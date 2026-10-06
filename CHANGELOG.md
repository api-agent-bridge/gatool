# Changelog

## Unreleased

### Tool contract changes

### Java API changes

### Properties

### Fixes

## 0.1.0

This is the first release of GATool. The README section
[What this release does](README.md#what-this-release-does) covers what it
provides.

### Tool contract changes

- A mutation whose answer passes `gatool.api.max-response-size` or
  `gatool.results.max-characters` now tells the model that the API
  answered and the write may have been applied, and asks it to read the
  current state before it writes again. The advice to ask for fewer
  fields or a smaller page used to follow a mutation as well, and it
  stays for queries.
- The default server instructions describe a result as "a GraphQL
  response: the data entry holds the result of the executed operation, and
  the errors array holds any errors the API reported". They used to speak
  of a data member and an errors member.
- The descriptions of the three dynamic tools changed. `introspectType`
  opens with "Returns the details of one type that searchSchema returned",
  and asks for a call "when a search result names a type you need to look
  inside", where it asked for one on every type an operation touches.
  `executeGraphql` opens with "Executes a GraphQL document against the
  API". It ends with the order of the tools, "Always call searchSchema
  first and executeGraphql after that", with when to call introspectType
  between them, and with "Do not try to guess a valid operation."
  `searchSchema` says that a field below the root comes with the path
  that reaches it from a root field. The server instructions of the
  dynamic mode follow: they ask the model to read "any type the search
  result names that you need to look inside", where they asked it to read
  every type it touches.
- Startup now stops where an MCP server bean was built without immediate
  execution, because GATool reads who is calling from the request thread.
- At call time, GATool now refuses any MCP tool call that arrives on a
  thread other than the one serving the HTTP request, and records the
  outcome as `off-request-thread`. A call refused for a missing scope
  records `insufficient-scope`.
- The same refusal now covers a per-caller credential read under a
  security context strategy that a pooled thread inherits, such as Spring
  Security's inheritable thread-local strategy.
- For a 408 or a 425 answer to a mutation, the model now reads a shorter
  sentence: read the current state before writing again. A 5xx or a 429
  still tells the model the first call may already have applied.
- A schema GATool fetches from a registry can still stop a restart if it
  drifts from the operation files; the build check run against the
  registry's schema is the place to catch that drift first.
- The new README section "Compatibility" states what a patch release
  keeps and what a minor release may change, until GATool reaches 1.0.
- Planned for the release after 0.1.0: carrying the caller inside the MCP
  transport context, so the thread a call runs on stops deciding who the
  caller is.

### Java API changes

- `GATool` is a class built through `GATool.builder()`, where it was a
  record with a constructor of nine parameters. Its accessors keep their
  names, `toBuilder()` copies a tool, and a tool that leaves `readOnly`
  unset counts as one that writes.
- The groups `gatool.api`, `gatool.mcp` and `gatool.dev` are bound to the
  classes `GAToolApiProperties`, `GAToolMcpProperties` and
  `GAToolDevProperties`, where they were nested classes of
  `GAToolProperties`. `GAToolProperties.getApi()` and its siblings return
  the new types, and every property name, type and default is unchanged.
- `CheckedTool` is a class that the check creates and a caller reads,
  where it was a record with a public constructor of twelve parameters.
  Its accessors keep their names.
- `CheckSettings` is built from `CheckSettings.defaults()` and its
  `with...` methods, or through its canonical constructor. The two shorter
  constructors, of three and four parameters, are removed.
- `ToolGeneration.DYNAMIC_TWO_STEP` is removed, so setting
  `gatool.dev.experimental.generate-tools` to `dynamic-two-step` now fails
  Spring Boot's own property binding instead of GATool's message.
- `ReachableTypes`, `TypeDetail`, `SchemaPaths`, `SdlDescriptions`, and
  `SdlWriter` moved from the public package `io.gatool.core.search` into
  the internal package `io.gatool.core.internal.search`, leaving
  `SchemaSearch`, `SearchHit`, `CorpusEntry`, `CorpusFormat`, and
  `SchemaCorpus` as the package's public types.
- `CheckedTool` gained five values: `title`, `outputSchema`, `readOnly`,
  `openWorld`, and `scopes`.
- The overloads of `OperationFileCheck.check` and
  `OperationFilesAssert.assertValid` that assumed default `CheckSettings`
  are gone. Every caller now passes a `CheckSettings` explicitly.
- `GAToolCatalog.schema()` and its builder method `Builder.schema(...)`
  are gone; GATool built every tool without reading the value a caller
  supplied there.
- The bean methods `gaToolGraphQlExecutor`, `gaToolGraphQlResultWriter`,
  `gaToolCallRunner`, `gaToolNamingStrategy`, `gaToolCatalog`,
  `gaToolRateLimiter`, `mcpServerJsonMapper`, and `gaToolCallbacks` are
  now package-private. An application replaces the behaviour each one
  builds by publishing its own bean of the same published type.
- The seven classes GATool registers through `spring.factories` are now
  package-private, `OperationFileProblemsException` is now `final` with a
  package-private constructor, and `SpringAiMcpKeys` moved into an
  internal package.
- `CONTRIBUTING.md` and the Javadoc of `GraphQlExecutionRequest` now
  state the rule for a public record that grows: it keeps the constructor
  of the release before it compiling for at least one minor release.

### Properties

- `gatool.dev.experimental.dynamic-operations.search-schema-description`,
  `.introspect-type-description` and `.execute-graphql-description` each
  replace the whole description of one dynamic tool. A tool whose
  property is unset or blank keeps the text GATool writes.
- `gatool.dev.experimental.dynamic-operations.search-token-budget` is
  2000 by default, where it was 500, so one `searchSchema` call returns
  more of the ranked fields and a field with a long description fits.
- `gatool.mcp.sessions.max-count-per-caller` now defaults to 100 instead
  of off. A value at or below 0 still turns the cap off, and startup
  warns when a secured stateful server runs without an effective cap.
- `gatool.api.max-response-size` now defaults to 1MB instead of 10MB, and
  the new property `gatool.api.schema.max-size`, 10MB by default, bounds
  the schema GATool fetches from a registry URL.
- Where an application leaves `spring.http.clients.read-timeout` unset,
  the read deadline GATool supplies drops from 20 to 15 seconds, so the
  model reads GATool's own timeout sentence before the MCP client's
  20-second default gives up.
- The MCP JSON mapper now reads a decimal argument as a `BigDecimal`, so
  an MCP tool call carries every digit the model sent, the way an
  in-process call already does.

### Fixes

- A servlet application that sets `spring.ai.mcp.server.stdio=true` now
  serves MCP over stdio alone. GATool's HTTP transport used to register
  there as well, so the server answered on `/mcp` without authentication
  while the log said it served stdio. With Spring Security on the
  classpath, the same application now starts without an issuer, under
  Spring Boot's default chain.
- A tool call that failed while an `ObservationHandler` threw from
  `onError` is no longer run a second time. GATool took the empty answer
  for a handler that had failed before the call began and called the tool
  again, so a mutation that timed out could reach the API twice inside one
  `tools/call`. The model now also keeps the sentence of the failure.
- A request without a token no longer opens an HTTP session on either
  contributed security chain. A POST to the metadata path and any request
  the default chain refused each left a session behind, so an anonymous
  caller could fill the session store. On the default chain in its bearer
  shape, an anonymous POST now answers `401` where it answered `403`.
- Under `spring.main.lazy-initialization=true`, an application on the
  in-process starter alone now checks its operation files at startup. It
  used to start with a broken file and fail on the first chat request.
- Startup now stops where `spring.ai.mcp.server.capabilities.tool` is
  `false` while operation files make MCP tools. Spring AI registers tools
  only while the capability is on, so the server started without them
  while the log listed them as served.
- An application's own closeable `SchemaSearch` bean is now closed once at
  shutdown. GATool registered a second disposal for it beside Spring's.
- An application that depends on `gatool-spring-boot` or
  `gatool-mcp-spring-boot` directly, without `spring-boot-http-client`,
  `spring-ai-model` or `micrometer-core`, no longer fails with a
  `NoClassDefFoundError` while Spring Boot reads the auto-configuration.
  It reaches the startup message that names the jar to add.
- Startup now stops where `spring.ai.mcp.server.protocol`,
  `spring.ai.mcp.server.type` or `spring.ai.mcp.server.stdio` carries
  whitespace around its value, as a `.properties` file keeps it. Such a
  value passed GATool's checks and missed Spring's conditions, so the
  application started without a server.
- An application that publishes its own `GAToolCatalog` bean now starts
  without `gatool.api.url`. GATool's executor used to be built beside such
  a catalog and stopped startup asking for a URL that went uncalled.
- A stateful server with a client listening on `GET /mcp` now stops at
  once. The open stream used to hold Spring Boot's graceful shutdown for
  the whole shutdown phase, 30 seconds by default, on every stop.
- The startup line that names the request factory builder now says
  "the ClientHttpRequestFactoryBuilder bean of this application" only for
  a bean the application declares. Spring Boot declares that bean itself
  in every application that is not reactive, so the line said it at every
  start.
- The startup listing of a stdio server leaves
  `gatool.mcp.security.baseline-scopes` out of each tool's scopes. The
  stdio check reads a tool's own scopes alone, and the listing used to
  name the baseline beside them.
- The startup stop for a `gatool.mcp.security.resource` under a scheme
  other than `http` and `https` now says that the audiences property
  takes the `aud` claim as the issuer writes it, which Microsoft Entra ID
  fills with the client ID alone in a v2.0 token. It used to send the
  App ID URI `api://<client-id>` there.
- Messages about `gatool.dev.experimental.generate-tools` write its
  values as the property takes them, `dynamic-three-step` and `none`.
- The README and the Javadoc of `RequestCaller` name
  `server.tomcat.remoteip.internal-proxies` for a deployment behind a
  proxy, where they named `trusted-proxies`. The default of
  `internal-proxies` covers every private address range, and
  `trusted-proxies` adds to it.
- Startup now warns when the running application uses a Spring Boot or a
  Spring AI release outside the lines this release tested, Spring Boot
  4.1.x and Spring AI 2.0.x from 2.0.1, naming the tested lines and the
  versions found.
- The build check now counts the 98 characters the MCP adapter adds to
  each tool's JSON Schema `$id`, so a tool near the token warning
  threshold gets the same warning from the check that startup gives.
- A failed stdio start beside a stderr pipe a host leaves unread now
  exits within a bound: GATool writes the startup failure report on a
  daemon thread and waits at most two seconds for it.
- The stdio section of the README now states which writers to stderr
  wait when a host leaves the pipe unread, and that a failed start's
  report is given up after two seconds beside a full pipe.
- A stdio server now runs each tool call on the thread that reads stdin,
  so two answers cannot reach the MCP SDK's one outbound queue at once.
  The SDK drops an answer emitted beside another and closes the
  transport, which left a server silent during a run of back-to-back
  calls.
