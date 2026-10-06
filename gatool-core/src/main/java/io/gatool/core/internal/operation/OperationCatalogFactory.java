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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.GraphQLSchema;
import org.jspecify.annotations.Nullable;

import io.gatool.core.internal.naming.ToolNameRules;
import io.gatool.core.internal.parser.TrustedText;
import io.gatool.core.internal.schema.InputSchema;
import io.gatool.core.internal.schema.InputSchemaWriter;
import io.gatool.core.internal.schema.OutputSchema;
import io.gatool.core.internal.schema.OutputSchemaWriter;
import io.gatool.core.internal.schema.ScalarSchemas;
import io.gatool.core.model.RequestLimits;
import io.gatool.core.naming.ToolNamingStrategy;

/**
 * Runs every operation file through the pipeline once and builds the catalog of tools for
 * both exposure types.
 *
 * @author Željko Kozina
 */
public final class OperationCatalogFactory {

	// One tool worth a few thousand tokens leaves that much less room for the work the
	// agent was asked to do. An input graph that inlines wide or deep is the way a
	// generated tool gets there without anyone noticing.
	private static final int LARGE_TOOL_TOKENS = 4000;

	private final GraphQLSchema schema;

	private final OperationFileParser parser = new OperationFileParser();

	private final OperationValidator validator;

	private final DescriptionResolver descriptions;

	private final DeprecatedSelections deprecatedSelections;

	private final ToolNamingStrategy namingStrategy;

	// The property sets the default for every tool, and @gatool(outputSchema:)
	// overrides it either way, because MCP binds a server to a schema it publishes and
	// the file knows how awkward its own response shape is.
	private final boolean outputSchemaByDefault;

	// The fragments arrive unchecked, because checking them needs the schema and the
	// diagnostics that build() creates, so a bad fragment joins the same batch of
	// problems as a bad operation file, and startup reports every one at once.
	private final Map<String, ?> configuredScalarSchemas;

	private final RequestLimits requestLimits;

	// The fragments of gatool.results.scalar-schemas, which arrive unchecked for the
	// same reason and join the same batch of problems.
	private final Map<String, ?> configuredResultScalarSchemas;

	private final Generation generation;

	// What the adapter of each exposure type adds to a schema it publishes, which the
	// estimate behind the warning about a large tool counts.
	private final List<SchemaAddition> schemaAdditions;

	private OperationCatalogFactory(Builder builder) {
		this.schemaAdditions = builder.schemaAdditions;
		this.requestLimits = builder.requestLimits;
		this.schema = builder.schema;
		this.validator = new OperationValidator(builder.schema);
		this.descriptions = new DescriptionResolver(builder.schema);
		this.deprecatedSelections = new DeprecatedSelections(builder.schema);
		this.namingStrategy = builder.namingStrategy;
		this.outputSchemaByDefault = builder.outputSchemaByDefault;
		this.configuredScalarSchemas = builder.scalarSchemas;
		this.configuredResultScalarSchemas = builder.resultScalarSchemas;
		this.generation = builder.generation;
	}

	/**
	 * Starts a factory for one schema.
	 * @param schema the schema every operation is validated against
	 * @param namingStrategy turns an operation name into a tool name
	 * @return a builder whose other settings start at their defaults: output schemas off,
	 * scalars unconfigured, the default request limits, generation off
	 */
	public static Builder builder(GraphQLSchema schema, ToolNamingStrategy namingStrategy) {
		return new Builder(schema, namingStrategy);
	}

	/**
	 * Builds the catalog.
	 * @param sources every distinct operation file, with the exposure types that use it
	 * @return the tools, with every problem and warning of every file
	 */
	public OperationCatalog create(List<OperationSource> sources) {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ScalarSchemas scalarSchemas = ScalarSchemas.of(this.schema, this.configuredScalarSchemas, diagnostics);
		// Built once for the catalog, like the fragments above. The results map is
		// checked whatever gatool.results.publish-output-schema says, as the input map is
		// checked whether or not an operation uses the scalar. A file can turn its own
		// output schema on with @gatool(outputSchema: true), and a bad entry found on
		// that day would stop startup over a change to an operation file.
		ScalarSchemas resultScalarSchemas = scalarSchemas
			.forResults(ScalarSchemas.ofResults(this.schema, this.configuredResultScalarSchemas, diagnostics));
		List<ToolOperation> tools = new ArrayList<>();
		Map<String, List<String>> toolNamesByUnmappedScalar = new LinkedHashMap<>();
		// The files are parsed before anything is generated, because a root field an
		// operation file already covers is left to that operation.
		List<FragmentFile> fragmentFiles = new ArrayList<>();
		List<OperationFile> files = parseOperationFiles(sources, fragmentFiles, diagnostics);
		// The shared fragments join each document before it is validated, so the
		// validator, the schema writers and the executor all read the assembled text.
		SharedFragments sharedFragments = SharedFragments.of(fragmentFiles, diagnostics);
		files.replaceAll((file) -> sharedFragments.attachTo(file, diagnostics));
		files.addAll(generateMissingRootFields(files, diagnostics));
		for (OperationFile file : files) {
			addTool(file, scalarSchemas, resultScalarSchemas, tools, toolNamesByUnmappedScalar, diagnostics);
		}
		sharedFragments.checkUnused(this.schema, diagnostics);
		reportUnmappedScalars(toolNamesByUnmappedScalar, diagnostics);
		// A generated tool yields before it becomes a problem, because the instruction a
		// clash gives, rename one of the operations, cannot be followed for an operation
		// GATool wrote.
		List<ToolOperation> kept = ToolNameClashes.withoutGeneratedClashes(tools, diagnostics);
		ToolNameClashes.reportNameClashes(kept, diagnostics);
		warnOnLargeTools(kept, this.schemaAdditions, diagnostics);
		warnOnDocumentsPastARequest(kept, this.requestLimits, diagnostics);
		return new OperationCatalog(List.copyOf(kept), diagnostics.problems(), diagnostics.warnings());
	}

	// Warns about each tool that is estimated above LARGE_TOOL_TOKENS tokens over the
	// exposure type that sends the most for it.
	//
	// The warning is raised with the catalog's other warnings, so the check an
	// application runs in its build carries it and startup logs it on the path that logs
	// the rest.
	//
	// It is raised once for each tool, after the clashes are settled. A generated tool
	// that yielded its name is left unmentioned, and a file both exposure types reach
	// earns one warning, because it is one file with one remedy.
	//
	// The two exposure types send different amounts for one tool, so the number is the
	// one of the exposure type that sends the most among those that publish the tool.
	// That is MCP wherever the MCP server serves the tool, because tools/list carries
	// everything a model provider is sent in process, and the title, the output schema
	// and the adapter's additions beside it. A tool that fits there fits in process as
	// well, so one warning about the larger number covers both. The message names the
	// exposure type, and the startup listing of that exposure type prints the same
	// number for the tool, because both read ToolOperation.estimatedTokens with the
	// same addition. The listing of the other exposure type prints a smaller number for
	// the same file, which the name in the message accounts for.
	//
	// The remedy follows what the number holds. An output schema grows with the
	// selection set, so where the number counts one the message says how much of it the
	// schema is, and names the selection set and the switch that leaves the schema out.
	private static void warnOnLargeTools(List<ToolOperation> tools, List<SchemaAddition> schemaAdditions,
			OperationDiagnostics diagnostics) {
		for (ToolOperation tool : tools) {
			ToolExposureType costliest = null;
			int tokens = 0;
			for (ToolExposureType toolExposureType : ToolExposureType.values()) {
				if (!tool.toolExposureTypes().contains(toolExposureType)) {
					continue;
				}
				int estimate = tool.estimatedTokens(toolExposureType,
						SchemaAddition.charactersFor(toolExposureType, schemaAdditions));
				if (costliest == null || estimate > tokens) {
					costliest = toolExposureType;
					tokens = estimate;
				}
			}
			if (costliest == null || tokens <= LARGE_TOOL_TOKENS) {
				continue;
			}
			int outputSchemaTokens = tool.estimatedOutputSchemaTokens(costliest,
					SchemaAddition.charactersFor(costliest, schemaAdditions));
			String remedy = (outputSchemaTokens == 0) ? "Narrow the variables it declares, or split the operation."
					: "Its output schema is about " + outputSchemaTokens + " of them. Select fewer fields, narrow "
							+ "the variables it declares, or split the operation, and @gatool(outputSchema: false) "
							+ "leaves the output schema out of this tool.";
			diagnostics.warning(tool.location(),
					"becomes the tool " + tool.toolName() + " and costs about " + tokens + " tokens as an "
							+ costliest.label() + " tool, which is a large share of a model's context "
							+ "before any call. " + remedy);
		}
	}

	// Warns about each tool whose document passes a limit the application expects its API
	// to apply to a request.
	//
	// An operation file is read however long it is, because the operator wrote it. The
	// API that receives its document reads a request under limits of its own, and a
	// document above them is refused there on every call. The GraphQL specification keeps
	// those limits out of introspection, so GATool cannot ask the API for them. The
	// application states them under gatool.api.request-limits, and until it does they are
	// the defaults of graphql-java, which TrustedText applies to the document as such an
	// API would.
	//
	// The tool is served either way. The limit is what the application expects, the API
	// is the one that decides, and a warning that stopped startup would stop it over a
	// number that may be out of date.
	//
	// The document is the printed one, fragments of shared files included, because that
	// is the text the API receives and counts.
	private static void warnOnDocumentsPastARequest(List<ToolOperation> tools, RequestLimits limits,
			OperationDiagnostics diagnostics) {
		for (ToolOperation tool : tools) {
			List<TrustedText.PassedLimit> passed = TrustedText.findRequestLimitsPassed(tool.printedDocument(), limits);
			if (passed.isEmpty()) {
				continue;
			}
			String fragmentFiles = tool.sharedFragmentFiles().isEmpty() ? ""
					: " The document holds the shared fragments of " + String.join(", ", tool.sharedFragmentFiles())
							+ ".";
			diagnostics.warning(tool.location(), "becomes the tool " + tool.toolName() + " and sends a document of "
					+ joinPhrases(passed.stream().map(TrustedText.PassedLimit::size).toList()) + ", which passes "
					+ joinPhrases(passed.stream().map(TrustedText.PassedLimit::property).toList())
					+ ". The API applies limits of its own to each request and refuses a call whose document "
					+ "passes them. GATool cannot read those limits from the API, so " + RequestLimits.PROPERTY
					+ " holds what you expect of it, with the defaults of graphql-java until you set them."
					+ fragmentFiles + " Select less or split the operation, or raise the property where your API "
					+ "reads more.");
		}
	}

	private static String joinPhrases(List<String> phrases) {
		if (phrases.size() == 1) {
			return phrases.getFirst();
		}
		return String.join(", ", phrases.subList(0, phrases.size() - 1)) + " and " + phrases.getLast();
	}

	// Adds the tool that one operation file becomes, or reports why it left.
	//
	// A generated operation reports as a warning and leaves, where a written operation
	// file reports as a problem and stops startup. The difference is what the reader can
	// do about it: a written file can be fixed, and the message for a generated one would
	// name a location nobody can open, while taking down every healthy tool beside it.
	// Three failures of this kind are known: a union whose members give one response name
	// two shapes, a root field whose name is longer than a tool name may be, and a
	// document that outgrows graphql-java's parser limit.
	private void addTool(OperationFile file, ScalarSchemas scalarSchemas, ScalarSchemas resultScalarSchemas,
			List<ToolOperation> tools, Map<String, List<String>> toolNamesByUnmappedScalar,
			OperationDiagnostics diagnostics) {
		boolean generated = RootFieldOperations.isGenerated(file.source().location());
		OperationDiagnostics own = generated ? new OperationDiagnostics() : diagnostics;
		ValidatedOperation operation = this.validator.validate(file, own);
		if (operation == null) {
			if (generated) {
				diagnostics.warning(file.source().location(),
						"is left out, because the operation GATool wrote "
								+ "for this root field does not validate against the schema. Write an operation file "
								+ "for it, selecting the fields you want. " + describeFirstProblem(own));
			}
			return;
		}
		if (!generated) {
			this.deprecatedSelections.warnAbout(operation, diagnostics);
		}
		String description = this.descriptions.describe(operation, own);
		String toolName = resolveToolName(operation, own);
		if (toolName == null && generated) {
			diagnostics.warning(file.source().location(), "is left out, because its root field does not take a "
					+ "tool name GATool can publish. Write an operation file for it, with @gatool(name:) to give "
					+ "it one. " + describeFirstProblem(own));
		}
		if (generated) {
			// The warnings a generated operation earns are worth reading, and its
			// problems are the reason it left.
			own.warnings().forEach((warning) -> diagnostics.warning(warning.location(), warning.message()));
		}
		if (toolName == null) {
			return;
		}
		// The schema is written once here, so both adapters read finished JSON and
		// graphql-java stays inside gatool-core.
		InputSchema inputSchema = InputSchemaWriter.write(this.schema, operation.file(), scalarSchemas);
		for (String scalarName : inputSchema.unmappedScalars()) {
			toolNamesByUnmappedScalar.computeIfAbsent(scalarName, (name) -> new ArrayList<>()).add(toolName);
		}
		warnOnWhatTheInputSchemaLeftOut(operation, inputSchema, diagnostics);
		tools.add(ToolOperation.builder()
			.toolName(toolName)
			.operationName(operation.operationName())
			.operationType(operation.operationType())
			.description(description)
			.title(operation.file().directive().title())
			.printedDocument(operation.printedDocument())
			.inputSchema(inputSchema.json())
			.location(operation.location())
			.toolExposureTypes(file.source().toolExposureTypes())
			.paginationVariables(DeclaredVariables.findPaginationVariables(operation))
			.variablesWithDefaults(DeclaredVariables.findVariablesWithDefaults(this.schema, operation))
			.variableTypes(DeclaredVariables.findVariableTypes(this.schema, operation))
			.openWorld(operation.file().directive().openWorld())
			.outputSchema(writeOutputSchema(operation, resultScalarSchemas, diagnostics))
			.sharedFragmentFiles(operation.file().sharedFragmentFiles())
			.scopes(operation.file().directive().scopes())
			.build());
	}

	// Warns about each default and each input type the input schema of one operation left
	// out, with the reason, so the team knows what a model reading the schema cannot see.
	private static void warnOnWhatTheInputSchemaLeftOut(ValidatedOperation operation, InputSchema inputSchema,
			OperationDiagnostics diagnostics) {
		// Named per operation instead of gathered like the unmapped scalars, because the
		// file that declares the default is the file to change.
		for (String refusedDefault : inputSchema.refusedDefaults()) {
			diagnostics.warning(operation.location(),
					"declares " + refusedDefault + ", and that value fails the schema "
							+ "GATool publishes for the argument, so the default is left out. A model reading it and "
							+ "sending it back would be refused before the call reached the API. The two disagree when "
							+ "the scalar's JSON type comes from its @specifiedBy page or from "
							+ ScalarSchemas.PROPERTY
							+ ".<Name> while the default is written in the API's own terms, so "
							+ "check both against what the API really takes.");
		}
		// Named separately from the variables above, because this default is written in
		// the API's own schema instead of in the operation file, so the two warnings
		// send a team to different places.
		for (String refusedFieldDefault : inputSchema.refusedFieldDefaults()) {
			diagnostics.warning(operation.location(), "reaches the input field " + refusedFieldDefault
					+ ", a default the API's schema declares, and that value fails the schema GATool publishes "
					+ "for the field, so the default is left out. A model reading it and sending it back would "
					+ "be refused before the call reached the API. The two disagree when the scalar's JSON type "
					+ "comes from its @specifiedBy page or from " + ScalarSchemas.PROPERTY + ".<Name> while the "
					+ "default is written in the API's own terms, so check both against what the API really "
					+ "takes.");
		}
		// Named per operation like the refused defaults above, because the operation file
		// is what a team changes: a narrower variable type, or an argument the API offers
		// that names fewer tables.
		for (String cutType : inputSchema.cutTypes()) {
			diagnostics.warning(operation.location(), "publishes an input schema that stops short at " + cutType
					+ ", because the input types of this API reach each other in enough ways that writing them "
					+ "all out would pass what a model reads and what this process can hold. The schema names "
					+ "the type and stops there, so a model can still send that field by reading the API's own "
					+ "schema. A filter graph of the shape Hasura and PostGraphile publish is the usual reason.");
		}
	}

	// Returns the JSON text of the output schema this tool publishes, or null where the
	// tool does not publish one.
	//
	// The directive decides per file whether a tool publishes an output schema, and the
	// application's default answers where the file stays quiet.
	private @Nullable String writeOutputSchema(ValidatedOperation operation, ScalarSchemas resultScalarSchemas,
			OperationDiagnostics diagnostics) {
		Boolean declared = operation.file().directive().outputSchema();
		boolean publishes = (declared != null) ? declared : this.outputSchemaByDefault;
		if (!publishes) {
			return null;
		}
		OutputSchema written = OutputSchemaWriter.write(this.schema, operation.file(), resultScalarSchemas);
		// The branches name the members either way, and without __typename a model
		// reading a response cannot tell which branch it holds.
		for (String position : written.positionsWithoutTypename()) {
			diagnostics.warning(operation.location(),
					"selects the union or interface at '" + position
							+ "' without __typename, and the output schema names the members it describes by "
							+ "that field, so a model reading a result cannot tell them apart; select __typename "
							+ "beside the fragments");
		}
		// A position past the writer's shape budget is published as an open object, so a
		// result there still conforms; the warning says where the schema stops
		// describing.
		for (String position : written.cutPositions()) {
			diagnostics.warning(operation.location(), "publishes an output schema that stops short at '" + position
					+ "', because the operation selects more object shapes than the " + OutputSchemaWriter.SHAPE_BUDGET
					+ " one schema may hold, so that position is published as an open object and a result there "
					+ "still conforms. Name fewer members or less depth at that position.");
		}
		return written.json();
	}

	private static String describeFirstProblem(OperationDiagnostics diagnostics) {
		return diagnostics.problems()
			.stream()
			.findFirst()
			.map((problem) -> "The schema said: " + problem.message())
			.orElse("");
	}

	// One parsed operation file with the exposure types its source serves. A file that
	// holds fragments alone goes to the second list, for SharedFragments to read.
	private List<OperationFile> parseOperationFiles(List<OperationSource> sources, List<FragmentFile> fragmentFiles,
			OperationDiagnostics diagnostics) {
		List<OperationFile> files = new ArrayList<>();
		for (OperationSource source : sources) {
			// A generated document that will not even parse reports as a warning and
			// leaves, for the reason a validation failure does: taking down every healthy
			// tool for it is the wrong answer.
			boolean generated = RootFieldOperations.isGenerated(source.location());
			OperationDiagnostics own = generated ? new OperationDiagnostics() : diagnostics;
			ParsedFile parsed = this.parser.parse(source, own);
			if (generated && parsed == null) {
				diagnostics.warning(source.location(), "is left out, because the operation GATool wrote "
						+ "for this root field could not be read back: " + describeFirstProblem(own)
						+ " A smaller gatool.dev.experimental.generated-selection-depth, or an operation file for this "
						+ "root field, gives a document that fits.");
				continue;
			}
			if (parsed instanceof FragmentFile fragmentFile) {
				fragmentFiles.add(fragmentFile);
				continue;
			}
			if (generated) {
				own.problems().forEach((problem) -> diagnostics.warning(problem.location(), problem.message()));
			}
			// A file that failed to parse reported its own problem, so it leaves quietly.
			if (parsed instanceof OperationFile file) {
				files.add(file);
			}
		}
		return files;
	}

	// Returns the parsed operations GATool writes for every root field the operation
	// files leave uncovered.
	//
	// These serve both exposure types, because the switch belongs to the application.
	private List<OperationFile> generateMissingRootFields(List<OperationFile> written,
			OperationDiagnostics diagnostics) {
		if (!this.generation.enabled()) {
			return List.of();
		}
		// Coverage is per root and per exposure type. Per root because a schema can
		// declare movie on Query and on Mutation. Per exposure type because an operation
		// file under the in-process locations does not say anything about what MCP
		// serves.
		Map<ToolExposureType, Set<String>> coveredRootFieldsByExposureType = new LinkedHashMap<>();
		for (ToolExposureType toolExposureType : ToolExposureType.values()) {
			coveredRootFieldsByExposureType.put(toolExposureType,
					written.stream()
						.filter((file) -> file.source().toolExposureTypes().contains(toolExposureType))
						.flatMap((file) -> {
							String keyword = (file.operationType() == OperationType.MUTATION) ? "mutation" : "query";
							return rootFieldsOf(file).stream().map((name) -> keyword + "/" + name);
						})
						.collect(Collectors.toCollection(LinkedHashSet::new)));
		}
		// The generator is told which root fields every side covers, so it leaves them
		// out, and it leaves out the warnings about them. A root field one side covers is
		// still written, for the other side, and the narrowing below decides which side
		// publishes it.
		Set<String> coveredOnEverySide = coveredRootFieldsByExposureType.values()
			.stream()
			.reduce((oneSide, otherSide) -> {
				Set<String> both = new LinkedHashSet<>(oneSide);
				both.retainAll(otherSide);
				return both;
			})
			.orElse(Set.of());
		List<OperationSource> sources = new ArrayList<>();
		for (OperationSource source : RootFieldOperations.write(this.schema, this.generation, coveredOnEverySide,
				diagnostics)) {
			String rootField = RootFieldOperations.rootFieldOf(source.location());
			Set<ToolExposureType> uncovered = Arrays.stream(ToolExposureType.values())
				.filter((toolExposureType) -> !coveredRootFieldsByExposureType.getOrDefault(toolExposureType, Set.of())
					.contains(rootField))
				.collect(Collectors.toCollection(LinkedHashSet::new));
			if (!uncovered.isEmpty()) {
				sources.add(source.withToolExposureTypes(uncovered));
			}
		}
		// A generated operation holds one operation without a spread, so the fragment
		// list it could fill stays a throwaway.
		return parseOperationFiles(sources, new ArrayList<>(), diagnostics);
	}

	// Returns the names of the root fields one operation selects.
	//
	// The generator leaves these root fields alone. A fragment spread at the top level is
	// read through, since an operation file may spread one there.
	private static Set<String> rootFieldsOf(OperationFile file) {
		Map<String, FragmentDefinition> fragments = new LinkedHashMap<>();
		file.document()
			.getDefinitionsOfType(FragmentDefinition.class)
			.forEach((fragment) -> fragments.put(fragment.getName(), fragment));
		Set<String> rootFieldNames = new LinkedHashSet<>();
		collectRootFields(file.operation().getSelectionSet(), fragments, new LinkedHashSet<>(), rootFieldNames);
		return rootFieldNames;
	}

	private static void collectRootFields(@Nullable SelectionSet selectionSet,
			Map<String, FragmentDefinition> fragments, Set<String> followedSpreads, Set<String> rootFieldNames) {
		if (selectionSet == null) {
			return;
		}
		for (Selection<?> selection : selectionSet.getSelections()) {
			switch (selection) {
				case Field field -> rootFieldNames.add(field.getName());
				case InlineFragment inline ->
					collectRootFields(inline.getSelectionSet(), fragments, followedSpreads, rootFieldNames);
				case FragmentSpread spread -> {
					FragmentDefinition fragment = fragments.get(spread.getName());
					if (fragment != null && followedSpreads.add(spread.getName())) {
						collectRootFields(fragment.getSelectionSet(), fragments, followedSpreads, rootFieldNames);
					}
				}
				default -> {
					// Field, InlineFragment and FragmentSpread are every selection a
					// document holds, and Selection is not sealed, so javac asks for an
					// empty arm.
				}
			}
		}
	}

	// Returns the name this operation publishes its tool under. Where the name fails the
	// portability check, this reports the problem and returns null.
	private @Nullable String resolveToolName(ValidatedOperation operation, OperationDiagnostics diagnostics) {
		// One file can name its own tool with @gatool(name:), and that name wins
		// over the strategy. It faces the same checks below, because every tool name
		// reaches the same clients.
		String explicitToolName = operation.file().directive().toolName();
		if (explicitToolName != null) {
			return portable(explicitToolName, operation, diagnostics);
		}
		// IllegalArgumentException is the strategy's documented way to refuse a name, and
		// its message is the sentence the strategy wrote for the reader. Anything else a
		// strategy throws, and a null it returns, is a fault in the strategy, so the
		// problem names the class beside the file.
		@Nullable String toolName;
		try {
			toolName = this.namingStrategy.toolName(operation.operationName());
		}
		catch (IllegalArgumentException ex) {
			diagnostics.problem(operation.location(), "cannot be named as a tool: " + ex.getMessage());
			return null;
		}
		catch (RuntimeException ex) {
			diagnostics.problem(operation.location(),
					"cannot be named as a tool, because the naming strategy " + this.namingStrategy.getClass().getName()
							+ " failed on the operation name '" + operation.operationName() + "': " + ex);
			return null;
		}
		if (toolName == null) {
			diagnostics.problem(operation.location(),
					"cannot be named as a tool, because the naming strategy " + this.namingStrategy.getClass().getName()
							+ " returned null for the operation name '" + operation.operationName()
							+ "', and a strategy returns a name or throws " + "IllegalArgumentException");
			return null;
		}
		return portable(toolName, operation, diagnostics);
	}

	private static @Nullable String portable(String toolName, ValidatedOperation operation,
			OperationDiagnostics diagnostics) {
		String problem = ToolNameRules.problemWith(toolName);
		if (problem != null) {
			diagnostics.problem(operation.location(), "gets the tool name '" + toolName + "' for operation "
					+ operation.operationName() + ", and a tool name " + problem);
			return null;
		}
		return toolName;
	}

	// Adds one warning naming every custom scalar GATool describes to the model as any
	// JSON value.
	//
	// One warning lists every unmapped scalar with the tools that use it, so one line
	// at startup carries the whole list.
	private static void reportUnmappedScalars(Map<String, List<String>> toolNamesByUnmappedScalar,
			OperationDiagnostics diagnostics) {
		if (toolNamesByUnmappedScalar.isEmpty()) {
			return;
		}
		String scalarListing = toolNamesByUnmappedScalar.entrySet()
			.stream()
			.map((scalar) -> scalar.getKey() + " (used by " + String.join(", ", scalar.getValue()) + ")")
			.collect(Collectors.joining(", "));
		diagnostics.warning(OperationWarning.THE_SCHEMA,
				"declares custom scalars that GATool describes to the model " + "as any JSON value: " + scalarListing
						+ ". The GraphQL API's own coercion decides whether a value is "
						+ "valid, and it answers a wrong one with a request error the model can read. Set "
						+ ScalarSchemas.PROPERTY + ".<Name> to describe one, so the model reads the format before it "
						+ "calls.");
	}

	/**
	 * Whether to write an operation for every root field the operation files leave
	 * uncovered, and how far to look.
	 *
	 * @param enabled whether the generator runs at all, which is off by default
	 * @param includeMutations whether the mutation root joins the query root, since
	 * generating a write tool for every mutation is a wider step than generating a read
	 * tool for every query
	 * @param includeDeprecatedRootFields whether a root field the schema marks deprecated
	 * becomes a tool, which carries the deprecation in its description
	 * @param includeDeprecatedFields whether a deprecated field joins a generated
	 * selection set
	 * @param depth how many object levels a generated selection set expands, bounded by
	 * {@link RootFieldOperations#MAX_DEPTH}
	 */
	public record Generation(boolean enabled, boolean includeMutations, boolean includeDeprecatedRootFields,
			boolean includeDeprecatedFields, int depth) {

		/**
		 * Returns the default setting. Every component beside {@code enabled} is moot
		 * while it is false, and each one holds the value that leaves the most out, so a
		 * reader of this line sees a generator that stays off.
		 * @return a setting that leaves the generator off
		 */
		public static Generation off() {
			return new Generation(false, false, false, false, 1);
		}
	}

	/**
	 * Collects the settings of an {@link OperationCatalogFactory}.
	 *
	 * <p>
	 * A builder names each setting where it is set.
	 */
	public static final class Builder {

		private final GraphQLSchema schema;

		private final ToolNamingStrategy namingStrategy;

		private boolean outputSchemaByDefault;

		private Map<String, ?> scalarSchemas = Map.of();

		private Map<String, ?> resultScalarSchemas = Map.of();

		private RequestLimits requestLimits = RequestLimits.defaults();

		private Generation generation = Generation.off();

		private List<SchemaAddition> schemaAdditions = List.of();

		private Builder(GraphQLSchema schema, ToolNamingStrategy namingStrategy) {
			this.schema = schema;
			this.namingStrategy = namingStrategy;
		}

		/**
		 * Sets whether a tool publishes an output schema where its file leaves the choice
		 * out.
		 * @param outputSchemaByDefault the value of
		 * {@code gatool.results.publish-output-schema}
		 * @return this builder
		 */
		public Builder outputSchemaByDefault(boolean outputSchemaByDefault) {
			this.outputSchemaByDefault = outputSchemaByDefault;
			return this;
		}

		/**
		 * Sets the JSON Schema configured for each custom scalar an argument takes.
		 * @param scalarSchemas the schemas by scalar name, which are copied in the order
		 * given
		 * @return this builder
		 */
		public Builder scalarSchemas(Map<String, ?> scalarSchemas) {
			this.scalarSchemas = Collections.unmodifiableMap(new LinkedHashMap<>(scalarSchemas));
			return this;
		}

		/**
		 * Sets the JSON Schema configured for each custom scalar a result holds.
		 * @param resultScalarSchemas the schemas by scalar name, which are copied in the
		 * order given
		 * @return this builder
		 */
		public Builder resultScalarSchemas(Map<String, ?> resultScalarSchemas) {
			this.resultScalarSchemas = Collections.unmodifiableMap(new LinkedHashMap<>(resultScalarSchemas));
			return this;
		}

		/**
		 * Sets the limits the API is expected to put on a document.
		 * @param requestLimits the limits startup compares each document with
		 * @return this builder
		 */
		public Builder requestLimits(RequestLimits requestLimits) {
			this.requestLimits = requestLimits;
			return this;
		}

		/**
		 * Sets whether the factory generates a tool for each root field, and how.
		 * @param generation the generation settings
		 * @return this builder
		 */
		public Builder generation(Generation generation) {
			this.generation = generation;
			return this;
		}

		/**
		 * Sets what each exposure type's adapter adds to a schema it publishes, which the
		 * token estimates count.
		 * @param schemaAdditions the additions, one for each adapter
		 * @return this builder
		 */
		public Builder schemaAdditions(List<SchemaAddition> schemaAdditions) {
			this.schemaAdditions = List.copyOf(schemaAdditions);
			return this;
		}

		/**
		 * Builds the factory.
		 * @return the factory
		 */
		public OperationCatalogFactory build() {
			return new OperationCatalogFactory(this);
		}

	}

}
