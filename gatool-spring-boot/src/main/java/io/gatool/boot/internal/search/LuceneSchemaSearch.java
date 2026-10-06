/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatool.boot.internal.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.analysis.miscellaneous.KeywordRepeatFilter;
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.IOUtils;

import io.gatool.core.search.CorpusEntry;
import io.gatool.core.search.SchemaSearch;
import io.gatool.core.search.SearchHit;

/**
 * Ranks schema coordinates with word statistics, which is what Lucene's default
 * similarity computes: BM25.
 *
 * <p>
 * This is the default backend, because it runs without a service, a key or a model, so
 * the dynamic layer works the moment it is switched on. What it finds is a field whose
 * declaration uses the words the question used; the embedding backend finds one that
 * answers in other words, and asks an application for a bean to do it.
 *
 * <p>
 * The index lives in memory, in a {@link ByteBuffersDirectory}, and is built once at
 * startup. A schema of a few thousand coordinates indexes in well under a second, and
 * nothing is written to disk. {@link #close()} releases the reader, the directory and the
 * analyzer, which is what a container calls when the application stops.
 *
 * <p>
 * A question arrives as words a person or a model wrote, so the analyzer turns it into
 * terms and those terms become one query. Lucene's own query parser would read
 * {@code title:foo} and {@code a AND b} as syntax, which is a meaning nobody sending a
 * question intends, and it lives in a third artifact this project leaves out. So the
 * escaping the parser needs does not arise here.
 *
 * <p>
 * The analyzer is built from Lucene's own filters, in {@code lucene-analysis-common},
 * because a schema without descriptions holds identifiers alone and a question holds
 * plain words: {@code StandardAnalyzer} keeps {@code topRatedMovies} as one term, so "top
 * rated movies" comes back empty. {@link WordDelimiterGraphFilter} splits an identifier
 * at case changes, underscores and digits, and keeps the identifier itself as a term
 * beside its parts, so a question that spells the identifier still matches it.
 * {@link PorterStemFilter} is the stemmer {@code EnglishAnalyzer} uses, and it conflates
 * a wider family than {@code KStemFilter} does: "rating", "rated" and "rate" all become
 * "rate", where KStem keeps "rating" as the dictionary word it is and so leaves a
 * question about ratings without a match on {@code topRated}. {@link KeywordRepeatFilter}
 * indexes the written form beside its stem, so an entry spelling the plural the question
 * wrote ranks above one that merely stems to it. The stop set stays empty: BM25 gives a
 * word that appears in most entries a score close to zero, and a question of common words
 * alone still ranks.
 *
 * @author Željko Kozina
 */
public final class LuceneSchemaSearch implements SchemaSearch, AutoCloseable {

	/**
	 * How many distinct terms of a question become clauses of the query.
	 *
	 * <p>
	 * Lucene refuses a query of more than 1,024 clauses, and a question of that many
	 * words, or one word repeated that often, would fail with a stack trace. A question
	 * is a sentence or two, and the words past the first few dozen add to every entry's
	 * score while leaving the order as it was. So the terms are taken in the order they
	 * were written until this many distinct ones are in hand.
	 */
	static final int MAX_TERMS = 64;

	// The parts of an identifier, its digits, a split at every case change and every
	// letter to digit boundary, the identifier itself kept beside its parts, and the
	// possessive "'s" dropped from a question such as "the movie's rating".
	private static final int WORD_DELIMITER_FLAGS = WordDelimiterGraphFilter.GENERATE_WORD_PARTS
			| WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS | WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE
			| WordDelimiterGraphFilter.SPLIT_ON_NUMERICS | WordDelimiterGraphFilter.PRESERVE_ORIGINAL
			| WordDelimiterGraphFilter.STEM_ENGLISH_POSSESSIVE;

	private static final String TEXT = "text";

	private static final String COORDINATE = "coordinate";

	private static final String TOKENS = "tokens";

	private static final String PATH = "path";

	private final Analyzer analyzer = new IdentifierAnalyzer();

	private final ByteBuffersDirectory directory = new ByteBuffersDirectory();

	private final DirectoryReader reader;

	private final IndexSearcher searcher;

	/**
	 * Indexes one corpus.
	 * @param corpus every field of the schema, from
	 * {@link io.gatool.core.search.SchemaCorpus}
	 */
	public LuceneSchemaSearch(List<CorpusEntry> corpus) {
		try {
			try (IndexWriter writer = new IndexWriter(this.directory, new IndexWriterConfig(this.analyzer))) {
				for (CorpusEntry entry : corpus) {
					Document document = new Document();
					document.add(new TextField(TEXT, entry.text(), Field.Store.YES));
					document.add(new StoredField(COORDINATE, entry.coordinate()));
					document.add(new StoredField(TOKENS, entry.tokens()));
					// Stored and left out of the index: a path holds the field names of
					// other types, which would match questions aimed at those other
					// types.
					document.add(new StoredField(PATH, (entry.path() != null) ? entry.path() : ""));
					writer.addDocument(document);
				}
				writer.commit();
			}
			this.reader = DirectoryReader.open(this.directory);
			this.searcher = new IndexSearcher(this.reader);
		}
		catch (IOException ex) {
			// An in-memory directory that fails to write is a GATool bug or a JVM out of
			// memory, and either way the schema cannot be searched, so startup stops here
			// instead of publishing a tool that cannot answer.
			throw new UncheckedIOException("GATool could not index the schema for searchSchema", ex);
		}
	}

	@Override
	public List<SearchHit> search(String question, int maxHits) {
		List<String> terms = termsOf(question);
		if (terms.isEmpty() || maxHits < 1) {
			return List.of();
		}
		BooleanQuery.Builder query = new BooleanQuery.Builder();
		for (String term : terms) {
			// SHOULD, so a question matches an entry carrying some of its words, and BM25
			// ranks by how many and how rare they are. A term query reads terms alone, so
			// the graph the delimiter filter produces cannot break the query.
			query.add(new TermQuery(new Term(TEXT, term)), BooleanClause.Occur.SHOULD);
		}
		try {
			// Two entries with the same score come back in index order, and the corpus
			// is sorted, so one question always answers in one order.
			TopDocs topDocs = this.searcher.search(query.build(), maxHits);
			List<SearchHit> hits = new ArrayList<>();
			for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
				Document document = this.searcher.storedFields().document(scoreDoc.doc);
				String path = document.get(PATH);
				hits.add(new SearchHit(document.get(COORDINATE), scoreDoc.score, document.get(TEXT),
						path.isEmpty() ? null : path, document.getField(TOKENS).numericValue().intValue()));
			}
			return List.copyOf(hits);
		}
		catch (IOException ex) {
			// The question stays out of the message, because the message lands in a log
			// and the question is the caller's text.
			throw new UncheckedIOException("searchSchema could not search the index", ex);
		}
	}

	/**
	 * Releases the index reader, the in-memory directory and the analyzer.
	 *
	 * <p>
	 * Closing twice is harmless, because each of the three ignores a second close, and a
	 * search after this one fails with Lucene's {@code AlreadyClosedException}.
	 */
	@Override
	public void close() {
		try {
			// Every one of the three is closed even when an earlier one fails, and the
			// first failure is what comes back.
			IOUtils.close(this.reader, this.directory, this.analyzer);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("GATool could not release the searchSchema index", ex);
		}
	}

	// Returns the distinct terms the analyzer finds in one question, in the order it read
	// them, up to MAX_TERMS.
	//
	// The same analyzer that indexed the corpus reads the question, so a word is split,
	// lower-cased and stemmed the same way when the corpus is indexed and when a
	// question is read. Anything else leaves a term missing from the index. A word the
	// question repeats becomes one clause: a second copy of the same term adds a second
	// clause with the same score, which is a longer query for the same order.
	private List<String> termsOf(String question) {
		Set<String> terms = new LinkedHashSet<>();
		try (TokenStream stream = this.analyzer.tokenStream(TEXT, question)) {
			CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
			stream.reset();
			// The stream is read to its end whatever the cap, because a token stream
			// expects end() after its last token, and the cap decides what is kept.
			while (stream.incrementToken()) {
				if (terms.size() < MAX_TERMS) {
					terms.add(term.toString());
				}
			}
			stream.end();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("searchSchema could not read the question", ex);
		}
		return List.copyOf(terms);
	}

	/**
	 * The chain that turns an identifier or a question into terms: Unicode word
	 * boundaries, the parts of an identifier beside the identifier itself, lower case,
	 * then each word's stem beside the word as written.
	 */
	// The delimiter filter emits a graph, with the identifier and its first part at
	// one position, and FlattenGraphFilter squashes it into the flat stream the
	// indexer consumes; the same chain reads a question, so index and query agree.
	// The keyword filter marks the written form so the stemmer leaves it alone and
	// stems the copy, and the duplicates filter drops the copy where stemming left
	// the word unchanged.
	private static final class IdentifierAnalyzer extends Analyzer {

		@Override
		protected TokenStreamComponents createComponents(String fieldName) {
			StandardTokenizer source = new StandardTokenizer();
			TokenStream result = new WordDelimiterGraphFilter(source, WORD_DELIMITER_FLAGS, null);
			result = new FlattenGraphFilter(result);
			result = new LowerCaseFilter(result);
			result = new KeywordRepeatFilter(result);
			result = new PorterStemFilter(result);
			result = new RemoveDuplicatesTokenFilter(result);
			return new TokenStreamComponents(source, result);
		}

	}

}
