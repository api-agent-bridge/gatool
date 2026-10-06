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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.Node;
import graphql.language.NodeTraverser;
import graphql.language.NodeVisitorStub;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

/**
 * Finds the directives that ask a GraphQL API to answer in pieces.
 *
 * <p>
 * {@code @defer} and {@code @stream} break the same rule a subscription does: a tool call
 * returns a single result. An API that serves incremental delivery answers over
 * {@code multipart/mixed}, which the transport refuses. An embedded run with incremental
 * support switched on answers the first payload with {@code hasNext} true, which reaches
 * the model as a whole result that is missing its deferred fields.
 *
 * <p>
 * Which of the two directives reaches this walk depends on the schema. Version 25.0 of
 * graphql-java declares {@code @defer} in every schema it builds, so a document using it
 * validates and runs. It leaves {@code @stream} undeclared, so validation refuses that
 * one first with {@code Unknown directive 'stream'}, and this walk sees it only when the
 * API's own SDL declares it. Both stay here, because the rule belongs to GATool and holds
 * whichever version of graphql-java runs underneath it.
 *
 * <p>
 * Running one is harmless on today's defaults: graphql-java ignores {@code @defer} unless
 * {@code ExperimentalApi.ENABLE_INCREMENTAL_SUPPORT} is in the context, and Spring for
 * GraphQL 2.0.1 does not put it there. An application that switches it on gets the
 * truncated answer above, without an error to show the model that anything is missing,
 * which is what this refuses ahead of.
 *
 * <p>
 * Both the trusted document path and the dynamic one have to apply the rule, and they
 * word their refusals differently, because one is written for startup output and the
 * other for the model that sent the document. So they share the walk and write their own
 * sentence.
 *
 * @author Željko Kozina
 */
public final class IncrementalDelivery {

	private static final Set<String> DIRECTIVES = Set.of("defer", "stream");

	private IncrementalDelivery() {
	}

	/**
	 * Returns the incremental delivery directives one document uses.
	 *
	 * <p>
	 * The whole document is walked, because the directives sit anywhere in the selection
	 * set: on a field, on a fragment spread or on an inline fragment.
	 * @param document the parsed document
	 * @return the directive names without their {@code @}, in the order the walk met
	 * them, and empty for a document that asks for its answer in one piece
	 */
	public static List<String> directivesIn(Document document) {
		List<String> directiveNames = new ArrayList<>();
		new NodeTraverser().depthFirst(new NodeVisitorStub() {

			// graphql-java declares the visitor's context with a raw Node, so overriding
			// it faithfully means repeating the raw type under -Werror.
			@Override
			@SuppressWarnings("rawtypes")
			public TraversalControl visitDirective(Directive directive, TraverserContext<Node> context) {
				if (DIRECTIVES.contains(directive.getName()) && !directiveNames.contains(directive.getName())) {
					directiveNames.add(directive.getName());
				}
				return TraversalControl.CONTINUE;
			}
		}, document);
		return List.copyOf(directiveNames);
	}

}
