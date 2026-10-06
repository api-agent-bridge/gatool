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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.GraphQLSchema;
import graphql.validation.ValidationError;
import graphql.validation.ValidationErrorType;
import org.jspecify.annotations.Nullable;

/**
 * The fragments that shared fragment files hold, and how an operation file borrows them.
 *
 * <p>
 * A fragment defined inside an operation file belongs to that file, so two operation
 * files may each define their own {@code MovieCard}. A spread resolves to the file's own
 * fragment first, and to a shared fragment file on the same side second. The shared
 * fragments an operation reaches are appended to its document in the order of their first
 * spread, depth first, so the assembled text stays the same between builds.
 *
 * <p>
 * Two shared fragment files on one side that define the same name stop startup, and the
 * problem names both files. An operation file whose own fragment shadows a shared one
 * keeps its own, with a warning that names both files. A shared fragment that every
 * operation file leaves unspread earns a warning with its file and is validated on its
 * own, since the operations that would have validated it are missing, and a spread
 * without a definition in either place is left for validation, which reports the
 * undefined fragment.
 *
 * @author Željko Kozina
 */
final class SharedFragments {

	private final Map<ToolExposureType, Map<String, Shared>> fragmentsByExposureType = new EnumMap<>(
			ToolExposureType.class);

	// Every shared fragment, keyed "location name", taken out when an operation attaches
	// it.
	private final Map<String, Shared> unused = new LinkedHashMap<>();

	private SharedFragments() {
	}

	/**
	 * Reads the shared fragment files, reporting two files on one side that define the
	 * same name.
	 * @param files every file that holds fragments alone
	 * @param diagnostics receives a problem for each clash
	 * @return the fragments, ready to attach
	 */
	static SharedFragments of(List<FragmentFile> files, OperationDiagnostics diagnostics) {
		SharedFragments sharedFragments = new SharedFragments();
		// A pair of files that share both sides clashes once, so the pair is remembered.
		Set<String> reported = new LinkedHashSet<>();
		for (FragmentFile file : files) {
			String location = file.source().location();
			for (FragmentDefinition fragment : file.document().getDefinitionsOfType(FragmentDefinition.class)) {
				Shared shared = new Shared(location, fragment);
				for (ToolExposureType toolExposureType : file.source().toolExposureTypes()) {
					sharedFragments.add(toolExposureType, shared, reported, diagnostics);
				}
			}
		}
		return sharedFragments;
	}

	// Adds the fragment to one side, or reports the clash with the one that side already
	// holds under the name.
	private void add(ToolExposureType toolExposureType, Shared shared, Set<String> reported,
			OperationDiagnostics diagnostics) {
		String name = shared.fragment().getName();
		String location = shared.location();
		Map<String, Shared> side = this.fragmentsByExposureType.computeIfAbsent(toolExposureType,
				(key) -> new LinkedHashMap<>());
		Shared existing = side.get(name);
		if (existing == null) {
			side.put(name, shared);
			this.unused.putIfAbsent(location + " " + name, shared);
		}
		else if (reported.add(name + " " + existing.location() + " " + location)) {
			String definedWhere = existing.location().equals(location) ? "twice"
					: "and " + existing.location() + " defines it too";
			diagnostics.problem(location,
					"defines the fragment " + name + " " + definedWhere + " among the " + toolExposureType.label()
							+ " tools' fragment files, and a spread has to find one definition; keep one, or "
							+ "rename one of them");
		}
	}

	/**
	 * Returns the operation file with the shared fragments its spreads reach appended to
	 * its document, in the order of their first spread, depth first.
	 * @param file the parsed operation file
	 * @param diagnostics receives a warning for each own fragment that shadows a shared
	 * one
	 * @return the same file where every fragment it spreads is its own, or a copy whose
	 * document holds them and whose {@link OperationFile#sharedFragmentFiles()} names
	 * their files
	 */
	OperationFile attachTo(OperationFile file, OperationDiagnostics diagnostics) {
		Map<String, FragmentDefinition> ownFragments = new LinkedHashMap<>();
		file.document()
			.getDefinitionsOfType(FragmentDefinition.class)
			.forEach((fragment) -> ownFragments.put(fragment.getName(), fragment));
		Map<String, String> conflictByName = new LinkedHashMap<>();
		Map<String, Shared> visible = visibleTo(file, conflictByName);
		for (String name : ownFragments.keySet()) {
			Shared shadowed = visible.get(name);
			if (shadowed != null) {
				diagnostics.warning(file.source().location(), "defines the fragment " + name + ", which "
						+ shadowed.location() + " also defines; the file keeps its own");
			}
		}
		Map<String, Shared> attached = new LinkedHashMap<>();
		Set<String> unresolved = new LinkedHashSet<>();
		collect(file.operation().getSelectionSet(), ownFragments, visible, new LinkedHashSet<>(), attached, unresolved);
		// A name the two sides define differently is a problem for the file that spreads
		// it, and for that file alone: a both-sided file that spreads something else
		// keeps starting.
		for (String name : unresolved) {
			String conflict = conflictByName.get(name);
			if (conflict != null) {
				diagnostics.problem(file.source().location(), conflict);
			}
		}
		if (attached.isEmpty()) {
			return file;
		}
		List<String> attachedFileLocations = new ArrayList<>();
		attached.forEach((name, sharedFragment) -> {
			this.unused.remove(sharedFragment.location() + " " + name);
			if (!attachedFileLocations.contains(sharedFragment.location())) {
				attachedFileLocations.add(sharedFragment.location());
			}
		});
		// The shared fragments go behind the file's own definitions one at a time, the
		// way ToolDirective rebuilds a document, because graphql-java's definition list
		// is a raw type and a list of this method's own would fail -Werror.
		Document assembled = file.document().transform((builder) -> {
			builder.definitions(List.of());
			file.document().getDefinitions().forEach(builder::definition);
			attached.values().forEach((sharedFragment) -> builder.definition(sharedFragment.fragment()));
		});
		return new OperationFile(file.source(), assembled, file.operation(), file.directive(), attachedFileLocations);
	}

	/**
	 * Reports every shared fragment that stayed unused once every operation file has
	 * attached what it spreads: a warning naming it, and a problem where it fails
	 * validation against the schema.
	 * @param schema the schema to validate against
	 * @param diagnostics receives one warning per unused fragment, and a problem for each
	 * one that fails validation
	 */
	// A fragment is validated inside the operation that spreads it, so a fragment left
	// unspread would skip validation, and a wrong field in it would pass startup and fail
	// at the first spread, in a later change. It is validated here on its own, with the
	// rules graphql-java runs on a top-level fragment: the rules that read an operation's
	// variables are skipped by graphql-java for a fragment reached without a spread, and
	// the unused-fragment error is dropped, because unused is the case being checked.
	void checkUnused(GraphQLSchema schema, OperationDiagnostics diagnostics) {
		this.unused.values().forEach((shared) -> {
			String name = shared.fragment().getName();
			diagnostics.warning(shared.location(),
					"holds the fragment " + name + ", which zero operation files spread");
			List<ValidationError> errors = validateAlone(schema, shared);
			if (!errors.isEmpty()) {
				diagnostics.problem(shared.location(),
						"holds the fragment " + name + ", which fails validation against the schema:"
								+ OperationValidator.describeErrors(errors, shared.location()));
			}
		});
	}

	// Validates one shared fragment on its own and returns the errors inside it.
	//
	// The fragment keeps its own nodes, whose locations name its file. Every other shared
	// fragment on a side that holds this one joins the document, so a spread resolves the
	// way it does for an operation on that side. Each of them joins re-parsed without a
	// file name, so an error inside one of them arrives without a file and is left out
	// here: a spread one is reported through its operation, and an unspread one on its
	// own turn.
	private List<ValidationError> validateAlone(GraphQLSchema schema, Shared shared) {
		Document.Builder document = Document.newDocument().definition(shared.fragment());
		Set<String> added = new LinkedHashSet<>();
		added.add(shared.fragment().getName());
		this.fragmentsByExposureType.values().forEach((side) -> {
			Shared onThisSide = side.get(shared.fragment().getName());
			if (onThisSide == null || !onThisSide.location().equals(shared.location())) {
				return;
			}
			side.values().forEach((other) -> {
				if (added.add(other.fragment().getName())) {
					document.definition(withoutFileName(other.fragment()));
				}
			});
		});
		return OperationValidator.validate(schema, document.build())
			.stream()
			.filter((error) -> error.getValidationErrorType() != ValidationErrorType.UnusedFragment)
			.filter((error) -> error.getLocations()
				.stream()
				.anyMatch((location) -> shared.location().equals(location.getSourceName())))
			.toList();
	}

	private static FragmentDefinition withoutFileName(FragmentDefinition fragment) {
		return OperationFileParser.parseDocument(AstPrinter.printAst(fragment))
			.getDefinitionsOfType(FragmentDefinition.class)
			.getFirst();
	}

	// Returns the shared fragments the sides of this file can see, by name, and fills the
	// second map with the problem text for each name the two sides define differently.
	//
	// A file that both sides publish sees both sides' fragment files. Where the two
	// sides hold different definitions for one name, the file cannot take one of them,
	// so the name is left out, and the caller reports the problem only where the file
	// spreads that name.
	private Map<String, Shared> visibleTo(OperationFile file, Map<String, String> conflictByName) {
		Map<String, Shared> visible = new LinkedHashMap<>();
		for (ToolExposureType toolExposureType : file.source().toolExposureTypes()) {
			this.fragmentsByExposureType.getOrDefault(toolExposureType, Map.of()).forEach((name, sharedFragment) -> {
				Shared definedWhere = visible.putIfAbsent(name, sharedFragment);
				if (definedWhere != null && !definedWhere.location().equals(sharedFragment.location())) {
					conflictByName.putIfAbsent(name,
							"is published by both exposure types, and the fragment " + name + " is defined by "
									+ definedWhere.location() + " on one side and by " + sharedFragment.location()
									+ " on the other; keep one of the two");
				}
			});
		}
		conflictByName.keySet().forEach(visible::remove);
		return visible;
	}

	// Depth first: a shared fragment is appended when its spread is first met, and its
	// own spreads are followed before the next sibling, so a fragment's dependencies
	// sit right behind it. A name already followed is skipped, which also ends a cycle,
	// and validation reports the cycle itself.
	private static void collect(@Nullable SelectionSet selectionSet, Map<String, FragmentDefinition> ownFragments,
			Map<String, Shared> visible, Set<String> followed, Map<String, Shared> attached, Set<String> unresolved) {
		if (selectionSet == null) {
			return;
		}
		for (Selection<?> selection : selectionSet.getSelections()) {
			switch (selection) {
				case Field field ->
					collect(field.getSelectionSet(), ownFragments, visible, followed, attached, unresolved);
				case InlineFragment inline ->
					collect(inline.getSelectionSet(), ownFragments, visible, followed, attached, unresolved);
				case FragmentSpread spread -> follow(spread, ownFragments, visible, followed, attached, unresolved);
				default -> {
					// Field, InlineFragment and FragmentSpread are every selection a
					// document holds, and Selection is not sealed, so javac asks for a
					// default arm, and this one stays empty.
				}
			}
		}
	}

	// A spread names the file's own fragment or a shared one; a shared one is attached,
	// and
	// a name neither side defines is reported once validation runs.
	private static void follow(FragmentSpread spread, Map<String, FragmentDefinition> ownFragments,
			Map<String, Shared> visible, Set<String> followed, Map<String, Shared> attached, Set<String> unresolved) {
		String name = spread.getName();
		if (!followed.add(name)) {
			return;
		}
		FragmentDefinition fragment = ownFragments.get(name);
		if (fragment == null) {
			Shared sharedFragment = visible.get(name);
			if (sharedFragment == null) {
				unresolved.add(name);
				return;
			}
			attached.put(name, sharedFragment);
			fragment = sharedFragment.fragment();
		}
		collect(fragment.getSelectionSet(), ownFragments, visible, followed, attached, unresolved);
	}

	// One shared fragment, with the file that holds it.
	private record Shared(String location, FragmentDefinition fragment) {
	}

}
