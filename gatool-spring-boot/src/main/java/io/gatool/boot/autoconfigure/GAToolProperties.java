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

package io.gatool.boot.autoconfigure;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * The properties GATool reads, under the {@code gatool} prefix.
 *
 * <p>
 * Each numeric property that needs a positive value is checked in the bean method that
 * consumes it, which is how the auto-configuration reports a value it cannot use. The
 * four where a value below 1 switches a bound off are read as they are: the search token
 * budget, and the depth, field and alias limits of the dynamic layer. Bean Validation
 * would need an implementation on the classpath, and a starter that carries
 * {@code @Validated} without one accepts a rate limit of zero and fails on the first tool
 * call instead of at startup.
 *
 * @author Željko Kozina
 */
@ConfigurationProperties(prefix = "gatool")
public class GAToolProperties {

	// What the account name drops before it names the cache folder.
	private static final Pattern OUTSIDE_A_FOLDER_NAME = Pattern.compile("[^A-Za-z0-9._-]");

	@NestedConfigurationProperty
	private final GAToolApiProperties api = new GAToolApiProperties();

	@NestedConfigurationProperty
	private final GAToolMcpProperties mcp = new GAToolMcpProperties();

	private final InProcess inProcess = new InProcess();

	private final Naming naming = new Naming();

	private final Results results = new Results();

	private final Observations observations = new Observations();

	private final Inputs inputs = new Inputs();

	@NestedConfigurationProperty
	private final GAToolDevProperties dev = new GAToolDevProperties();

	/**
	 * Creates the properties with the default of each one. Spring Boot's binder then sets
	 * the ones the environment carries.
	 */
	public GAToolProperties() {
		// Each property keeps the default its field declares.
	}

	/**
	 * Returns the group of properties under {@code gatool.api}.
	 * @return the group
	 */
	public GAToolApiProperties getApi() {
		return this.api;
	}

	/**
	 * Returns the group of properties under {@code gatool.mcp}.
	 * @return the group
	 */
	public GAToolMcpProperties getMcp() {
		return this.mcp;
	}

	/**
	 * Returns the group of properties under {@code gatool.in-process}.
	 * @return the group
	 */
	public InProcess getInProcess() {
		return this.inProcess;
	}

	/**
	 * Returns the group of properties under {@code gatool.naming}.
	 * @return the group
	 */
	public Naming getNaming() {
		return this.naming;
	}

	/**
	 * Returns the group of properties under {@code gatool.results}.
	 * @return the group
	 */
	public Results getResults() {
		return this.results;
	}

	/**
	 * Returns the group of properties under {@code gatool.observations}.
	 * @return the group
	 */
	public Observations getObservations() {
		return this.observations;
	}

	/**
	 * Returns the group of properties under {@code gatool.inputs}.
	 * @return the group
	 */
	public Inputs getInputs() {
		return this.inputs;
	}

	/**
	 * Returns the group of properties under {@code gatool.dev}.
	 * @return the group
	 */
	public GAToolDevProperties getDev() {
		return this.dev;
	}

	/**
	 * Returns the default for a cache directory: a folder under the JVM's temporary
	 * directory, inside a folder that carries the name of the account the JVM runs as.
	 * @param name the name of the cache folder
	 * @return the path of that folder under the account's folder in the temporary
	 * directory
	 */
	// Boot's own on-disk defaults live under java.io.tmpdir. A folder under the working
	// directory would be written into the repository of every application and refused in
	// a read-only container. The JVM sets the property at startup, and the fallback keeps
	// the null checker satisfied while pointing at the working directory.
	static String underTemporaryDirectory(String name) {
		String temporaryDirectory = System.getProperty("java.io.tmpdir");
		return underTemporaryDirectory(Objects.requireNonNullElse(temporaryDirectory, "."),
				System.getProperty("user.name"), name);
	}

	/**
	 * Returns the default for a cache directory under the temporary directory and for the
	 * account given.
	 * @param temporaryDirectory the temporary directory of the JVM
	 * @param account the name of the account the JVM runs as, or {@code null} where the
	 * JVM does not report one
	 * @param name the name of the cache folder
	 * @return the path of that folder under the account's folder in the temporary
	 * directory
	 */
	// The temporary directory of a Linux host is shared by every account on it. One
	// folder named gatool would belong to whichever account made it first, so a second
	// account would find a folder it cannot write to, or one somebody else can write to.
	// A folder per account gives each its own, and CacheDirectories checks the folder
	// before a cache uses it, since another account can still make a folder of that name
	// first.
	//
	// The name is the account's as the JVM reports it, with every character outside
	// letters, digits, the full stop, the hyphen and the underscore replaced, so a
	// name that holds a space or a path separator stays one path element. A JVM
	// whose user id lacks an entry in the password file reports "?", which a
	// container started with an arbitrary user id does, and the folder is then
	// gatool-_. The name only picks the folder. Who owns it is read from the file
	// system.
	static String underTemporaryDirectory(String temporaryDirectory, @Nullable String account, String name) {
		String accountName = (account == null || account.isBlank()) ? "unknown"
				: OUTSIDE_A_FOLDER_NAME.matcher(account).replaceAll("_");
		return Path.of(temporaryDirectory, "gatool-" + accountName, name).toString();
	}

	/**
	 * What GATool sends to the GraphQL API as a tool call's variables.
	 */
	public static class Inputs {

		/**
		 * Creates the group under {@code gatool.inputs} with the default of each
		 * property.
		 */
		public Inputs() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Whether a null argument reaches the GraphQL API. When false, a null for a
		 * variable that declares a default is dropped.
		 */
		private boolean sendExplicitNulls;

		/**
		 * Returns whether a null argument reaches the GraphQL API.
		 * @return the value of {@code gatool.inputs.send-explicit-nulls}
		 */
		public boolean isSendExplicitNulls() {
			return this.sendExplicitNulls;
		}

		/**
		 * Sets whether a null argument reaches the GraphQL API.
		 * @param sendExplicitNulls the value of {@code gatool.inputs.send-explicit-nulls}
		 */
		public void setSendExplicitNulls(boolean sendExplicitNulls) {
			this.sendExplicitNulls = sendExplicitNulls;
		}

		/**
		 * JSON Schema fragment to publish for a custom scalar, under the scalar name.
		 */
		private final Map<String, Map<String, Object>> scalarSchemas = new LinkedHashMap<>();

		/**
		 * Returns the JSON Schema fragment to publish for a custom scalar, under the
		 * scalar name.
		 * @return the value of {@code gatool.inputs.scalar-schemas}
		 */
		public Map<String, Map<String, Object>> getScalarSchemas() {
			return this.scalarSchemas;
		}

	}

	/**
	 * What the observation of each tool call records.
	 */
	public static class Observations {

		/**
		 * Creates the group under {@code gatool.observations} with the default of each
		 * property.
		 */
		public Observations() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Whether the arguments and the result join each tool call's observation.
		 */
		private boolean includeContent;

		/**
		 * Returns whether the arguments and the result join each tool call's observation.
		 * @return the value of {@code gatool.observations.include-content}
		 */
		public boolean isIncludeContent() {
			return this.includeContent;
		}

		/**
		 * Sets whether the arguments and the result join each tool call's observation.
		 * @param includeContent the value of {@code gatool.observations.include-content}
		 */
		public void setIncludeContent(boolean includeContent) {
			this.includeContent = includeContent;
		}

	}

	/**
	 * What a tool call returns.
	 */
	public static class Results {

		/**
		 * Creates the group under {@code gatool.results} with the default of each
		 * property.
		 */
		public Results() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Largest result a tool returns, in characters.
		 */
		private int maxCharacters = 60000;

		/**
		 * Whether every tool publishes an output schema and returns structured content.
		 */
		private boolean publishOutputSchema;

		/**
		 * Returns the largest result a tool returns, in characters.
		 * @return the value of {@code gatool.results.max-characters}
		 */
		public int getMaxCharacters() {
			return this.maxCharacters;
		}

		/**
		 * Sets the largest result a tool returns, in characters.
		 * @param maxCharacters the value of {@code gatool.results.max-characters}
		 */
		public void setMaxCharacters(int maxCharacters) {
			this.maxCharacters = maxCharacters;
		}

		/**
		 * Returns whether every tool publishes an output schema and returns structured
		 * content.
		 * @return the value of {@code gatool.results.publish-output-schema}
		 */
		public boolean isPublishOutputSchema() {
			return this.publishOutputSchema;
		}

		/**
		 * Sets whether every tool publishes an output schema and returns structured
		 * content.
		 * @param publishOutputSchema the value of
		 * {@code gatool.results.publish-output-schema}
		 */
		public void setPublishOutputSchema(boolean publishOutputSchema) {
			this.publishOutputSchema = publishOutputSchema;
		}

		/**
		 * Whether a response that holds both data and errors counts as a success. By
		 * default it is a tool error, so the model reads that something failed. Set it
		 * for an API whose errors describe fields that are missing on purpose. A response
		 * without data is an error either way.
		 */
		private boolean partialResultsAsSuccess;

		/**
		 * Returns whether a response that holds both data and errors counts as a success.
		 * @return the value of {@code gatool.results.partial-results-as-success}
		 */
		public boolean isPartialResultsAsSuccess() {
			return this.partialResultsAsSuccess;
		}

		/**
		 * Sets whether a response that holds both data and errors counts as a success.
		 * @param partialResultsAsSuccess the value of
		 * {@code gatool.results.partial-results-as-success}
		 */
		public void setPartialResultsAsSuccess(boolean partialResultsAsSuccess) {
			this.partialResultsAsSuccess = partialResultsAsSuccess;
		}

		/**
		 * JSON Schema fragment the output schema publishes for a custom scalar, under the
		 * scalar name. It overrides what the output schema would reuse from
		 * gatool.inputs.scalar-schemas, for a scalar that returns another form than it
		 * accepts.
		 */
		private final Map<String, Map<String, Object>> scalarSchemas = new LinkedHashMap<>();

		/**
		 * Returns the JSON Schema fragment the output schema publishes for a custom
		 * scalar, under the scalar name.
		 * @return the value of {@code gatool.results.scalar-schemas}
		 */
		public Map<String, Map<String, Object>> getScalarSchemas() {
			return this.scalarSchemas;
		}

	}

	/**
	 * How GATool builds a tool name from a GraphQL operation name.
	 */
	public static class Naming {

		/**
		 * Creates the group under {@code gatool.naming} with the default of each
		 * property.
		 */
		public Naming() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Built-in strategy that names every tool. A ToolNamingStrategy bean replaces it.
		 * A tool name is part of the contract agents call, so changing it breaks them.
		 */
		private Naming.NamingStrategy strategy = Naming.NamingStrategy.CAMEL_CASE;

		/**
		 * Returns the built-in strategy that names every tool.
		 * @return the value of {@code gatool.naming.strategy}
		 */
		public Naming.NamingStrategy getStrategy() {
			return this.strategy;
		}

		/**
		 * Sets the built-in strategy that names every tool.
		 * @param strategy the value of {@code gatool.naming.strategy}
		 */
		public void setStrategy(Naming.NamingStrategy strategy) {
			this.strategy = strategy;
		}

		/**
		 * The built-in naming strategies.
		 */
		public enum NamingStrategy {

			/**
			 * Converts to camelCase, so {@code TopRatedMovies} becomes
			 * {@code topRatedMovies}. This is the default, because it changes the fewest
			 * GraphQL names and matches the method name default of Spring AI and
			 * LangChain4j.
			 */
			CAMEL_CASE,

			/**
			 * Converts to snake_case with Jackson 3's translation, so
			 * {@code TopRatedMovies} becomes {@code top_rated_movies}.
			 */
			SNAKE_CASE,

			/**
			 * Keeps the operation name as the file writes it.
			 */
			AS_WRITTEN

		}

	}

	/**
	 * The in-process tools, which a Spring AI ChatClient calls.
	 */
	public static class InProcess {

		/**
		 * Creates the group under {@code gatool.in-process} with the default of each
		 * property.
		 */
		public InProcess() {
			// Each property keeps the default its field declares.
		}

		private final Operations operations = Operations.at("optional:classpath*:gatool/in-process/");

		/**
		 * Returns the group of properties under {@code gatool.in-process.operations}.
		 * @return the group
		 */
		public Operations getOperations() {
			return this.operations;
		}

	}

	/**
	 * The operation files of one exposure type.
	 */
	public static class Operations {

		/**
		 * Creates the group under {@code gatool.mcp.operations} or
		 * {@code gatool.in-process.operations} with an empty list of folders. Each side
		 * starts from a default of its own, {@code optional:classpath*:gatool/mcp/} or
		 * {@code optional:classpath*:gatool/in-process/}.
		 */
		public Operations() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Folders that hold the operation files of this side.
		 *
		 * <p>
		 * Each side sets its own default through {@link #at(String)}, which is why the
		 * field starts empty here and the two defaults reach an IDE through
		 * {@code additional-spring-configuration-metadata.json}.
		 *
		 * <p>
		 * Spring Boot replaces a list property instead of adding to it, so an application
		 * naming a second folder loses the default one and every tool in it. List the
		 * default beside the new folder to keep both.
		 */
		private List<String> locations = List.of();

		static Operations at(String location) {
			Operations operations = new Operations();
			operations.locations = List.of(location);
			return operations;
		}

		/**
		 * Returns the folders that hold the operation files of this side.
		 * @return the value of {@code gatool.mcp.operations.locations} or
		 * {@code gatool.in-process.operations.locations}
		 */
		public List<String> getLocations() {
			return this.locations;
		}

		/**
		 * Sets the folders that hold the operation files of this side.
		 * @param locations the value of {@code gatool.mcp.operations.locations} or
		 * {@code gatool.in-process.operations.locations}
		 */
		public void setLocations(List<String> locations) {
			this.locations = locations;
		}

	}

}
