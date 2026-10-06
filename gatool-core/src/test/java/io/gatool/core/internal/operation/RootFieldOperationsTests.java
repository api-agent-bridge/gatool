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

package io.gatool.core.internal.operation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;

import io.gatool.core.internal.schema.SdlSchemaFactory;
import io.gatool.core.naming.ToolNamingStrategy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every generated operation through a real GraphQL engine.
 *
 * <p>
 * Startup validates each generated document, and validation is not execution. A document
 * can satisfy every validation rule and still fail when an engine runs it, so these tests
 * execute each one against a schema with data behind it and assert that it comes back
 * without a GraphQL error.
 *
 * <p>
 * The schema is built to be awkward on purpose: an interface implementing another
 * interface, a type implementing two, a union whose members share a field name, mutual
 * recursion, a Relay-shaped wrapper, a list of lists, fields that demand an argument, and
 * a root field returning a scalar.
 */
class RootFieldOperationsTests {

	private static final String SDL = """
			interface Node { id: ID! }

			interface Titled implements Node { id: ID!  title: String! }

			type Movie implements Titled & Node {
			  id: ID!
			  title: String!
			  year: Int
			  tags: [[String!]!]
			  similar(first: Int!): [Movie!]!
			  reviews: [Review!]!
			}

			type Review implements Node { id: ID!  score: Int!  movie: Movie! }

			type Studio implements Titled & Node { id: ID!  title: String!  founded: Int }

			union Result = Movie | Studio

			type MovieEdge { cursor: String!  node: Movie! }

			type MovieConnection { edges: [MovieEdge!]!  total: Int! }

			type Query {
			  movie(id: ID!): Movie
			  movies(after: String): MovieConnection!
			  search(text: String!): [Result!]!
			  node(id: ID!): Node
			  titled: Titled
			  tagline: String!
			  onlyArguments: Needy
			}

			type Needy { needs(what: String!): String! }

			type Mutation { rate(id: ID!, score: Int!): Review!  movie(id: ID!): Movie }
			""";

	@Test
	void everyGeneratedOperation_atEveryDepth_shouldExecuteWithoutAGraphQlError() {
		GraphQLSchema schema = executableSchema();
		for (int depth = 1; depth <= RootFieldOperations.MAX_DEPTH; depth++) {
			OperationDiagnostics diagnostics = new OperationDiagnostics();
			List<OperationSource> generated = RootFieldOperations.write(schema, generation(depth, true), Set.of(),
					diagnostics);
			assertThat(generated).as("depth %s generates something", depth).isNotEmpty();
			assertThat(diagnostics.problems()).as("depth %s does not report a problem", depth).isEmpty();
			for (OperationSource source : generated) {
				ExecutionResult result = GraphQL.newGraphQL(schema)
					.build()
					.execute((builder) -> builder.query(source.content()).variables(variablesFor(source.content())));
				assertThat(result.getErrors())
					.as("depth %s, %s, document:%n%s", depth, source.location(), source.content())
					.isEmpty();
			}
		}
	}

	@Test
	void everyGeneratedOperation_shouldPassTheSameValidationAWrittenFileDoes() {
		GraphQLSchema schema = executableSchema();
		OperationValidator validator = new OperationValidator(schema);
		OperationFileParser parser = new OperationFileParser();
		for (int depth = 1; depth <= RootFieldOperations.MAX_DEPTH; depth++) {
			OperationDiagnostics diagnostics = new OperationDiagnostics();
			for (OperationSource source : RootFieldOperations.write(schema, generation(depth, true), Set.of(),
					diagnostics)) {
				ParsedFile parsed = parser.parse(source, diagnostics);
				assertThat(parsed).as("%s parses", source.location()).isInstanceOf(OperationFile.class);
				assertThat(validator.validate((OperationFile) parsed, diagnostics))
					.as("%s validates", source.location())
					.isNotNull();
			}
			assertThat(diagnostics.problems()).as("depth %s", depth).isEmpty();
		}
	}

	@Test
	void aFieldDemandingAnArgument_shouldBeLeftOutOfEverySelection() {
		// Needy.needs(what: String!) cannot be selected without inventing a value, so the
		// only root field returning Needy does not yield a selection and is skipped with
		// a warning.
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		List<OperationSource> generated = RootFieldOperations.write(executableSchema(), generation(5, true), Set.of(),
				diagnostics);

		assertThat(generated).noneMatch((source) -> source.content().contains("needs"))
			.noneMatch((source) -> source.location().endsWith("/onlyArguments"));
		assertThat(diagnostics.warnings()).anyMatch((warning) -> warning.message().contains("onlyArguments"));
		// similar(first: Int!) sits inside Movie and is skipped the same way, which
		// leaves the rest of Movie selectable.
		assertThat(generated).noneMatch((source) -> source.content().contains("similar"))
			.anyMatch((source) -> source.content().contains("title"));
	}

	@Test
	void mutualRecursion_shouldStopAtTheDepth() {
		// Movie holds reviews, and a Review holds its movie, so a walk that followed the
		// graph would not end.
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		List<OperationSource> generated = RootFieldOperations.write(executableSchema(), generation(5, false), Set.of(),
				diagnostics);

		String movie = generated.stream()
			.filter((source) -> source.location().endsWith("/movie"))
			.findFirst()
			.orElseThrow()
			.content();
		// Movie, then Review, then the Movie inside it is the repeat that stops the walk.
		assertThat(movie).contains("reviews").contains("score");
		assertThat(countOf(movie, "title")).isEqualTo(1);
	}

	@Test
	void aRootFieldNameOnBothRoots_shouldBeCoveredPerRoot() {
		// movie sits on Query and on Mutation. A file reading the query one covers that
		// root alone, because the covered set holds each field name with the root it came
		// from. The file is on both sides, so the query root field is covered everywhere
		// and the mutation one is the only movie the generator writes, which keeps the
		// generated names apart.
		GraphQLSchema schema = executableSchema();
		String file = """
				# Reads one film.
				query ReadMovie { movie(id: "1") { id } }
				""";

		OperationCatalog catalog = OperationCatalogFactory.builder(schema, ToolNamingStrategy.camelCase())
			.generation(new OperationCatalogFactory.Generation(true, true, true, true, 1))
			.build()
			.create(List.of(new OperationSource("file:/mcp/ReadMovie.graphql", file,
					Set.of(ToolExposureType.MCP, ToolExposureType.IN_PROCESS))));

		assertThat(catalog.problems()).isEmpty();
		for (ToolExposureType side : ToolExposureType.values()) {
			assertThat(catalog.toolsFor(side)).extracting(ToolOperation::toolName)
				.as("%s tool names", side)
				.contains("readMovie", "rate", "movie");
			assertThat(catalog.toolsFor(side)).extracting(ToolOperation::location)
				.as("%s locations", side)
				.contains("generated:mutation/movie")
				.doesNotContain("generated:query/movie");
		}
	}

	@Test
	void aFileOnOneSide_shouldLeaveTheOtherSidesGeneratedTool() {
		// The file covers the query root field on the MCP side alone, which is the only
		// side it was configured for. The other side, where that file is absent, keeps
		// the generated tool.
		String file = """
				# Reads one film.
				query ReadMovie { movie(id: "1") { id } }
				""";

		OperationCatalog catalog = OperationCatalogFactory.builder(executableSchema(), ToolNamingStrategy.camelCase())
			.generation(new OperationCatalogFactory.Generation(true, false, true, true, 1))
			.build()
			.create(List.of(new OperationSource("file:/mcp/ReadMovie.graphql", file, Set.of(ToolExposureType.MCP))));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.toolsFor(ToolExposureType.MCP)).extracting(ToolOperation::toolName)
			.contains("readMovie", "tagline")
			.doesNotContain("movie");
		assertThat(catalog.toolsFor(ToolExposureType.IN_PROCESS)).extracting(ToolOperation::toolName)
			.contains("movie", "tagline")
			.doesNotContain("readMovie");
	}

	@Test
	void aRootFieldEveryFileCovers_shouldEarnNoGenerationWarning() {
		// onlyArguments returns Needy, whose one field demands an argument, so the
		// generator cannot write it and warns. The file answers for it on both sides, so
		// the warning stays out.
		String file = """
				# What the schema needs.
				query OnlyArguments { onlyArguments { needs(what: "something") } }
				""";

		OperationCatalog catalog = OperationCatalogFactory.builder(executableSchema(), ToolNamingStrategy.camelCase())
			.generation(generation(1, false))
			.build()
			.create(List.of(new OperationSource("file:/shared/OnlyArguments.graphql", file,
					Set.of(ToolExposureType.MCP, ToolExposureType.IN_PROCESS))));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::toolName).contains("onlyArguments");
		assertThat(catalog.warnings()).noneMatch((warning) -> warning.message().contains("'onlyArguments'"));
	}

	@Test
	void aRootFieldOneSideCovers_shouldStillEarnTheGenerationWarningForTheOtherSide() {
		// The in-process side lacks a file for it, so that side still lacks a tool for
		// the root field, and the warning is about that side.
		String file = """
				# What the schema needs.
				query OnlyArguments { onlyArguments { needs(what: "something") } }
				""";

		OperationCatalog catalog = OperationCatalogFactory.builder(executableSchema(), ToolNamingStrategy.camelCase())
			.generation(generation(1, false))
			.build()
			.create(List
				.of(new OperationSource("file:/mcp/OnlyArguments.graphql", file, Set.of(ToolExposureType.MCP))));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).anyMatch((warning) -> warning.message().contains("'onlyArguments'")
				&& warning.message().contains("leaves without a generated tool"));
	}

	@Test
	void aDeprecatedRootFieldWithATwoLineDescription_shouldStillBecomeATool() {
		// strip() keeps the line break inside a description, so each line needs its own
		// #, or the second line falls outside the comment and the parser reads "Invalid
		// syntax with offending token 'Use' at line 2 column 1".
		OperationCatalog catalog = OperationCatalogFactory
			.builder(twoLineDeprecationSchema(), ToolNamingStrategy.camelCase())
			.generation(generation(1, false))
			.build()
			.create(List.of());

		String state = "tools=" + catalog.tools().stream().map(ToolOperation::toolName).toList() + " problems="
				+ catalog.problems() + " warnings=" + catalog.warnings();
		assertThat(catalog.tools()).as(state).extracting(ToolOperation::toolName).contains("old", "films");
		assertThat(catalog.warnings()).as(state)
			.noneMatch((warning) -> warning.message().contains("could not be read back"));
		// Every line reaches the description, joined the way DescriptionResolver joins
		// comment lines.
		assertThat(catalog.tools()).extracting(ToolOperation::description)
			.contains("Every film.\nUse with care. Deprecated: Use films instead.");
	}

	@Test
	void theGeneratedDocumentForADeprecatedRootFieldWithATwoLineDescription_shouldParseAsAnOperationFile() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		List<OperationSource> generated = RootFieldOperations.write(twoLineDeprecationSchema(), generation(1, false),
				Set.of(), diagnostics);
		OperationSource old = generated.stream()
			.filter((source) -> source.location().endsWith("/old"))
			.findFirst()
			.orElseThrow();

		ParsedFile parsed = new OperationFileParser().parse(old, diagnostics);

		assertThat(parsed).as("document:%n%s%nproblems: %s", old.content(), diagnostics.problems())
			.isInstanceOf(OperationFile.class);
		assertThat(old.content()).startsWith("# Every film.\n# Use with care. Deprecated: Use films instead.\n");
	}

	private static GraphQLSchema twoLineDeprecationSchema() {
		return schemaOf("""
				type Query {
				  \"""
				  Every film.
				  Use with care.
				  \"""
				  old: String @deprecated(reason: "Use films instead.")
				  films: [String!]!
				}
				""");
	}

	@Test
	void theAsWrittenStrategy_shouldGiveAGeneratedToolTheRootFieldsOwnName() {
		// The operation takes the field name exactly, so every strategy lands on a name
		// that matches the field. Capitalising it first would leave as-written publishing
		// TopRatedMovies for a field called topRatedMovies.
		OperationCatalog catalog = OperationCatalogFactory.builder(executableSchema(), (graphQlName) -> graphQlName)
			.generation(new OperationCatalogFactory.Generation(true, false, true, true, 1))
			.build()
			.create(List.of());

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::toolName)
			.contains("movie", "tagline", "titled")
			.doesNotContain("Movie", "Tagline");
	}

	@Test
	void unionMembersGivingOneNameTwoShapes_shouldLeaveThatFieldOut() {
		// GraphQL 5.3.2 FieldsInSetCanMerge reads every pair of fields sharing a response
		// name across the inline fragments of one selection set and requires the same
		// response shape. A success type and a failure type that each carry their own
		// code is the errors-as-data pattern, and selecting both would give a document
		// the validator refuses at the default depth of 1.
		GraphQLSchema schema = schemaOf("""
				type Ok { code: Int  message: String }
				type Failed { code: String  message: String }
				union Outcome = Ok | Failed
				type Query { result(q: String): Outcome  greeting: String! }
				""");
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		List<OperationSource> generated = RootFieldOperations.write(schema, generation(1, false), Set.of(),
				diagnostics);

		String result = generated.stream()
			.filter((source) -> source.location().endsWith("/result"))
			.findFirst()
			.orElseThrow()
			.content();
		// code differs in shape and leaves; message agrees and stays.
		assertThat(result).doesNotContain("code").contains("message").contains("__typename");
		assertThat(GraphQL.newGraphQL(schema).build().execute(result).getErrors()).isEmpty();
		// And the healthy root field beside it still gets its tool.
		assertThat(generated).anyMatch((source) -> source.location().endsWith("/greeting"));
	}

	@Test
	void aGeneratedOperationThatCannotValidate_shouldWarnAndLetStartupCarryOn() {
		// A field name longer than a tool name may be. It is skipped with a warning, as
		// every other unusable root field is.
		String tooLong = "a".repeat(80);
		GraphQLSchema schema = schemaOf("type Query { " + tooLong + ": String!  greeting: String! }");

		OperationCatalog catalog = OperationCatalogFactory.builder(schema, ToolNamingStrategy.camelCase())
			.generation(new OperationCatalogFactory.Generation(true, false, true, true, 1))
			.build()
			.create(List.of());

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::toolName).containsExactly("greeting");
		assertThat(catalog.warnings()).anyMatch((warning) -> warning.location().contains(tooLong));
	}

	@Test
	void aDeprecatedRootField_shouldTellTheModelSoInItsDescription() {
		// It still becomes a tool, because an application may still want it, and the
		// model reads the deprecation where it reads the description.
		GraphQLSchema schema = schemaOf("""
				type Query {
				  "Every film." old: String! @deprecated(reason: "Use films instead.")
				  plain: String! @deprecated
				}
				""");

		OperationCatalog catalog = OperationCatalogFactory.builder(schema, ToolNamingStrategy.camelCase())
			.generation(new OperationCatalogFactory.Generation(true, false, true, true, 1))
			.build()
			.create(List.of());

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::description)
			.contains("Every film. Deprecated: Use films instead.", "Deprecated: No longer supported");
	}

	@Test
	void aDeprecatedRootFieldDescribedWithInvisibleCharacters_shouldDescribeItsToolByTheDeprecationAlone() {
		// isBlank() knows Java's whitespace, which leaves the no-break space and the
		// filler out, so a rule built on it would put each into the description in front
		// of the deprecation, and publish a reason of a zero width space after the colon.
		GraphQLSchema schema = schemaOf("""
				type Query {
				  "\\u00A0\\u3164" old: String! @deprecated(reason: "Use films instead.")
				  plain: String! @deprecated(reason: "\\u200B")
				}
				""");

		OperationCatalog catalog = OperationCatalogFactory.builder(schema, ToolNamingStrategy.camelCase())
			.generation(generation(1, false))
			.build()
			.create(List.of());

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::description)
			.containsExactly("Deprecated: Use films instead.", "Deprecated.");
	}

	@Test
	void aDeprecatedRootField_byDefault_shouldStillBecomeATool() {
		GraphQLSchema schema = deprecatedRootFieldSchema();

		OperationCatalog catalog = OperationCatalogFactory.builder(schema, ToolNamingStrategy.camelCase())
			.generation(generation(1, false))
			.build()
			.create(List.of());

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::toolName).containsExactly("old", "plain");
	}

	@Test
	void aDeprecatedRootField_withDeprecatedRootFieldsTurnedOff_shouldBeLeftOut() {
		GraphQLSchema schema = deprecatedRootFieldSchema();
		OperationCatalogFactory.Generation withoutDeprecatedRootFields = new OperationCatalogFactory.Generation(true,
				false, false, true, 1);

		OperationCatalog catalog = OperationCatalogFactory.builder(schema, ToolNamingStrategy.camelCase())
			.generation(withoutDeprecatedRootFields)
			.build()
			.create(List.of());

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::toolName).containsExactly("plain");
		// The property asked for this, so startup stays quiet about it.
		assertThat(catalog.warnings()).noneMatch((warning) -> warning.message().contains("'old'"));
	}

	@Test
	void aDeprecatedField_byDefault_shouldJoinTheGeneratedSelection() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		List<OperationSource> generated = RootFieldOperations.write(deprecatedFieldSchema(), generation(1, false),
				Set.of(), diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(documentFor(generated, "movie")).contains("title").contains("name").contains("id");
	}

	@Test
	void aDeprecatedField_withDeprecatedFieldsTurnedOff_shouldBeLeftOutOfTheSelection() {
		OperationCatalogFactory.Generation withoutDeprecatedFields = new OperationCatalogFactory.Generation(true, false,
				true, false, 1);
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		List<OperationSource> generated = RootFieldOperations.write(deprecatedFieldSchema(), withoutDeprecatedFields,
				Set.of(), diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		// The root field keeps its tool, and the two fields the schema still recommends
		// keep their place in the selection.
		assertThat(documentFor(generated, "movie")).doesNotContain("title").contains("name").contains("id");
	}

	@Test
	void aTypeOfferingOnlyDeprecatedFields_shouldNameThePropertyThatEmptiedItsSelection() {
		// The depth advice would be wrong here: no depth reaches a field the property
		// leaves out, so the message names the property and what to do with it.
		GraphQLSchema schema = schemaOf("""
				type Legacy { code: String! @deprecated(reason: "Use id.") }
				type Query { legacy: Legacy  plain: String! }
				""");
		OperationCatalogFactory.Generation withoutDeprecatedFields = new OperationCatalogFactory.Generation(true, false,
				true, false, 1);
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		List<OperationSource> generated = RootFieldOperations.write(schema, withoutDeprecatedFields, Set.of(),
				diagnostics);

		assertThat(generated).anyMatch((source) -> source.location().endsWith("/plain"))
			.noneMatch((source) -> source.location().endsWith("/legacy"));
		assertThat(diagnostics.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning.message()).contains("'legacy'")
				.contains("gatool.dev.experimental.generated-operations-include-deprecated-fields is false")
				.contains("Set that property to true for this schema")
				.doesNotContain("depth")
				.doesNotContain("inventing an argument"));
	}

	@Test
	void aFieldDemandingAnArgument_withDeprecatedFieldsTurnedOff_shouldStillGetTheDepthAdvice() {
		// The switch is off here and every field under this root field is current, so the
		// reader keeps the advice that fits: the argument, and the depth that reaches
		// past it.
		GraphQLSchema schema = schemaOf("""
				type Deep { s: String }
				type Mid { needs(what: String!): String!  deep: Deep }
				type Query { mid: Mid  plain: String }
				""");
		OperationCatalogFactory.Generation withoutDeprecatedFields = new OperationCatalogFactory.Generation(true, false,
				true, false, 1);
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		RootFieldOperations.write(schema, withoutDeprecatedFields, Set.of(), diagnostics);

		assertThat(diagnostics.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning.message())
				.contains("inventing an argument for a field that demands one")
				.contains("A depth of 5 reaches it")
				.doesNotContain("generated-operations-include-deprecated-fields"));
	}

	@Test
	void aWideGraphAtDepthFive_shouldBeLeftOut() {
		// A selection set grows with the shape of the graph: eight levels of six object
		// fields each write over 170,000 characters, which parse and validate, so nothing
		// downstream refuses them, and the schemas derived from them travel in every
		// tools/list. This schema is thinner than that and still reaches 61,912, so the
		// bound is what the assertion reads.
		StringBuilder sdl = new StringBuilder();
		for (int level = 0; level < 8; level++) {
			sdl.append("type T").append(level).append(" {\n  s: String\n");
			for (int field = 0; field < 6; field++) {
				sdl.append("  o").append(field).append(": T").append(level + 1).append('\n');
			}
			sdl.append("}\n");
		}
		sdl.append("type T8 { s: String }\ntype Query { root: T0  plain: String! }\n");

		OperationCatalog catalog = OperationCatalogFactory
			.builder(schemaOf(sdl.toString()), ToolNamingStrategy.camelCase())
			.generation(new OperationCatalogFactory.Generation(true, false, true, true, 5))
			.build()
			.create(List.of());

		assertThat(catalog.problems()).isEmpty();
		// The healthy root field keeps its tool, and the wide one says why it was left
		// out.
		assertThat(catalog.tools()).extracting(ToolOperation::toolName).containsExactly("plain");
		assertThat(catalog.warnings()).anyMatch((warning) -> warning.message().contains("root")
				&& warning.message().contains("past the 20000 GATool publishes")
				&& warning.message().contains("generated-selection-depth"));
	}

	@Test
	void aDeprecatedFieldBehindMoreDepth_withDeprecatedFieldsTurnedOff_shouldAskForBothSettings() {
		// The property alone leaves it empty at this depth and a deeper walk alone leaves
		// it empty too, so naming either one on its own would point at a setting
		// that does not change anything.
		GraphQLSchema schema = schemaOf("""
				type Inner { a: String }
				type Legacy { old: Inner @deprecated(reason: "Use a.") }
				type Query { legacy: Legacy }
				""");
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		RootFieldOperations.write(schema, withoutDeprecatedFields(1), Set.of(), diagnostics);

		assertThat(onlyWarning(diagnostics))
			.contains("gatool.dev.experimental.generated-operations-include-deprecated-fields is false")
			.contains("A depth of " + RootFieldOperations.MAX_DEPTH + " with that property set to true reaches it")
			.doesNotContain("inventing an argument")
			.doesNotContain("No depth reaches it");
	}

	@Test
	void aDeprecatedFieldBesideADeeperOne_withDeprecatedFieldsTurnedOff_shouldOfferTheDepth() {
		// Two remedies work here, so withholding the depth would hide the one that keeps
		// the property as it was set.
		GraphQLSchema schema = schemaOf("""
				type Deep { s: String }
				type Legacy { code: String! @deprecated(reason: "Use s.")  nested: Deep }
				type Query { legacy: Legacy }
				""");
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		RootFieldOperations.write(schema, withoutDeprecatedFields(1), Set.of(), diagnostics);

		assertThat(onlyWarning(diagnostics))
			.contains("gatool.dev.experimental.generated-operations-include-deprecated-fields is false")
			.contains("raise gatool.dev.experimental.generated-selection-depth to " + RootFieldOperations.MAX_DEPTH)
			.doesNotContain("inventing an argument");
	}

	@Test
	void anExclusionThatBlocksEveryDepth_shouldNameTheProperty() {
		// A deeper walk reaches the field once the property allows it, so the warning
		// names the property and leaves out "No depth reaches it".
		GraphQLSchema schema = schemaOf("""
				type Deep { old: String @deprecated(reason: "Gone.") }
				type Mid { deep: Deep }
				type Query { mid: Mid }
				""");
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		RootFieldOperations.write(schema, withoutDeprecatedFields(1), Set.of(), diagnostics);

		assertThat(onlyWarning(diagnostics))
			.contains("gatool.dev.experimental.generated-operations-include-deprecated-fields is false")
			.doesNotContain("No depth reaches it")
			.doesNotContain("inventing an argument");
	}

	@Test
	void anArgumentDefaultOfEachLiteralKind_shouldPrintAsTheVariablesDefaultAndReparse() {
		// A default written in SDL is held as a literal, and AstPrinter writes it back as
		// GraphQL text, so an enum, a list, an input object and a block string each reach
		// the variable declaration in a form the parser reads again. The compact printer
		// drops the spaces inside a list and an object, and a block string comes back as
		// a one-line string with its line feed escaped, which is the same value.
		GraphQLSchema schema = schemaOf("""
				enum Kind { DRAMA COMEDY }
				input Lookup { id: ID, title: String }
				type Query {
				  movies(kind: Kind = DRAMA, tags: [String!] = ["a", "b"], by: Lookup = {id: "1"},
				    note: String = \"""multi
				  line\""", first: Int = 25, exact: Float = 1.5, sorted: Boolean = true): [String!]!
				}
				""");
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		List<OperationSource> generated = RootFieldOperations.write(schema, generation(1, false), Set.of(),
				diagnostics);

		String document = documentFor(generated, "movies");
		assertThat(document).contains("$kind: Kind = DRAMA")
			.contains("$tags: [String!] = [\"a\",\"b\"]")
			.contains("$by: Lookup = {id:\"1\"}")
			.contains("$note: String = \"multi\\nline\"")
			.contains("$first: Int = 25")
			.contains("$exact: Float = 1.5")
			.contains("$sorted: Boolean = true");
		OperationSource source = generated.stream()
			.filter((candidate) -> candidate.location().endsWith("/movies"))
			.findFirst()
			.orElseThrow();
		ParsedFile parsed = new OperationFileParser().parse(source, diagnostics);
		assertThat(parsed).isInstanceOf(OperationFile.class);
		assertThat(new OperationValidator(schema).validate((OperationFile) parsed, diagnostics)).isNotNull();
		assertThat(diagnostics.problems()).isEmpty();
	}

	private static String onlyWarning(OperationDiagnostics diagnostics) {
		assertThat(diagnostics.warnings()).hasSize(1);
		return diagnostics.warnings().getFirst().message();
	}

	private static OperationCatalogFactory.Generation withoutDeprecatedFields(int depth) {
		return new OperationCatalogFactory.Generation(true, false, true, false, depth);
	}

	private static GraphQLSchema deprecatedRootFieldSchema() {
		return schemaOf("""
				type Query {
				  old: String! @deprecated(reason: "Use films instead.")
				  plain: String!
				}
				""");
	}

	private static GraphQLSchema schemaOf(String sdl) {
		return SdlSchemaFactory.schemaFrom(sdl, "generated-test.graphqls");
	}

	private static GraphQLSchema deprecatedFieldSchema() {
		return schemaOf("""
				type Movie { id: ID!  title: String! @deprecated(reason: "Use name.")  name: String! }
				type Query { movie: Movie }
				""");
	}

	private static String documentFor(List<OperationSource> generated, String rootField) {
		return generated.stream()
			.filter((source) -> source.location().endsWith("/" + rootField))
			.findFirst()
			.orElseThrow()
			.content();
	}

	// The defaults, which every test but the deprecation ones asks for: the generator on,
	// a deprecated root field still a tool, and a deprecated field still selected.
	private static OperationCatalogFactory.Generation generation(int depth, boolean includeMutations) {
		return new OperationCatalogFactory.Generation(true, includeMutations, true, true, depth);
	}

	private static long countOf(String text, String word) {
		return text.lines().filter((line) -> line.strip().equals(word)).count();
	}

	// Every generated variable is optional or takes the value a required argument needs,
	// so one map covers every document: an unused entry is a GraphQL error, so only the
	// names a document declares are sent.
	private static Map<String, Object> variablesFor(String document) {
		Map<String, Object> all = Map.of("id", "1", "text", "kepler", "after", "cursor-1", "score", 9);
		Map<String, Object> sent = new LinkedHashMap<>();
		all.forEach((name, value) -> {
			if (document.contains("$" + name + ":")) {
				sent.put(name, value);
			}
		});
		return sent;
	}

	private static GraphQLSchema executableSchema() {
		TypeDefinitionRegistry registry = new SchemaParser().parse(SDL);
		RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
			.type("Node", (builder) -> builder.typeResolver(
					(environment) -> environment.getSchema().getObjectType(typeNameOf(environment.getObject()))))
			.type("Titled", (builder) -> builder.typeResolver(
					(environment) -> environment.getSchema().getObjectType(typeNameOf(environment.getObject()))))
			.type("Result", (builder) -> builder.typeResolver(
					(environment) -> environment.getSchema().getObjectType(typeNameOf(environment.getObject()))))
			.type("Query", (builder) -> builder.dataFetcher("movie", (environment) -> movie())
				.dataFetcher("movies",
						(environment) -> Map.of("edges", List.of(Map.of("cursor", "c1", "node", movie())), "total", 1))
				.dataFetcher("search", (environment) -> List.of(movie(), studio()))
				.dataFetcher("node", (environment) -> movie())
				.dataFetcher("titled", (environment) -> studio())
				.dataFetcher("tagline", (environment) -> "Films, indexed.")
				.dataFetcher("onlyArguments", (environment) -> Map.of("needs", "something")))
			.type("Mutation",
					(builder) -> builder.dataFetcher("rate", (environment) -> review())
						.dataFetcher("movie", (environment) -> movie()))
			.build();
		return new SchemaGenerator().makeExecutableSchema(registry, wiring);
	}

	private static String typeNameOf(Object value) {
		return String.valueOf(((Map<?, ?>) value).get("__typename"));
	}

	private static Map<String, Object> movie() {
		return Map.of("__typename", "Movie", "id", "m1", "title", "Arrival", "year", 2016, "tags",
				List.of(List.of("scifi")), "similar", List.of(), "reviews", List.of(review()));
	}

	private static Map<String, Object> review() {
		return Map.of("__typename", "Review", "id", "r1", "score", 9, "movie", Map.of("__typename", "Movie", "id", "m1",
				"title", "Arrival", "year", 2016, "tags", List.of(), "similar", List.of(), "reviews", List.of()));
	}

	private static Map<String, Object> studio() {
		return Map.of("__typename", "Studio", "id", "s1", "title", "Paramount", "founded", 1912);
	}

}
