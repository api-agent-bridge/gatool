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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import graphql.language.Comment;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.SelectionSet;
import graphql.language.SourceLocation;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import org.jspecify.annotations.Nullable;

/**
 * Finds the description of a tool: the {@code #} lines directly above the operation, or
 * else the schema description of the one root field it selects. Where the schema marks
 * that root field deprecated, the description ends with the deprecation and the reason
 * the schema gives.
 *
 * @author Željko Kozina
 */
public final class DescriptionResolver {

	private final GraphQLSchema schema;

	public DescriptionResolver(GraphQLSchema schema) {
		this.schema = schema;
	}

	/**
	 * Resolves the description of one tool.
	 * @param operation the validated operation
	 * @param diagnostics receives a warning when the tool lacks a description
	 * @return the description, or {@code null} where both sources stay silent and the
	 * root field is current
	 */
	public @Nullable String describe(ValidatedOperation operation, OperationDiagnostics diagnostics) {
		String description = describeFromComments(operation.file());
		if (description == null) {
			description = describeFromRootField(operation);
		}
		if (description == null) {
			// A generated operation does not have a file to write above, so the advice
			// points at the schema: describe the root field there, which is where the
			// second description source reads from anyway.
			diagnostics.warning(operation.location(), RootFieldOperations.isGenerated(operation.location())
					? "lacks a description; describe the root field in your schema, or write an operation file for "
							+ "it with # lines above the operation, because agents choose tools by their descriptions"
					: "lacks a description; write # lines directly above the operation, because agents choose tools "
							+ "by their descriptions");
		}
		return withDeprecationOfTheRootField(description, operation);
	}

	// Returns the description ending with the deprecation of the root field the operation
	// selects, or the description as it came where that field is current.
	//
	// The root field is what the tool is, so a model choosing the tool has to read that
	// the API is removing it. The startup warning reaches the team, and the description
	// is what the model reads.
	//
	// The sentence is the one the generator writes, and it is added whatever the
	// comment of the file says, because the reason is the API's own wording. A
	// generated operation is left as it came: its comment already ends with the
	// sentence, written by RootFieldOperations.
	//
	// A deprecated field deeper in the selection stays with the startup warning,
	// because it concerns a part of the result. An operation selecting two root fields
	// stays there as well: the sentence would say of the whole tool what holds for
	// one of its parts. The schema's own description is read under the same condition,
	// so "the root field the operation selects" means one thing in both places.
	//
	// A tool both sources leave undescribed still gets the sentence, and describe()
	// has raised its warning by then, because the reason says what the API removes
	// and leaves unsaid what the tool returns.
	private @Nullable String withDeprecationOfTheRootField(@Nullable String description, ValidatedOperation operation) {
		if (RootFieldOperations.isGenerated(operation.location())) {
			return description;
		}
		GraphQLFieldDefinition rootField = findTheRootField(operation);
		if (rootField == null || !rootField.isDeprecated()) {
			return description;
		}
		String deprecation = describeDeprecation(rootField.getDeprecationReason());
		return (description != null) ? description + " " + deprecation : deprecation;
	}

	/**
	 * Returns the sentence a tool description ends with where its root field is
	 * deprecated, as {@code Deprecated: Use movies.}, or {@code Deprecated.} for a reason
	 * without a character a reader can see.
	 * @param reason the reason the schema gives
	 * @return the sentence
	 */
	// graphql-java fills in "No longer supported" for a @deprecated written without a
	// reason, which is the default the specification gives the directive.
	static String describeDeprecation(@Nullable String reason) {
		return "Deprecated" + ((reason != null && !VisibleText.rendersEmpty(reason)) ? ": " + reason.strip() : ".");
	}

	// Returns the description written as # lines directly above the operation, or null
	// where those lines are absent.
	//
	// graphql-java's comment tokens decide what counts as a comment, and the file's own
	// lines decide where each comment sits, because graphql-java 25.0 reports the line
	// of a comment one higher than it is. A blank line ends the description, so a file
	// header above that blank line stays out.
	//
	// Lines without a character a reader can see count as absent. strip() and isEmpty()
	// know Java's whitespace, which leaves out the no-break space and the zero width
	// space, so a line holding one of them would pass as a description that renders
	// empty. VisibleText holds the rule, which @gatool(title:) takes as well.
	private static @Nullable String describeFromComments(OperationFile file) {
		OperationDefinition operation = file.operation();
		SourceLocation operationLocation = operation.getSourceLocation();
		List<Comment> comments = operation.getComments();
		if (operationLocation == null || comments.isEmpty()) {
			return null;
		}
		List<String> lines = file.source().content().lines().toList();
		Deque<String> description = new ArrayDeque<>();
		int lineIndex = operationLocation.getLine() - 2;
		for (int i = comments.size() - 1; i >= 0 && lineIndex >= 0; i--, lineIndex--) {
			String content = comments.get(i).getContent();
			if (!lines.get(lineIndex).strip().equals(("#" + content).strip())) {
				break;
			}
			description.addFirst(content.strip());
		}
		String text = String.join("\n", description).strip();
		return VisibleText.rendersEmpty(text) ? null : text;
	}

	// Returns the schema description of the root field, where the operation selects
	// exactly one and the schema describes it in words a reader can see.
	//
	// The rule of the comment lines holds here too, so a root field described as "" or as
	// a no-break space counts as undescribed.
	private @Nullable String describeFromRootField(ValidatedOperation operation) {
		GraphQLFieldDefinition definition = findTheRootField(operation);
		String description = (definition != null) ? definition.getDescription() : null;
		return (description == null || VisibleText.rendersEmpty(description)) ? null : description;
	}

	// Returns the root field the operation selects, where its selection set holds that
	// one field alone, or null where it holds more or the schema lacks the field.
	private @Nullable GraphQLFieldDefinition findTheRootField(ValidatedOperation operation) {
		SelectionSet selectionSet = operation.file().operation().getSelectionSet();
		List<Field> fields = selectionSet.getSelectionsOfType(Field.class);
		if (selectionSet.getSelections().size() != 1 || fields.size() != 1) {
			return null;
		}
		GraphQLObjectType rootType = (operation.operationType() == OperationType.MUTATION)
				? this.schema.getMutationType() : this.schema.getQueryType();
		return (rootType != null) ? rootType.getFieldDefinition(fields.getFirst().getName()) : null;
	}

}
