# GATool

Spring Boot starters that turn the trusted GraphQL documents in your repository
into tools an agent can call, over the Model Context Protocol (MCP) or in
process through Spring AI.

## What it does

Write a GraphQL query in a file, and GATool publishes it as a tool:

```graphql
# Returns the highest-rated movies, best first.
query TopRatedMovies($first: Int = 10) {
  topRatedMovies(first: $first) {
    id
    title
    rating
  }
}
```

The operation name becomes the tool name. The comment above it becomes the
description an agent reads. The variables become the tool's input schema. An
agent calls the tool with arguments. GATool sends the document to your GraphQL
API. The API answers with JSON. GATool reads the `data` and `errors` of that
answer, writes them again, and returns them as the tool result. `extensions`
and every other top-level key stay out. A number reaches the model with every
digit the API sent.

The operations are trusted documents. The repository holds the list of queries
that exist. A pull request reviews each one. The model chooses from that list.
The schema stays out of the model's context. The API receives only the
documents in the repository. Each document arrives as graphql-java prints it,
with its comments removed. `CheckedTool.document()` in the CI check returns the
text GATool sends. The CI check is the `OperationFilesAssert` test, described
under "Checking your tools in CI". A later release will send persisted
documents, which the API holds ahead of time and a client names by hash. The
documentation for that release will say what to hash.

## Getting started

Add the starter for the Model Context Protocol (MCP):

```xml
<dependency>
    <groupId>io.gatool</groupId>
    <artifactId>gatool-mcp-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Put the query above in `src/main/resources/gatool/mcp/TopRatedMovies.graphql`,
and point the application at your API:

```yaml
gatool:
  api:
    url: https://movies.example.com/graphql
    schema:
      location: classpath:movies.graphqls
  mcp:
    security:
      unsafe:
        allow-mcp-calls-without-authentication: true
```

GATool validates every operation against the schema file at startup. A query
that names a field missing from the schema stops startup. The message names the
file and the field. Once the CI check is in place, it fails the build for the
same reason. The CI check is the `OperationFilesAssert` test described under
"Checking your tools in CI". A test that starts the context fails the same way.

GATool builds the schema with graphql-java. graphql-java refuses one schema
shape that the GraphQL specification allows. In that shape, a type gives an
argument a different default from the one its interface declares. An example is
`memberOf(limit: Int! = 150)` under an interface that declares
`limit: Int! = 100`. Startup stops and names each such argument.
[graphql-java#4480](https://github.com/graphql-java/graphql-java/issues/4480)
asks graphql-java to accept such a schema. Until a release does, start the
application with a copy of the schema that gives those arguments the default of
their interface. GATool then describes the interface default to the model,
while the API applies its own.

The YAML above turns on
`gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication`, the
unauthenticated switch. Without Spring Security on the classpath, the
unauthenticated switch is required, and startup stops while it is off. The
application logs a warning at every startup while the switch is on. Keep the
endpoint on a loopback address with `server.address`, or behind a gateway that
authenticates for it. The section on securing the endpoint below shows the
other way. Add the resource server starter, name the issuer, and leave the
unauthenticated switch off.

Start the application. A client that connects to `/mcp` reads one tool. The
entry is shortened here. The one on the wire also carries a `title`, the
`$schema` and the `$id` of the input schema, and the hints `destructiveHint`
and `idempotentHint`.

```json
{
  "name": "topRatedMovies",
  "description": "Returns the highest-rated movies, best first.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "first": {
        "anyOf": [{ "type": "integer" }, { "type": "null" }],
        "description": "How many movies to return.",
        "default": 10
      }
    },
    "required": [],
    "additionalProperties": false
  },
  "annotations": { "readOnlyHint": true }
}
```

## In-process tools for Spring AI

The second starter hands the same operation files to a `ChatClient`, without a
Model Context Protocol (MCP) server:

```xml
<dependency>
    <groupId>io.gatool</groupId>
    <artifactId>gatool-in-process-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
@Bean
ChatClient assistant(ChatClient.Builder builder, GAToolCallbacks tools) {
    return builder.defaultTools(tools.toolCallbackProvider()).build();
}
```

`ChatClient.Builder` comes from a Spring AI chat model starter, such as
`spring-ai-starter-model-anthropic`. The application adds that starter beside
this one.

Files under `gatool/in-process/`, the in-process folder, reach the `ChatClient`.
Files under `gatool/mcp/`, the MCP folder, reach the MCP server. One
application can therefore serve both with different lists.

An in-process tool runs inside the application. Three things stay out of its
path: the scopes an operation file lists, the rate limiter, and Spring AI's
`ToolContext`. The tool takes its arguments from the model's text alone. The
caller is the application itself. Under `token-exchange` and under
`gatool.api.credentials.unsafe.forward-client-tokens`, the forwarded token, the
caller is the user on the thread that calls the tool. Both strategies read the
token from that thread's security context. Under a per-caller credential,
GATool refuses a call where the thread's security context may belong to another
caller. [The caller and the request
thread](#the-caller-and-the-request-thread) describes that refusal.

The arguments travel to the API as the model wrote them. An argument the input
schema does not name is forwarded as sent. An argument the model left out stays
out. A misspelt name therefore gets the API's own answer for the unnamed
argument: its default, or the API's own error. The MCP path refuses such a call
before the API sees it. A later minor release may validate arguments before the
call, as the MCP path does.

A scope list on an in-process file states what the application has to enforce
itself. The application enforces it with method security on the resolver, or in
the code that builds the `ChatClient`. The startup listing, the list of tools
GATool prints at INFO when the application starts, says so beside the tool.
Spring AI's own `spring.ai.tool` observation covers each call. A refusal
reaches the model as a plain sentence of text. One example is a credential the
configured strategy could not supply. An answer arrives as the same GraphQL
envelope the MCP path returns.

## How a tool is built

- **One file makes one tool.** An operation file is a `.graphql` file that
  holds one GraphQL operation and the fragments it spreads. A file that holds a
  second operation stops startup. A tool call returns one result. A file that
  holds a subscription, or that uses `@defer` or `@stream`, therefore stops
  startup as well.

- **The name** comes from the operation name through a naming strategy. A
  naming strategy is the rule that turns an operation name into a tool name,
  and `camel-case` is the default. `@gatool(name: "movies_top_rated")` sets an
  explicit name. A `ToolNamingStrategy` bean replaces the strategy. An
  operation written without a name takes its name from the file, so
  `top-rated-movies.graphql` and `TopRatedMovies.graphql` both give
  `topRatedMovies`. Under the `as-written` strategy, the file's own case is
  kept and a run of separators becomes one underscore, so the first file gives
  `top_rated_movies`. GATool refuses an operation without a name when the file
  name holds a letter outside ASCII, such as `über-filme.graphql`. The refusal
  happens on every platform. It does not depend on whether the file system
  hands the name over composed or decomposed. Composed means one code point
  for `ü`, and decomposed means `u` plus a combining mark. Name the operation
  in the file, and the file keeps
  its name. An explicit name follows the same rules as a name from a strategy.
  It uses letters, digits, `_` and `-`, with at most 64 characters. It holds
  at least one letter or digit.

- **The description** comes from the comment directly above the operation.
  Without one, it comes from the schema description of the root field the
  operation selects. Startup warns when both are silent. A comment or a schema
  description made of characters that render empty, such as a no-break space,
  counts as silent. The schema may mark that root field `@deprecated`. The
  description then ends with the deprecation and the schema's reason, as in
  `Lists every movie. Deprecated: Use movies.`. The model reads that the API is
  removing the field the tool calls. The sentence is added after a comment and
  after a schema description alike. A generated tool, one GATool writes from a
  root field under `gatool.dev.experimental.generate-tools`, carries the same
  sentence. A deprecated field deeper in the selection only reaches the startup
  warning. The same holds for a deprecated root field when the operation
  selects several root fields. The September 2025 edition of GraphQL allows a
  description string ahead of the operation. GATool refuses that syntax at
  startup, because graphql-java 25.0 predates it. The refusal says so and
  points at the `#` comment lines.

- **The input schema** is the JSON Schema that describes the arguments of a
  tool. It comes from the variables, with their types, their descriptions and
  their defaults. An `ID` argument accepts a string or an integer, which is
  what GraphQL's own input coercion takes. An `ID` in a result is a string,
  which is what a conforming service serializes. A default is published as the
  JSON Schema `default` keyword, coerced the way GraphQL coerces it. The model
  therefore reads what it gets by leaving the argument out. A whole number
  keeps every digit the file wrote. A `Long` default past 64 bits therefore
  publishes as those digits. A default that fails the schema of its own
  property is left out, and startup names it. `@oneOf` marks an input object
  where exactly one field is set. A nullable variable may fill a `@oneOf`
  input field through a nullable argument or input field. GATool refuses that
  variable, even when it has a default. Declare it non-null. Section 5.8.5 of
  the September 2025 specification allows that default. graphql-js 17 refuses
  it in that position. GATool follows graphql-js.

- **The title** comes from `@gatool(title: "Top rated movies")`. A blank title
  is refused. A blank title is one that is empty, or one made of spaces.
  Characters that render empty, such as a no-break space or a zero width
  space, count as spaces here.

- **The open world hint** comes from `@gatool(openWorld: true)` or
  `@gatool(openWorld: false)`. It sets `openWorldHint` on the tool definition
  of the Model Context Protocol (MCP). The hint says whether the tool reaches
  an open-ended set of entities. A file that leaves it out publishes the tool
  without the hint.

- **The result** is the `data` and `errors` of the GraphQL response as JSON
  text. GATool writes that text itself. The response-level `extensions` and
  every other top-level key stay out. An error's own `extensions` travel with
  the error. A `294 Partial Success`, or any 2xx status under a JSON content
  type, is read the same way as a `200`. A GraphQL error becomes a tool error
  that the model can read and act on.

- **The hints** `readOnlyHint`, `destructiveHint` and `idempotentHint` are set
  on every tool. A query publishes `readOnlyHint` and `idempotentHint` as true
  and `destructiveHint` as false. A mutation publishes the opposite three
  values. A mutation file becomes a tool the same way a query file does. The
  operation type tells a client which it is.

- **The output schema** is the JSON Schema that describes the result of a
  tool. It is off by default. `gatool.results.publish-output-schema: true`
  turns it on for every tool. `@gatool(outputSchema: true)` or
  `@gatool(outputSchema: false)` overrides that for one operation either way.
  An application can therefore publish for the tools it trusts and hold back
  for one awkward operation.

Every `@gatool` problem names the line and column of the argument it is about.
An empty or comment-only operation file is reported as empty. An operation
file, a shared fragment file and the schema file may end each line with `\n`,
`\r\n` or a lone `\r`. GATool reads the three forms the same way. A block
string, a GraphQL string written between triple quotes, therefore holds one
value on a Windows checkout and on a Linux one. A tool publishes one contract
on both. The three files are the operator's own text, so GATool reads them at
any length. "The size of a document" below says which limits remain and which
the API sets.
`gatool-core/src/main/resources/io/gatool/core/gatool-directives.graphqls`
holds the `@gatool` directive in SDL, the GraphQL schema definition language.
It exists for an editor's completion and for a schema linter. GATool reads the
directive from the operation file itself. The SDL file serves editors alone.

### What the input schema checks

The MCP SDK validates the arguments of a call against the published input
schema before the tool runs. The GraphQL API then coerces the values that reach
it. The table lists the positions where the two answer differently for one
value:

| Position | What the schema does | What the API does |
| --- | --- | --- |
| A Non-Null custom scalar published as `{}` | Accepts `null`. The empty schema leaves every type open, `null` included. The variable stays in `required`, so a call that leaves it out is refused | Refuses the `null` with a request error. The model reads it as the tool error |
| A list, such as `[Int!]!` | Refuses the single value `1999`, because the property says `array` | Takes `1999` as `[1999]`, which is GraphQL's input coercion for a list. The model sends the list, because the schema is what it reads |
| An input type inside another of its own kind, and one past the node budget | Accepts a field the type lacks. The object there is published with a description and without its fields | Refuses the field with a request error. The model reads it as the tool error |

A custom scalar is a scalar type that the schema defines itself. It is
published as `{}` until a scalar fragment, or a specification GATool has read,
describes it. A scalar fragment is the piece of JSON Schema configured under
`gatool.inputs.scalar-schemas` for one scalar. A scalar fragment that names a
`type` closes the position, so the schema states a type there. The Non-Null
variable then publishes that type without a null branch. "Custom scalars" below
shows how to write a scalar fragment. The third row covers two cases. The first
is a type that holds another of its own kind, such as a filter with `and` and
`or`. That type lists its fields and closes the object where it first appears.
The copy nested inside it is published open. The second is a position past the
node budget. One variable expands at most 500 input objects. A position past
that budget is published open as well, with a startup warning that names it. An
in-process tool sends its arguments without this validation, so the API answers
for every row there.

A variable typed as a generated filter costs more than its one line in the
operation suggests. A generated filter is the kind of input type that Hasura
and PostGraphile publish. One `where` variable over eight tables with three
relationships publishes an input schema of about 100,000 characters.
`tools/list` carries that schema to every client. Startup warns about a tool
above 4,000 estimated tokens and names it. To avoid this, declare the scalar
variables the tool needs and write the filter as a literal in the operation.
The budget of 500 input objects and the text published past it may change in a
later release.

The schemas GATool writes are built for function calling without OpenAI's
strict mode. Two positions fail OpenAI's rules for a tool under `strict: true`.
The first is an optional property, because strict mode requires every property
in `required`. The second is a custom scalar published as `{}`, because strict
mode requires a `type` on every property. A scalar fragment under
`gatool.inputs.scalar-schemas` closes the second position. The first stays open
for a later release.

### What the output schema promises

In MCP, an output schema is optional. Once a server publishes one, it is
binding: every call must return structured content that conforms to it.
Structured content is the JSON result an MCP tool returns beside its text.
GATool's output schema describes the GraphQL response envelope, the object that
holds `data` and `errors`. GATool reads the structured content back from the
text it wrote. The two halves therefore carry the same digits and nulls. That
holds in embedded mode, where the GraphQL API runs inside the same application
through Spring for GraphQL. It holds for a remote API as well. Where the
selection set fails to prove a shape, the schema stays permissive:

| Case | What the schema says |
| --- | --- |
| `data` | Nullable, because a failed call answers with `data: null` |
| Any selected field | Present in `required`, and its value nullable |
| A field under `@skip` or `@include` | Out of `required`, since the model decides whether it arrives |
| A field inside a fragment with a type condition | Out of `required`, since another concrete type leaves it out. A condition that names the surrounding type, an interface it implements or a union holding it keeps its fields in `required`. Such a condition holds for every object that can answer there |
| An enum | `string`, without the value list. A value the API adds before the schema file catches up still conforms |
| A union or an interface | One branch per member the operation's type conditions cover, plus an open branch and `null` |
| A custom scalar | The `type` and the `format` of its scalar fragment under `gatool.inputs.scalar-schemas`, or its entry under `gatool.results.scalar-schemas` as written. A scalar without an entry in either map may cite a specification through its `@specifiedBy` URL. Where GATool has read that specification, the schema publishes its `type` and `format`. Otherwise the schema is empty and accepts any JSON value |
| `errors` | The GraphQL shape: an array of objects whose `message`, `locations`, `path` and `extensions` may each be null or left out. Then `null`. Then an open branch that accepts any JSON value. An API, or a gateway in front of it, may write other shapes, such as an entry without `message`, an entry that is a plain string, or one object where the list belongs. Each of them conforms |

A response that carries errors beside its data conforms as well, so it arrives
with both halves. The tool error flag says the response carries errors. The
structured content says what came back with them. Errors in a shape other than
the GraphQL one count as errors for that flag. The text and the structured
content carry them as the API wrote them. GATool writes some refusals itself,
such as a result above one of the two response caps,
`gatool.api.max-response-size` and `gatool.results.max-characters`. Such a
refusal carries a sentence in place of the envelope and goes back as text
alone.

Structured content reaches an MCP client alone. Spring AI's in-process tools
carry text, so the output schema does not change what a `ChatClient` receives.

### Unions, and the errors-as-data pattern

The errors-as-data pattern puts an expected failure, such as a declined card,
into the result type. The failure is a union member beside the success type. A
union or interface position publishes one branch per member its type conditions
cover. `__typename` is the field that names the concrete type of an object in
the response. When the operation selects it, each branch pins its value. This
operation:

```graphql
query Pay($cardId: ID!) {
  pay(cardId: $cardId) {
    __typename
    ... on Payment { id amountCents }
    ... on InsufficientFunds { message shortfallCents }
    ... on CardDeclined { message code }
  }
}
```

publishes a `Payment` branch keyed `{"const": "Payment"}`, one branch for each
error member, then an open `{"type": "object"}` branch and `{"type": "null"}`.
Both error members carry `message`. The pinned `__typename` lets the model tell
them apart, and it is what the model switches on.

With the open branch present, the position only enforces "an object, or null".
The member branches exist for the model to read. The open branch keeps the
schema safe. The API may add a member after the operation file was written. A
result with that member lands in the open branch. Without the open branch, the
call would fail on an operation file that had not changed.

An operation that leaves `__typename` out gets the same branches without the
`const`. Startup warns, because the model then cannot tell from the response
which member it holds. GATool only removes its own directive from a document.
It does not add `__typename` to your query. A type condition may name the
position's own type, or an interface that type declares. Such a condition adds
its fields to the one shape every member shares. It does not get a branch of
its own. A fragment on `Node` at a `node` position therefore publishes one
shape, and startup stays quiet. One output schema holds at most 500 object
shapes. A position past that bound is published as an open object. A result
there still conforms, and startup names the position.

### Custom scalars

A custom scalar reaches the model as `{}`, the JSON Schema that accepts any
value. The model then guesses the format and learns it from the API's request
error. Two things narrow that: a `@specifiedBy` URL that GATool has read, and a
scalar fragment you write.

**A `@specifiedBy` URL GATool has read.** `@specifiedBy` is the directive a
schema puts on a scalar to point at the specification of its values. GATool has
read every specification the community registry publishes, 31 in all. It has
also read RFC 4122 and RFC 3986, which the GraphQL specification uses in its own
examples. Nine of them carry a JSON Schema `format`. The rest describe the shape
in words, because a `format` would refuse a value the API takes. GATool
normalises the URL before the lookup, so every spelling of one page finds one
entry. `tools.ietf.org` and `datatracker.ietf.org` both find an RFC, with or
without `.html`, and with or without a section anchor. The scalar keeps its own
description either way. Where the entry describes the shape in words, the
property carries the URL. The model can then see which page defines the value.

**A scalar fragment you write.** Most schemas leave `@specifiedBy` unset. A
scalar's name alone does not say which values the API takes. That rule lives in
the API's coercion code, which the schema leaves out. You state the format
yourself:

```yaml
gatool:
  inputs:
    scalar-schemas:
      DateTime:
        type: string
        format: date-time
        description: An RFC 3339 timestamp, for example 2026-09-18T10:00:00Z.
```

Startup names every scalar that is still undescribed, so the warning tells you
which fragments to write. A scalar fragment wins over the `@specifiedBy` table.
Its `description` replaces the scalar's own.

Six keywords are published. Startup refuses any other keyword and names the
reason:

| Keyword | What it does |
| --- | --- |
| `type` | `string`, `integer`, `number` or `boolean`. It asserts, so a wrong one refuses a value your API would take. GATool writes the null branch itself |
| `format` | One of the ten formats Anthropic documents: `date-time`, `time`, `date`, `duration`, `email`, `hostname`, `uri`, `ipv4`, `ipv6` and `uuid`. It guides the model, and validation reads past it. OpenAI's strict subset leaves `uri` out, so a fragment that writes `uri` publishes with a startup warning |
| `pattern` | A regular expression the string has to match. It asserts, so prefer one that over-accepts. A pattern longer than 200 characters is refused. The pattern stays inside the syntax Java and ECMA 262 share, because a client compiles it as ECMA 262. Startup refuses a Java-only construct by name: inline flags, `\A`, `\z`, `\Z`, `\Q...\E`, `\p{...}`, the escapes `\R`, `\h`, `\H`, `\v`, `\V`, `\X`, `\G`, `\e`, `\a`, `\N{...}` and `\x{...}`, `&&` or a nested `[...]` inside a class, possessive quantifiers and atomic groups. Startup also refuses lookaround, a backreference, a named group and `\b`, because Anthropic's strict tool use answers them with `400 Invalid regex in pattern field` |
| `enum` | A closed value set, which only the API's owner knows. It asserts, because the MCP SDK's validator enforces it. An `enum` of more than 250 values is refused |
| `description` | The field that helps the model most, and the replacement for every refused keyword. A description longer than 1,024 characters is refused |
| `anyOf` | Two to eight branches, for a scalar with more than one wire form |

A scalar fragment whose JSON is longer than 2,048 characters is refused as a
whole. The schema travels in every `tools/list` and in every prompt that carries
the tool, so it has to stay short.

A numeric bound is refused, because Anthropic's strict tool use answers
`minimum` with HTTP 400: `For 'integer' type, properties maximum, minimum are
not supported`. `oneOf` is refused the same way. State a bound in `description`.
Anthropic's own SDKs do the same.

Three of the six keywords assert, so a scalar fragment can refuse a call before
the API sees it. They are `type`, `pattern` and `enum`, each measured against
the MCP SDK's own validator. You write the fragment because only you know the
wire form. Your `Long` may travel as a number, as a quoted string, or as either.
Write the wider shape when you are unsure, and let the API answer the rest.

**A profile adds to a scalar fragment.** `gatool.inputs.scalar-schemas` is a map
of maps, and Spring Boot merges a map across property sources. A fragment
written in `application.yml` and again in `application-prod.yml` therefore
publishes the keywords of both files. The profile changes the value of a
keyword the base file wrote, and it adds keywords of its own. A keyword the base
file wrote stays in the fragment when the profile leaves it out. Take a base
fragment that holds a `pattern` for a date. A profile writes `type: string` and
`format: date-time` over it. The merged fragment publishes all three keywords.
The timestamp the description asks for then fails the date pattern. To avoid
this, keep a keyword that differs between environments out of the base file and
write it in each profile. Or write that keyword in the profile as well, with the
value the profile needs. `gatool.results.scalar-schemas` merges the same way.

**A result reuses the scalar fragment.** You write the fragment once. Where a
tool publishes an output schema, a result describes the scalar from the same
fragment: its `type`, its `format` and its `description`. For a fragment that is
an `anyOf`, a result takes the `type` and the `format` of each branch. GATool
writes `null` beside them, as it does for every value of a result. The
description is written at the first position of each output schema where the
scalar appears. The later positions leave it out, because the schema travels in
every `tools/list`.

`enum` and `pattern` apply to arguments alone. A scalar fragment says what the
model should send. You may write it narrower than the API on purpose. An `enum`
may list fewer values than the API holds. A `pattern` may ask for `2026-09-28`
while the API returns `2026-09-28T00:00:00Z`. An output schema is binding in
MCP. A result that fails it is reported as an error, after the API has already
run the call. For a mutation, that error invites the model to retry a write that
was already applied. `type` and `format` describe the wire form, which a scalar
normally shares in both directions.

One case needs a second entry: a scalar that returns another form than it
accepts. Take a `Money` that is accepted as a number and returned as a quoted
string. It fails the reused `type: number` on every result. An entry under
`gatool.results.scalar-schemas` states what a result carries:

```yaml
gatool:
  inputs:
    scalar-schemas:
      Money:
        type: number
        description: An amount with two decimals, for example 12.50.
  results:
    scalar-schemas:
      Money:
        type: string
        description: An amount with two decimals, as a quoted string such as "12.50".
```

An entry there follows the rules of the table above. The output schema
publishes it as written, its `enum` and `pattern` included, because you wrote
them about results. It takes the place of the input fragment there. The input
schema reads `gatool.inputs.scalar-schemas` alone. To switch the reuse off for
one scalar, give it an entry under `gatool.results.scalar-schemas` that holds a
`description` alone. Its results then publish the empty schema, which accepts
any JSON value.

Take a scalar without a fragment whose `@specifiedBy` cites a specification
GATool has read. A result publishes the `type` and the `format` of that
specification. An argument publishes the same. A specification states one wire
form for both directions. A scalar fragment wins over the specification for
arguments and for results. An API that cites a specification and returns
another form takes an entry under `gatool.results.scalar-schemas`, like any
scalar that returns another form than it accepts.

**A 64-bit integer needs both forms.** A JSON reader that stores a number as a
double (IEEE 754 binary64) changes `9223372036854775807` into
`9223372036854776000`. The model can send the right digits and a client still
hands the server a different number:

```yaml
gatool:
  inputs:
    scalar-schemas:
      Long:
        anyOf:
          - type: integer
          - type: string
            pattern: "^-?(0|[1-9][0-9]{0,18})$"
        description: >-
          A 64-bit signed integer. Send a value outside -9007199254740991 to
          9007199254740991 as a quoted string, for example "9223372036854775807".
```

Whether the string branch belongs there depends on the API. graphql-java's
extended `Long` takes both forms. Three different `Long` specifications are
published, and one of them takes the string alone. The same scalar name means
different things behind different APIs, so the fragment has to come from you.

**A decimal reaches the API with every digit.** GATool's own mapper reads a
float as a `BigDecimal` on every surface: a result, an in-process argument and
an MCP argument. You do not need
`spring.jackson.deserialization.use-big-decimal-for-floats`. The MCP transport
reads a request with the `mcpServerJsonMapper` bean. That bean enables the same
setting, `USE_BIG_DECIMAL_FOR_FLOATS`. The digits an API receives can differ
from what the model wrote, because a `BigDecimal` prints its own way. `1e2`
reaches the API as `1E+2`, and `12.50` reaches it as `12.50`, both valid JSON
numbers. A decimal past the range of a `double`, such as `1E400`, reaches the
API as a number.

### Shared fragments

An operation file defines the fragments it spreads. A fragment is a named
selection set that an operation reuses with a spread, `...MovieCard`. A fragment
that several operations share can live in a file of its own, for example under
`gatool/mcp/fragments/`. A file that holds fragments alone becomes a shared
fragment file for the locations that reach it. Startup attaches each shared
fragment to every operation that spreads it and validates the assembled
document. The fragments are appended in the order of the first spread, depth
first, so the text an API receives stays the same between builds.

- A spread resolves to the operation file's own fragment first. It resolves to
  a shared fragment file in the same folder second, the MCP folder or the
  in-process folder. Two operation files may therefore each define their own
  `MovieCard`.

- An operation file whose own fragment has the name of a shared one keeps its
  own, and startup warns naming both files.

- Two shared fragment files in one folder that define the same name stop
  startup, and the message names both files.

- A shared fragment left unspread by every operation file earns a warning
  naming its file. GATool still validates it on its own. A wrong field in it
  stops startup with the fragment file's line and column. A spread without a
  definition in either place stops startup like any other validation error.

- `@defer` and `@stream` inside a shared fragment file stop startup, as they do
  in an operation file. The message names the fragment file. A validation error
  inside a shared fragment names the fragment file, with its own line and
  column, beside the operation file.

The CI check returns the assembled text of every tool. The startup listing is
the list of tools GATool prints at INFO when the application starts. For a tool
that uses shared fragments, it also prints the assembled text at `DEBUG` for
`io.gatool`. GATool does not support `#import` comments, because the GraphQL
specification treats comments like white space.

### The size of a document

GATool limits what it reads from a file, and the API limits what it accepts in a
request. The two are set in different places, so they can disagree.

**What GATool reads.** An operation file, a shared fragment file and the schema
file are your own text, so GATool reads them however long they are. One limit
remains on an operation file and a fragment file: selection sets may nest at
most 165 deep. A document nested far deeper exhausts the stack of the thread
that prints it. A file past that depth stops startup with a problem that names
the file. The problem says that graphql-java's parser stopped reading the file
at a limit. It quotes graphql-java's own count: `More than 500 deep 'grammar'
rules have been entered`. Those 500 grammar rules come to 165
selection sets. The limit is fixed in this release, and a property cannot
change it.

**What the API reads.** The API applies limits of its own to each request, and
it refuses a call whose document passes them. GraphQL's introspection describes
types and leaves request limits out, so GATool cannot read them from the API.
You state them under `gatool.api.request-limits`. Startup and the CI check then
warn about a tool whose document passes one:

| Property | Default, which is what graphql-java reads of a request |
| --- | --- |
| `gatool.api.request-limits.max-characters` | 1,048,576 |
| `gatool.api.request-limits.max-tokens` | 15,000 |
| `gatool.api.request-limits.max-whitespace-tokens` | 200,000 |

```yaml
gatool:
  api:
    request-limits:
      max-tokens: 5000
```

Set each to the limit of your API. The defaults hold for an API built on
graphql-java that left its parser options alone. Apollo Router stops at 15,000
tokens by default as well. It counts the ignored tokens, such as commas, among
them, so its limit is reached sooner. A value below 1 leaves that size
unchecked. The properties decide what GATool warns about. The API keeps
applying its own limits, whatever the properties say. A value above the API's
limit therefore silences a warning about a call the API will refuse.

The document counted is the one GATool sends: the operation as graphql-java
prints it, with the fragments of every shared file it spreads. The printer
indents each field, so a deeply nested document gains whitespace the file did
not hold. The tool is served either way. To get under the limit, select less or
split the operation.

**What the model writes.** `executeGraphql` is one of the dynamic tools,
described under "Getting started without writing operation files". A document
the model writes for `executeGraphql` is read under graphql-java's limits for a
request, nesting included. It is also read under three limits you set:
`max-depth`, `max-fields` and `max-aliases` under
`gatool.dev.experimental.dynamic-operations`. They protect the API from a
document that reached it without review, so set them at or below the limits of
the API.

### Reading the schema from a registry

`gatool.api.schema.location` is a Spring `Resource`. The value decides where
the schema comes from. `classpath:` and `file:` read a file in the jar or on a
mounted volume. GATool itself fetches an `http` or `https` URL at startup. A
registry serves the schema with a key in a header, and Spring's own
`UrlResource` cannot send one. Spring Cloud AWS, GCP and Azure register
`s3://`, `gs://` and `azure-blob://` with the same resource loader. A team adds
the matching starter and writes the URL.

[GraphQL Hive](https://the-guild.dev/graphql/hive/docs/high-availability-cdn)
serves the SDL of a target from its CDN, with the key in `X-Hive-CDN-Key`:

```yaml
gatool:
  api:
    schema:
      location: https://cdn.graphql-hive.com/artifacts/v1/${HIVE_TARGET_ID}/sdl
      header-name: X-Hive-CDN-Key
      header-value: ${HIVE_CDN_KEY}
```

The key stays out of every log line. Startup names the URL without its userinfo
and query. A secret read from a file often ends in a line break. A value holding
a line break stops startup, and the message names the property. A header name
outside RFC 9110's token characters stops startup the same way. A space inside
the value, as in `Bearer <key>`, is accepted. GATool follows up to five
redirects itself. It sends the key to the configured origin alone. A `Location`
on the same scheme, host and port gets the key. Any other host is fetched
without it. The pre-signed storage URL that Hive's CDN answers with is one
example. A redirect loop or a sixth hop stops startup, and the message names the
URL. `spring.http.clients.redirects` does not apply to this request.
`gatool.api.schema.max-size` bounds the body, at 10MB by default. This bound is
separate from one of the two response caps of a tool call,
`gatool.api.max-response-size`. A schema larger than that cap is therefore
fetched, while the cap stays where the deployment set it. A schema above the
bound stops startup, and the message names the property. The application may
leave `spring.http.clients.connect-timeout` or `read-timeout` unset. GATool then
fills the missing one: 3 seconds to connect, 15 seconds for an answer. Startup
says so at INFO, as it does for the API. Hive's CDN answers with an `ETag`.
GATool keeps it on the first line of the cached copy under
`gatool.api.schema.cache-directory`. The default is `schema`, inside a folder
named `gatool-` and the account the JVM runs as, under the JVM's temporary
directory (`java.io.tmpdir`). GATool writes the copy only after every operation
file has validated against the fetched schema. The copy therefore holds the last
schema that validated. A fetch that breaks an operation stops startup and leaves
the copy unchanged. The next startup sends `If-None-Match`. A `304` reuses the
copy. A registry that cannot be reached, or that answers `5xx`, starts the
application on the copy. A warning then names the copy's age. A `4xx` such as
`401` stops startup even with a copy on disk. The message says that the server
answered `401 UNAUTHORIZED`. Spring Boot's failure analysis adds the property
name, `gatool.api.schema.location`. The directory is configurable. A team that
wants the copy to outlive the temporary directory sets the property to a path of
its own. A persistent volume is one such path. A blank value fetches at every
startup and stops when the fetch fails.

**The default folder is checked before it is used.** Every account on a Linux
host shares the temporary directory. Another account could create GATool's
folder first and put a schema into it. The descriptions of that schema would
then reach the model as tool text. GATool makes the folder for its owner
alone. It uses the folder only while the account it runs as owns it, and while
group and others cannot write to it. A folder that fails the check is left
unread and unwritten, and startup warns with the reason. The schema is then
fetched at every startup. A startup while the registry is down stops. A
directory you configure is used as it is. A volume mounted into a container is
often owned by root and writable by a group, and it is yours to secure.

Apollo GraphOS and WunderGraph Cosmo hand the schema to their own command line
tools. `rover supergraph fetch` and `rover graph fetch` write the supergraph
or the API schema. `wgc federated-graph fetch-schema` writes the router schema
of a federated graph, or the client schema with `--client-schema`. Package the
API schema or the client schema. A supergraph or router schema carries fields
marked `@inaccessible`. Those fields pass validation at startup, and the router
refuses them at call time. GATool reads the file as written. A later release
may derive the API schema itself. A build step that runs one of these tools
and packages the file gives GATool a `classpath:` or `file:` location. A team
that publishes the file to a bucket or behind a URL of its own uses the
protocols above.

### Getting started without writing operation files

A development switch gives you tools from the schema alone. It is off by
default. Startup warns on every boot while it is on:

```yaml
gatool:
  dev:
    experimental:
      generate-tools: all-root-queries    # or all-root-queries-and-mutations
      generated-selection-depth: 1        # 1 to 5
      generate-tools-for-deprecated-root-fields: true
      generated-operations-include-deprecated-fields: true
```

Every root field becomes one tool. The field name becomes the operation name,
and the tool naming strategy turns that into the tool name. The default
strategy, `camel-case` under `gatool.naming.strategy`, leaves a camelCase
field name as it is. The field's arguments become the tool's arguments with
their defaults. The field's own schema description becomes the tool
description. The selection set takes the leaves of the type the field returns.
Depth `1` therefore selects the scalars one level in. A wrapper costs one level
per hop. A Relay connection therefore needs `3` to reach node data: the
connection, its edges, then the node. At `2` you get cursors and page counts,
and the node data stays out.
A union selects `__typename` and one inline fragment per member. An interface
selects `__typename` and its own fields.

The generator skips two kinds of root field. The first is a field whose type
expands to an empty selection within the depth. The second is a field whose
generated operation is longer than 20,000 characters. Startup names each
skipped field. For the first kind it says whether a deeper setting would reach
it. For the second kind it tells you to lower the depth. Inside a selection, a
field that would reach a type already on the path is left out. The rest of the
selection stays. A required argument of a root field becomes a required
argument of the tool. A field inside the selection that demands an argument is
left out of the selection, because the generator cannot supply its value. A
root field you dropped with the deprecation property below is skipped without
a message, since you asked for it.

**Deprecated fields stay in until you say otherwise.** Both deprecation
properties default to `true`, which is what the generator has always done. A
root field your schema marks `@deprecated` then becomes a tool. Its
deprecation goes into the tool description, where the model reads it. A
deprecated field takes its place in a generated selection set. Set
`generate-tools-for-deprecated-root-fields` to `false`, and that root field is
left out of the generation entirely. Set
`generated-operations-include-deprecated-fields` to `false`, and a deprecated
field stays out of a generated selection. The generator judges this by the
declaration of the type being selected. That setting is one way to trim what a
tool brings back. A root field whose type offers deprecated fields alone then
has an empty selection set and loses its tool. Startup says which setting
reaches it: the depth, this property, or only both together.

**An operation file wins.** A root field your files already select keeps your
operation. You can therefore generate the lot, pick the few you want, and
write those as files. The rest stay generated.

**Why it lives under `dev.experimental`.** GATool exists to serve trusted
documents, the operation files checked into your repository. The operation
files state which operations exist. A pull request reviews each one. The model
chooses from that list. The generator publishes the whole root of your API to
a model. Trusted documents exist to prevent exactly that. The generator may
change in any release. Startup says so on every boot while it is on. The
generated text goes through the same parser, validator and catalog as a file
you wrote. A generated tool therefore behaves as a tool from a file does.

**The dynamic tools need two dependencies.** With
`gatool.dev.experimental.generate-tools` set to `dynamic-three-step`, GATool
publishes `searchSchema`, `introspectType` and `executeGraphql`, the dynamic
tools. The search ranks schema coordinates with Lucene. A schema coordinate is
a type name, or a type and field name, such as `Query.topRatedMovies`. Neither
starter carries Lucene, because the dynamic tools are off by default. The two
jars are also 6.3 MB together (lucene-core 4.6 MB, lucene-analysis-common
1.7 MB). Add both yourself:

```xml
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-core</artifactId>
    <version>10.3.2</version>
</dependency>
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-analysis-common</artifactId>
    <version>10.3.2</version>
</dependency>
```

GATool builds against 10.3.2. Startup stops and names each missing jar when the
dynamic tools are on and one of the two is absent. The lucene-core jar holds the
index. The lucene-analysis-common jar splits an identifier such as
`topRatedMovies` into words and stems them. The split lets a question in plain
words find a field on a schema without descriptions. An application that
publishes a `SchemaSearch` bean of its own ranks with that bean, and both jars
stay out of its build. The bean builds its entries with `SchemaCorpus.of`, from
a schema the application reads on its own. `GAToolCatalog` holds the tools
alone, without the schema. The catalog depends on the search bean. A
`SchemaSearch` bean that injects the catalog therefore makes a cycle, which
Spring reports at startup. An operation file whose tool would take one of the
three names stops startup. The message names the file and the property. Give
that operation another name with `@gatool(name:)`. A search hit on a field an
interface declares says so. It names an implementation on which to select the
field. `introspectType` lists an interface's implementations in a trailing
comment. Its description names the query root. It names the mutation root as
well while `allow-mutations` is on.

`gatool.dev.experimental.dynamic-operations.include-deprecated-fields=false`
hides deprecated fields, arguments, enum values and input fields from
`searchSchema` and `introspectType`. A hidden field still executes when the
model writes it into a document. The property saves tokens. It does not
control access. A field a caller must not reach belongs outside the schema the
API serves. A federated subgraph publishes `Query._service { sdl }`. A model
can read the whole SDL through `executeGraphql` with it. The dynamic tools
therefore publish what the API publishes, with one exception. `executeGraphql`
refuses a document that selects `__schema` or `__type` at any depth. The
refusal sends the model to the two schema tools. The three limits on the
document the model sends, `max-depth`, `max-fields` and `max-aliases`, count
the document's syntax. Argument values, list sizes and resolver cost stay with
the API's own demand control. The two response caps,
`gatool.api.max-response-size` and `gatool.results.max-characters`, apply after
the API computed the result.

## Configuration

| Property | What it does |
| --- | --- |
| `gatool.api.url` | The GraphQL API every document goes to. GATool reports a redirect with its status and leaves it unfollowed, so name the URL the API answers at |
| `gatool.api.schema.location` | The schema that operations validate against: a `classpath:` or `file:` resource, an `https://` URL, or a protocol a Spring Cloud module registers |
| `gatool.api.schema.header-name` | The header that carries a registry key when the location is an `http` or `https` URL |
| `gatool.api.schema.header-value` | Its value, kept in the environment |
| `gatool.api.schema.cache-directory` | Where a fetched schema and its `ETag` are kept. The default is `schema` inside `gatool-<account>` under the JVM's temporary directory (`java.io.tmpdir`), where `<account>` is the account the JVM runs as. GATool makes the default folder for its owner alone. It uses that folder only while the account owns it and group and others cannot write to it. A path of your own keeps the copy on a persistent volume and is used as it is. A blank value fetches the schema at every startup |
| `gatool.api.credentials.strategy` | How GATool authenticates to that API. Unset sends each call without a credential. `static-header` sends the header below. `client-credentials` obtains GATool's own token. `token-exchange` exchanges the caller's |
| `gatool.api.credentials.header-name` | The header that carries the credential, such as `Authorization` or `X-API-Key` |
| `gatool.api.credentials.header-value` | Its value, such as a bearer token, kept in the environment |
| `gatool.api.credentials.client-registration-id` | The Spring Boot client registration the two OAuth2 strategies authenticate through |
| `gatool.api.credentials.audience` | The audience token exchange asks the issuer for, which the API validates |
| `gatool.api.credentials.resource` | The resource token exchange names to the issuer, sent when set |
| `gatool.mcp.operations.locations` | Where the MCP folder lives, the operation files served over the Model Context Protocol (MCP). The default is `optional:classpath*:gatool/mcp/`. Naming a folder replaces that default, so list the default again beside a new folder to keep the tools already there. A `classpath*:` location inside a jar needs directory entries in that jar, which Maven and Gradle write. Spring lists a jar's folder through those entries |
| `gatool.in-process.operations.locations` | Where the in-process folder lives, `optional:classpath*:gatool/in-process/` by default. It is replaced the same way, and the same jar rule applies |
| `gatool.naming.strategy` | `camel-case`, `snake-case` or `as-written` |
| `gatool.results.max-characters` | Largest result a tool returns, 60000 characters by default. The count is Java's `String.length()`, which counts UTF-16 code units. A character outside the Basic Multilingual Plane, such as an emoji, counts as two |
| `gatool.results.publish-output-schema` | Whether tools publish an output schema and structured content. The dynamic tools, the ones `dynamic-three-step` generates, are published without one either way |
| `gatool.results.partial-results-as-success` | Whether a response holding both data and errors counts as a success, off by default |
| `gatool.results.scalar-schemas.<Name>` | The scalar fragment, a piece of JSON Schema, that the output schema publishes for a custom scalar that returns another form than it accepts. It replaces what the output schema reuses from `gatool.inputs.scalar-schemas.<Name>` |
| `gatool.api.request-limits.max-characters` | Characters you expect the API to read of a document, 1,048,576 by default, which is what graphql-java reads of a request. Startup and the CI check, the `OperationFilesAssert` test, warn about a tool whose document holds more. The tool is served all the same. A value below 1 leaves the size unchecked |
| `gatool.api.request-limits.max-tokens` | Tokens you expect the API to read of a document, 15,000 by default. The warning and the value below 1 work as for the characters |
| `gatool.api.request-limits.max-whitespace-tokens` | Whitespace tokens you expect the API to read of a document, 200,000 by default, counted in the printed document GATool sends. The warning and the value below 1 work as for the characters |
| `gatool.api.max-response-size` | Largest response GATool reads from the API, 1MB by default. This and `gatool.results.max-characters` are the two response caps. The value decides the heap one call in flight holds, which "Running in a container" sizes. A value in bytes below `gatool.results.max-characters` makes this cap refuse every result the result limit would have allowed. Startup then warns naming both |
| `gatool.api.schema.max-size` | Largest schema GATool fetches from a URL, 10MB by default. A schema above it stops startup naming the property. The bound is the schema's own. A public schema of a few megabytes is fetched while `gatool.api.max-response-size` stays where the deployment set it |
| `gatool.mcp.rate-limit.calls-per-minute` | Calls one caller makes to one tool each minute, 60 by default. Once the endpoint is secured, the caller is the token's subject. Where an application's own converter leaves the name blank, the caller is the token's fingerprint, a SHA-256 hash of the bearer token. The session binding, which ties an MCP session to the caller that opened it, uses the same key. While the unauthenticated switch is on, the caller is the client address. The caller is read through the `SecurityContextHolderStrategy` bean an application declares, so a strategy of the application's own keeps the count per caller. Behind a proxy that address is the proxy's. Set `server.forward-headers-strategy=native` with `server.tomcat.remoteip.internal-proxies` naming the proxy. That replaces a default which covers every private address range. `framework` trusts the forwarded headers every client sends, so a caller could then send a different address on every call and escape the count |
| `gatool.mcp.rate-limit.max-tracked-pairs` | Most caller and tool pairs the limiter counts at once, 100,000 by default. Each pair holds a bucket of a few hundred bytes for a minute after its last call. Above the cap, the limiter drops a live pair's bucket for each new one, and that caller starts with a fresh allowance. The Micrometer counter `gatool.rate-limit.evictions` counts those drops. The log warns once a minute naming the property. Set it above the pairs active within a minute |
| `gatool.mcp.transport.max-request-body-size` | Largest request body the MCP endpoint reads, 256KB by default |
| `gatool.mcp.transport.allowed-origins` | The origins the endpoint accepts in an `Origin` header, empty by default. A request carrying the header is refused with `403` until its origin is listed. A request without the header is accepted. Each entry is a serialized origin, `scheme://host[:port]`. The scheme and the host are compared case-insensitively, and one trailing slash is ignored. An entry of another shape stops startup. CORS stays the application's. A browser client also needs a `CorsConfigurationSource` bean of the application's own, such as a `UrlBasedCorsConfigurationSource`. That bean is what Spring Security's `cors()` reads |
| `gatool.mcp.sessions.max-count` | Most stateful sessions the server keeps at once, 1000 by default |
| `gatool.mcp.sessions.max-count-per-caller` | Most stateful sessions one caller may hold at once, 100 by default. Zero or below turns it off |
| `gatool.mcp.sessions.idle-timeout` | How long a stateful session may stay idle before eviction, 30 minutes by default. `spring.ai.mcp.server.streamable-http.keep-alive-interval` has to be shorter than it. Otherwise startup stops naming both |
| `gatool.mcp.security.baseline-scopes` | Scopes every MCP call needs, published in the metadata and every `401` |
| `gatool.mcp.security.resource` | The canonical resource URI the metadata publishes. Unset publishes the request's URL |
| `gatool.mcp.stdio.granted-scopes` | The scopes the process that started a stdio server holds. Tools requiring scopes are checked against them |
| `gatool.mcp.stdio.log-to-stderr` | Whether a stdio server writes its log to stderr, on by default. stderr carries WARN and above until `logging.threshold.console` sets another threshold. The log is written without making the server wait. A host that leaves stderr unread therefore loses lines while the server keeps answering, and a WARN line says how many once lines get through again. This applies on Logback, while the application leaves `logging.console.enabled` unset and the logging configuration to Spring Boot's defaults. On Log4j2, startup says in one line on stderr that the log is lost, while the application leaves the log file and `logging.config` unset. On `java.util.logging`, a log file leaves the stderr log on, and the application's own `logging.config` is what changes it |
| `gatool.inputs.scalar-schemas.<Name>` | The scalar fragment, a piece of JSON Schema, to publish for a custom scalar |
| `gatool.inputs.send-explicit-nulls` | Whether a null argument for a variable that declares a default reaches the GraphQL API as an explicit null, off by default. A null for a variable without a default is sent either way |
| `gatool.dev.experimental.generate-tools` | Which tools to write from the schema: `none` by default, `all-root-queries`, `all-root-queries-and-mutations` or `dynamic-three-step` |
| `gatool.dev.experimental.generate-tools-for-deprecated-root-fields` | Whether a deprecated root field becomes a tool, on by default. It applies only under `all-root-queries` and `all-root-queries-and-mutations` |
| `gatool.dev.experimental.generated-operations-include-deprecated-fields` | Whether a deprecated field is selected, on by default. It applies only under `all-root-queries` and `all-root-queries-and-mutations` |
| `gatool.dev.experimental.generated-selection-depth` | How many object levels a generated selection expands, 1 by default. Under those two modes the value is 1 to 5, and startup stops on any other value |
| `gatool.dev.experimental.dynamic-operations.corpus-format` | How each schema coordinate is written for the search index, `sdl` by default. A schema coordinate is a type name, or a type and field name, such as `Query.topRatedMovies`. `gloss` is one sentence and `raw` the coordinate alone |
| `gatool.dev.experimental.dynamic-operations.search-backend` | What ranks a search, `bm25` by default. `embedding` uses the application's `EmbeddingModel` bean, and startup stops without one |
| `gatool.dev.experimental.dynamic-operations.search-token-budget` | Tokens of search results one `searchSchema` call returns, 2000 by default. A token is counted as four characters of the text and the hint. A best match above the budget is named with its cost, so the model can read it with `introspectType` |
| `gatool.dev.experimental.dynamic-operations.ranked-hits` | Coordinates a search ranks before the budget cuts the list, 50 by default |
| `gatool.dev.experimental.dynamic-operations.allow-mutations` | Whether a mutation the model wrote runs, off by default. While off, the mutation root and the types only it reaches stay out of `searchSchema`, `introspectType` and the hints |
| `gatool.dev.experimental.dynamic-operations.include-deprecated-fields` | Whether a deprecated field, argument, enum value or input field is searchable, readable and used as a step in a hint, off by default. While on, each is written with the reason the schema gives |
| `gatool.dev.experimental.dynamic-operations.validate-only` | Whether `executeGraphql` returns the validated operation for a person to run, without calling the API, off by default |
| `gatool.dev.experimental.dynamic-operations.search-schema-description`, `.introspect-type-description` and `.execute-graphql-description` | The whole description of one of the three dynamic tools. It replaces the text GATool writes, the sentences GATool generates from the schema and the settings included. Unset by default, and a blank value keeps GATool's text |
| `gatool.dev.experimental.dynamic-operations.max-depth` | Deepest field nesting `executeGraphql` sends, 15 by default. 0 switches the check off |
| `gatool.dev.experimental.dynamic-operations.max-fields` | Most field selections `executeGraphql` sends, spreads counted where spread, 500 by default. 0 switches the check off, and graphql-java's own ceiling of 100,000 selections still applies |
| `gatool.dev.experimental.dynamic-operations.max-aliases` | Most aliased fields `executeGraphql` sends, 30 by default. 0 switches the check off |
| `gatool.dev.experimental.dynamic-operations.question-prefix` | Text put in front of a question before it is embedded, empty by default. It is joined as it is, so quote the value in YAML to keep a trailing space |
| `gatool.dev.experimental.dynamic-operations.field-prefix` | Text put in front of each schema field before it is embedded, empty by default, joined the same way |
| `gatool.dev.experimental.dynamic-operations.embed-batch-size` | Schema fields sent to the embedding model in one call, 128 by default |
| `gatool.dev.experimental.dynamic-operations.vector-cache-directory` | Where embedded fields are cached, so a schema is embedded once. The default is `vectors` inside `gatool-<account>` under the JVM's temporary directory (`java.io.tmpdir`), checked the way the schema cache's default folder is. A path of your own keeps the vectors on a persistent volume and is used as it is. A blank value embeds at every startup. The files are written through a temporary file and an atomic rename. A cache an earlier build wrote is embedded once more, because the key format changed |
| `gatool.dev.experimental.dynamic-operations.required-scopes` | The scopes a caller needs for the dynamic tools on top of the baseline, all of them. Required under a shared credential, which is client credentials or a static header: one identity for every caller |
| `gatool.observations.include-content` | Whether arguments and results join the `gatool.call` observation as high-cardinality values, off by default |

Every property has Javadoc, which an IDE shows while you type. The Javadoc says
what the property does. The text below says what a setting costs.

A call to a remote API is bounded by `spring.http.clients.connect-timeout` and
`spring.http.clients.read-timeout`. Spring Boot's `RestClient` builder carries
both into the client GATool uses. GATool fills whichever of the two the
application left unset: 3 seconds to connect, 15 seconds for an answer. It says
so at INFO. Without a deadline, a call to an API that stops answering would hold
the thread until the socket closes. Under `client-credentials` and
`token-exchange`, the request to the issuer's token endpoint waits under the
same two deadlines. The API may take longer than the read timeout to answer a
query. The timeout can hit before the status arrives or inside the body. The
model then reads what happened, the deadline, and the property that sets it. It
is asked for fewer fields or a smaller page, because the same call is likely to
take as long again. When the connection did not open, the model reads that the
call did not run and that a later call may succeed. For any other transport
failure, the model reads that GATool cannot tell whether the call ran. A
mutation reads the same account of how far the call got. After a mutation fails
in any of the three ways, the model is asked to read the current state before
sending it again. The model's sentence and the operator's line open with the
same words. The operator reads a WARN line for each such call, query or
mutation. The line says how far the call got and ends with one of three causes:

- the tool `could not reach the GraphQL API`

- the API `took longer to answer than the read timeout of 15 seconds`

- the tool `failed in transport while calling the GraphQL API, and the cause
  does not say whether the call ran`

The stack is written at DEBUG. In embedded mode, where the GraphQL API runs
inside the same application through Spring for GraphQL, the call stays in the
same JVM. This release leaves that call without a deadline.

The MCP Java SDK client and Spring AI's MCP client wait 20 seconds for a tool
call by default. Their clock starts before GATool's does. Keep
`spring.http.clients.read-timeout` below the request timeout of the client
that calls the server, or raise `spring.ai.mcp.client.request-timeout`. A
client that reaches its own deadline first gives up on the call. The model
then reads the client's timeout message. GATool's sentence does not reach it.
After a mutation, GATool's sentence is the one that asks the model to read the
current state before it sends the write again.

GATool builds the request factory of that client from the application's
`ClientHttpRequestFactoryBuilder` bean and `spring.http.clients.*`. Startup
names the builder at INFO. The headers and interceptors a `RestClientCustomizer`
sets reach every request GATool sends. Startup names the customizer beans. A
request factory the customizer installs stays on the application's own clients.
GATool keeps its own factory, because that factory carries the two response
caps, `gatool.api.max-response-size`, and leaves redirects unfollowed. Without a
builder bean, `ClientHttpRequestFactoryBuilder.detect()` prefers Apache
HttpClient 5 and Jetty over the JDK client. `httpclient5` on the classpath, for
any reason, moves GATool onto Apache's pool: 5 connections per route and 25 in
all. We measured about 48 calls per second on that pool, against 398 with the
JDK client. Startup warns on that builder.
`spring.http.clients.imperative.factory=jdk` selects the JDK client. A
`ClientHttpRequestFactoryBuilder` bean built from
`ClientHttpRequestFactoryBuilder.httpComponents().withConnectionManagerCustomizer(...)`
raises both sizes.

A proxy reaches GATool's client through that same builder bean. The bean
replaces the builder Spring Boot declares, so every client the application
builds from Spring Boot's builder goes through the proxy as well. A factory a
`RestClientCustomizer` installs stays on the application's own clients. A proxy
set only there does not reach GATool's calls:

```java
@Bean
ClientHttpRequestFactoryBuilder<?> gatoolRequestFactoryBuilder() {
    return ClientHttpRequestFactoryBuilder.jdk()
            .withProxySelector(ProxySelector.of(new InetSocketAddress("proxy.example.com", 8080)));
}
```

Mutual TLS reaches GATool's client the same way, through
`spring.http.clients.ssl.bundle`. GATool reads that property with the rest of
`spring.http.clients.*`. A `RestClientCustomizer`'s factory stays on the
application's own clients here as well:

```yaml
spring:
  ssl:
    bundle:
      jks:
        mtls:
          keystore:
            location: classpath:client-keystore.p12
            password: ${KEYSTORE_PASSWORD}
          truststore:
            location: classpath:client-truststore.p12
            password: ${TRUSTSTORE_PASSWORD}
  http:
    clients:
      ssl:
        bundle: mtls
```

In embedded mode, the
document runs on the application's `WebGraphQlHandler` bean. Without that bean,
GATool builds one from the `ExecutionGraphQlService` and the application's
`WebGraphQlInterceptor` beans. The interceptors therefore apply in every
application type, a non-web one included. Startup names them. That call
bypasses the servlet filter chain and every URL rule on
`spring.graphql.http.path`. Three controls remain on it: the MCP endpoint's
scopes, those interceptor beans, and method security in the resolvers.

Every MCP tool call is a Micrometer observation named `gatool.call`. Its
low-cardinality tags are `gatool.call.name`, `gatool.operation.type` (`query`
or `mutation`) and `gatool.call.outcome`. The outcome is one of `success`,
`graphql-errors`, `api-refused`, `credential-unavailable`,
`response-too-large`, `result-too-large`, `tool-error`, `rate-limited`,
`insufficient-scope` and `off-request-thread`. An answer the operator has to
act on reaches the log at WARN. A `5xx`, and a `2xx` without a GraphQL
response, are written once per tool per minute. The next such line says how
many answers were left out since the last one. A refused credential and a
redirect are written on every call. A `4xx` stays at DEBUG, because the model
reads it and can act on it. The model corrects the call. After a `408`, a `425`
or a `429`, it reads that a later call may succeed and sends the same call
again. It reads the same for a `5xx`.

Four Spring AI settings take a default from GATool while the application leaves
them unset. The application's own value wins. `spring.ai.mcp.server.protocol`
becomes `STATELESS` over HTTP. `spring.ai.mcp.server.name` becomes
`${spring.application.name:mcp-server}`, so an application without a name is
called `mcp-server`. `spring.ai.mcp.server.version` becomes
`spring.application.version` when that property is set.
`spring.ai.mcp.server.instructions` becomes one sentence that says what every
tool result holds. Under `dynamic-three-step`, it describes the loop the
dynamic tools serve: search, read, run and correct.
`spring.main.lazy-initialization=true` is supported. GATool keeps Spring AI's
server bean eager, so the endpoint answers from the first request. It keeps its
tool catalog eager on both starters, so a broken operation file still stops
startup.

**Spring AI's line about tool methods.** An application whose tools all come
from operation files reads this line at every startup, at WARN. It comes from
`SyncStatelessMcpToolProvider` on the stateless transport and from
`SyncMcpToolProvider` on the stateful one:

```
No tool methods found in the provided tool objects: []
```

Spring AI's annotation scanner collects the beans that carry `@McpTool`
methods and builds tool specifications from them. Without such a method the
list of beans is empty, which is the `[]`, and the provider says so. The tools
of the operation files are registered another way. GATool publishes them as a
list of tool specifications of its own, and Spring AI's server reads that list
beside the scanner's. Its line `Registered tools:` follows at INFO with the
count, which includes them, and `tools/list` serves them. An application
without `@McpTool` methods of its own can set
`spring.ai.mcp.server.annotation-scanner.enabled=false`. The scanner and its
line then stay out of the startup.

A null argument is the one place GATool writes JSON with a mapper of its own.
GraphQL reads a variable set to `null` as clearing a value. It reads a variable
left out as a request for the default. That default comes from the operation,
or from the schema's argument or input field the variable fills. Every null
GATool holds therefore reaches the request it sends. An application that sets
`spring.jackson.default-property-inclusion` to a value dropping nulls would
otherwise lose them on the way out. Startup says so on the boot where that
happens. Every other HTTP client in the application writes with the mapper it
had.

**A null argument reaches the API.** Spring AI's MCP transports read each
request body with the `mcpServerJsonMapper` bean. Spring AI's own bean drops a
`null` map entry while reading. GATool declares that bean with a null map value
kept, so a model can clear a value by sending `null`. The rules above decide
what the API receives. The protocol JSON the transports write is unchanged. A
result map of your own tool that holds a `null` value writes it. Declare a bean
of that name yourself and GATool leaves it to you.

### The unsafe switches

Five properties weaken a control the server would otherwise keep: four under
`gatool.mcp.security.unsafe` and one under `gatool.api.credentials.unsafe`. Each
is `false` by default. While one of the four is on, GATool logs a warning naming
it at every startup. The fifth,
`gatool.api.credentials.unsafe.forward-client-tokens`, warns at every startup on
which GATool creates its own strategy bean for it. An application with an
`ApiCredentialStrategy` bean of its own reads an INFO line naming that bean.

| Property | What you give up |
| --- | --- |
| `gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication` | The unauthenticated switch. Any caller that reaches the port may call every tool. Keep the port on a loopback address with `server.address`, or behind a gateway that authenticates for it. Startup warns while `server.address` is unset, because the server then listens on every interface. Spring Boot's own default stays as it is, the way Spring AI's MCP starter leaves it. |
| `gatool.api.credentials.unsafe.forward-client-tokens` | The forwarded token. Every call to the GraphQL API carries the token the MCP caller sent. MCP says a server must not pass that token through. The API cannot tell GATool from the caller. A token minted for this server reaches another audience. A server with this switch on is outside the MCP specification. It only works with an API that skips audience validation. |
| `gatool.mcp.security.unsafe.request-every-scope-at-sign-in` | The metadata and every `401` name every scope this server knows, so a client asks for all of them at sign-in. MCP's security best practices call that a common mistake. It serves clients whose step-up, a second sign-in that asks for more scopes, fails. |
| `gatool.mcp.security.unsafe.allow-unlimited-tool-calls` | The rate limiter stops running. MCP requires a server to rate limit tool calls, so a deployment that sets this covers it at a gateway. |
| `gatool.mcp.security.unsafe.allow-superseded-mcp-revisions` | The endpoint accepts 2024-11-05 and 2025-03-26. GATool serves 2025-03-26 in part, because it cannot read the JSON-RPC batches that revision asks for. |

`gatool.dev.experimental.*` is separate. Those settings leave every control in
place. They publish more of your API than a folder of trusted documents, the
operation files checked into your repository, would. They may change in any
release.

## Securing the MCP endpoint

The Model Context Protocol (MCP) endpoint is an OAuth 2.1 resource server once
Spring Security is on the classpath. Add the starter and name the issuer, the
audience and the scopes every call needs:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
</dependency>
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://issuer.example.com
          audiences: movies-mcp
gatool:
  mcp:
    security:
      baseline-scopes: mcp:tools
```

Startup stops while any of the three settings above is unset,
and the message names the property. Startup also stops when Spring Security is
absent and `gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication`,
the unauthenticated switch, is off. That message names the starter. The
endpoint reads JWT access tokens only. An opaque token that needs introspection
is unsupported, and the stop for a missing `issuer-uri` says so. Spring Boot's
decoder reads the issuer's discovery document at the first request. A wrong
`issuer-uri` therefore surfaces at the first request, and the stop at startup
covers an unset property alone. An `issuer-uri` that cannot be reached, or that
answers without a discovery document, gives `500` on every call that carries a
token. Spring Security raises `JwtDecoderInitializationException` there,
outside the bearer token handling. An `issuer-uri` that answers and differs
from the `iss` claim of the token gives `401` with `invalid_token`. With the
issuer, the audience and the scopes set, a call without a token gets a `401`.
Its challenge names this endpoint's metadata document,
`/.well-known/oauth-protected-resource/mcp`, and the baseline scopes. A client
reads the document to find the issuer, and asks for the scopes at sign-in. The
document answers anonymously with the issuer and the scopes. The root document
at `/.well-known/oauth-protected-resource` stays unserved. RFC 9728 gives that
path the root resource's identifier, and a client reads the URL the challenge
names. A request there meets the default chain's `401` with a bearer challenge.
A token for another audience gets `401` with `invalid_token`, because Spring
Boot's decoder checks the audience. A token that lacks a baseline scope gets
`403` with `insufficient_scope`. CSRF stays off for the endpoint and its
metadata path, since an MCP client authenticates with a bearer token alone.

An application without a `SecurityFilterChain` of its own gets two from the
starter. The first serves the endpoint and its metadata path. The second is a
default chain at Spring Boot's basic-auth order, shaped like the chain Boot
would have contributed. Every other path then needs a bearer token for the same
issuer. This is how Boot's resource server chain works, because Boot's
generated user backs off for a `JwtDecoder` bean. Under the unauthenticated
switch without an issuer, the default chain keeps form and basic login against
the generated user. The bearer shape runs without an HTTP session, a saved
request or CSRF protection. Boot's own chain keeps all three. A request without
a token therefore cannot open a session. The actuator's health endpoint stays
open where the actuator is present. An error dispatch is left to Boot's error
controller. A request the endpoint refuses with a `404` therefore reads Boot's
error body in place of a login challenge. Both chains back off as soon as the
application declares any chain of its own, which is Boot's rule for its own
defaults. An application that declares its own chains gives the endpoint a
chain of its own, ordered ahead of the rest. Its matcher covers both paths, and
it carries the configurer alone:

```java
@Bean
@Order(1)
SecurityFilterChain mcp(HttpSecurity http) throws Exception {
    return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource/mcp")
            .with(McpServerSecurityConfigurer.mcpServer(), withDefaults())
            .build();
}
```

The configurer refuses a chain that already carries `authorizeHttpRequests`
rules. Spring Security registers a configurer's rules at build time, after the
chain's own, and the first matching rule wins. A broader rule would therefore
decide the endpoint ahead of the scope check. Startup also stops when the chain
that serves the endpoint lacks the resource server, and that message names the
chain above.

Behind a proxy that terminates TLS, the challenge and the metadata document
would name the address the servlet container saw. Set
`gatool.mcp.security.resource` to the URL clients reach, such as
`https://mcp.example.com/mcp`, and both name that URL. Or set
`server.forward-headers-strategy=native` and name the proxy in
`server.tomcat.remoteip.internal-proxies`. The container then reads the
forwarded headers from that proxy alone. The default of that property covers
every private address range. With the default, any caller on a private network
is read as a proxy, and `trusted-proxies` adds to that list without narrowing
it. The `framework` strategy trusts the forwarded headers every client sends. A
caller could then choose the host the challenge names and the address the rate
limiter counts calls against. That limiter counts per caller under the same key
as the session binding, which ties an MCP session to the caller that opened it.
The key is the token's subject once the endpoint is secured, and the client
address while the unauthenticated switch is on. The value of
`gatool.mcp.security.resource` is an absolute URL on the endpoint's own path,
without a query, a fragment or a trailing slash. Startup stops on any other
value. MCP ties the audience to that canonical URL. The issuer should therefore
write it as the token's `aud`, and
`spring.security.oauth2.resourceserver.jwt.audiences` should name it. The
audience check belongs to Spring Boot's decoder, so an application that
declares a `JwtDecoder` bean of its own owns that check.

Some issuers name an API by an identifier of their own and write that identifier
as the audience. Microsoft Entra ID gives an API an App ID URI,
`api://<client-id>` by default. In a v2.0 access token, the `aud` claim holds
the client ID. In a v1.0 token, it holds the client ID or the App ID URI.
[Microsoft's claims
reference](https://learn.microsoft.com/en-us/entra/identity-platform/access-token-claims-reference)
describes both. With such an issuer the two properties take different values.
`gatool.mcp.security.resource` takes the URL of the endpoint, under `http` or
`https`. RFC 9728 defines the resource identifier as an `https` URL. Every
challenge builds the address of the metadata document from the scheme, host and
port of that value. Startup stops on `api://<client-id>` there, and the message
names the URL to set and the property for the identifier.
`spring.security.oauth2.resourceserver.jwt.audiences` takes the identifier as
the token carries it. Spring Boot's decoder compares the `aud` claim with that
property alone. A token issued for `api://<client-id>` is therefore accepted
once the property names it, whatever URL the resource property holds.

Once every bean exists, startup reads the live chains. It checks that the
metadata document answers an anonymous `GET` under the chain that serves it. It
checks that the chain serving the endpoint is the configurer's own. That
catches an application chain ordered ahead of it with a resource server of its
own. It checks that the compliance filter, the bean
`gaToolMcpComplianceFilter`, is registered and runs ahead of Spring Security's
filter. That filter reads each request ahead of the scope check. Every stop
names the line to change.

GATool serves the endpoint with the dispatcher servlet mapped at the root.
Startup therefore stops on `spring.mvc.servlet.path` set to anything else, and
on an endpoint written as a pattern. Behind `server.servlet.context-path` the
endpoint and its metadata document carry the context path, and
`gatool.mcp.security.resource` names it in front of the endpoint. The document
then sits at the context path followed by the well-known path. Every challenge
points there. RFC 9728 would place the well-known path ahead of the whole
resource path. A client that follows the challenge finds the document. A client
that probes the RFC location behind a context path misses it.

On the stateful transport each session is bound to the caller that opened it,
as MCP's security best practices recommend. A request that carries another
caller's session id answers `404` with code `-32001`, the code for a session
the server does not serve. A deletion request is treated the same way. That
client then starts a session of its own. A request with an id the server does
not know gets the same `404` and the same code. This holds on the secured path
and with the unauthenticated switch on. The binding key is the token's subject.
Where an application's own converter leaves the name blank, the key is the
token's fingerprint, a SHA-256 hash of the bearer token. A token that lacks
both answers `401`. Every request that carries the session id refreshes the
binding. GATool forgets the binding when the SDK confirms a deletion, and when
the SDK answers `404` for the session. It also forgets a binding that has been
silent for three times `gatool.mcp.sessions.idle-timeout`. That sweep runs at
the next session opening, and by then the SDK has evicted the session itself.
The SDK writes an exception as the body of a `400`, a `404` or a `500`. GATool
rewrites such a body to the JSON-RPC error alone. The error carries the call's
id where the request held one, so a stack trace stays out of every answer.

In production set `gatool.mcp.security.resource`. Without it, every challenge
and the metadata document name the address a request carried in its `Host`
header, which the caller writes. Startup warns about that when the server is
bound off loopback.

### The caller and the request thread

GATool reads the caller of a tool call from the thread the call runs on. The
rate limiter names the caller from Spring Security's context on that thread.
While the unauthenticated switch is on, it names the caller from the servlet
request bound to that thread. The `token-exchange` strategy and the forwarded
token, `gatool.api.credentials.unsafe.forward-client-tokens`, take the caller's
token from the same context. The scope check runs earlier, in the security
filter chain, so it reads the token of the request itself.

That reading is right while the call runs on the thread that served its HTTP
request, and GATool checks that on every call. The MCP SDK hands a synchronous
tool handler to a worker thread of Reactor's bounded elastic scheduler, unless
the server was built with `immediateExecution(true)`. Spring AI's stateless
server bean sets that itself. Its stateful server bean takes the setting from a
`McpSyncServerCustomizer` bean that Spring AI contributes. On a worker thread
every caller would share one rate limit. Under Spring Security's
`MODE_INHERITABLETHREADLOCAL` a call could reach the API with the token of
another caller.

**Startup stops for a server built without immediate execution.** The stateful
server bean takes one customizer. An application's own `McpSyncServerCustomizer`
bean beside Spring AI's stops startup, because Spring finds two beans for one
parameter. The application's bean replaces Spring AI's when it is marked
`@Primary`, or when it is named `mcpSyncServerCustomizer`, the parameter name
of Spring AI's bean method. Immediate execution is then lost, unless the
application's bean sets it. GATool reads the setting from every `McpSyncServer`
and `McpStatelessSyncServer` bean once every singleton exists. A server built
without the setting stops startup. The report names the customizer that dropped
it and the line to add:

```java
@Bean
@Primary
McpSyncServerCustomizer serverCustomizer() {
    return server -> server.immediateExecution(true)
            .requestTimeout(Duration.ofSeconds(30));
}
```

The MCP SDK keeps that setting in a private field, so GATool reads it by
reflection. Where a later SDK puts the field out of reach, startup writes one
WARN naming the SDK version and carries on. The refusal below still guards such
a server.

**A call that arrives off the request thread is refused.** The compliance
filter marks the thread it serves each request on. The mark is a plain
thread-local. A child thread does not inherit it, and Reactor's context
propagation does not carry it. A tool call that reaches the handler on an
unmarked thread is answered with a tool error. The error says that the server
ran the call on a thread that does not carry the caller, and
`gatool.call.outcome` records it as `off-request-thread`. The operator reads
one ERROR line a minute for each tool, naming the thread. The check runs on the
servlet transports and is off over stdio, where one process is the only caller.
It runs whether or not the deployment's rate limiter or credential strategy
reads the caller. GATool cannot see what a `ToolRateLimiter` or
`ApiCredentialStrategy` bean of the application does with the caller.

The check also refuses one application that reads each caller correctly. That
application sets `spring.reactor.context-propagation=auto`, declares its own
customizer without immediate execution, and secures the endpoint. It reads the
caller on the worker thread through the accessor Spring Security registers.
Adding `immediateExecution(true)` to the customizer serves that application,
and the property keeps serving its own Reactor code. Calls on virtual threads
are served. With `spring.threads.virtual.enabled=true` the filter and the
handler run on the same virtual thread.

**A per-caller credential is refused where the thread's context may belong to
another caller.** `token-exchange` and the forwarded token both read the caller
from the security context holder strategy. Under Spring Security's default
strategy, a token on the thread was put there for the work the thread is doing.
A filter, context propagation, or the application's own code put it there. Under
`MODE_INHERITABLETHREADLOCAL` a thread created during one request keeps that
request's context for life. Under `MODE_GLOBAL` one context is shared by every
thread. Under either of those two, a call on a thread that is not serving a
request is refused. The model reads which strategy is in force. It also reads
that the default strategy with `spring.reactor.context-propagation=auto`
carries each caller to the thread its call runs on. This check covers the
in-process surface too, where a streamed chat legitimately runs its tools on
Spring AI's worker threads.

Two shapes get past that last check. A `SecurityContextHolderStrategy` bean of
an application's own class, and a `ListeningSecurityContextHolderStrategy`
wrapped around another strategy, cannot be classified. GATool treats them as it
treats the default. An application makes `RequestContextHolder`
inheritable with `setThreadContextInheritable(true)` on its
`RequestContextFilter` or `DispatcherServlet`. A child thread then has a bound
request, which is the signal this check reads.

These shapes are refused as well, and each would otherwise be served:

- A replaced compliance filter. An application may replace the filter inside
  the compliance filter bean with one of its own. With the unauthenticated
  switch on, the mark then stays off every thread. Every call is refused, and
  startup stays silent about it. With the endpoint secured, startup already
  stops on that set-up.

- A transport of the application's own. It hands the call to another thread
  and carries the security context there itself.

- A call from application code on a thread of its own. In a servlet
  application, a scheduled job may invoke the handler of one of GATool's
  `SyncToolSpecification` beans directly.

- A streamed call in-process. It runs under `MODE_INHERITABLETHREADLOCAL` with
  `spring.reactor.context-propagation=auto`, and propagation restores the
  right caller on Spring AI's worker thread.

- A thread started for the request in-process. The application started it
  under `MODE_INHERITABLETHREADLOCAL`, which is the use that strategy exists
  for. The new thread inherited the right caller.

- An application without a servlet request in-process. The application runs
  under `MODE_INHERITABLETHREADLOCAL`, and a message listener, for example,
  sets the security context itself before it calls a tool.

### Scopes per tool

An operation file lists every scope its tool needs. An empty list opens the
tool to every caller that holds the baseline scopes, the ones
`gatool.mcp.security.baseline-scopes` names for every call:

```graphql
# Deletes one review. Only a moderator may call this.
mutation DeleteReview($id: ID!) @gatool(scopes: ["reviews:moderate"]) {
  deleteReview(id: $id) { id }
}
```

A `tools/call` needs the baseline scopes and the tool's own scopes. The startup
listing, the list of tools GATool prints at INFO when the application starts,
names the effective set beside each tool, baseline first. For a file that
declares `[]` without a baseline, the listing prints `open to every caller`.
The tool list is built once at startup and served to every caller. `tools/list`
therefore shows every tool, with its name, description and input schema, to
every caller that holds the baseline scopes. A call to a tool whose scopes the
token lacks is refused with the `403` below. The tool's data is only reachable
through the call. A call without a token gets a `401`. Its `scope` parameter
names the tool's scopes beside the baseline, so a client that signs in for that
call asks for everything it needs. A caller without a token therefore learns,
from that `scope` parameter, which tools carry a scope list and which scopes
they need. The challenge names them so that a client can ask for them at
sign-in. A token that lacks a scope gets `403` with `insufficient_scope`. The
`scope` parameter of that challenge names what the call needs, together with
the scopes of this server the token already holds. A client that signs in again
therefore keeps what it had. The description says which of this server's scopes
the token held and which it lacked. A step-up is a second sign-in that asks for
more scopes. Two refusals of one caller for one missing scope carry the same
challenge. A client that compares them can therefore tell a step-up that can
succeed from one that cannot. Each `403` is logged at INFO with the tool, the
scopes the token held and lacked, and a correlation id. The correlation id is
the trace id where Micrometer Tracing runs, and an id of GATool's own
otherwise. A `401` is answered without a log line. A scope name is printable
ASCII without a space, a quote or a backslash. Startup stops on any other name.
A file without the argument needs the baseline alone. That suits an API that
sees the real user, through token exchange or through embedded mode. Embedded
mode is where the GraphQL API runs inside the same application through Spring
for GraphQL. An embedded call skips the servlet filter chain and every URL rule
on `spring.graphql.http.path`. A token that holds the baseline alone can
therefore run an operation that a `POST` to that path would refuse with `403`.
The controls on an embedded call are these scopes, the `WebGraphQlInterceptor`
beans, and method security in the resolvers. Startup warns while embedded mode
runs with Spring Security on the classpath. Under client credentials or a
static header, GATool sends a shared credential: one identity for every
caller. The API then sees GATool as every caller. Every MCP tool therefore has
to list its scopes, an empty list included. Startup stops and names the tools
without a list. The dynamic tools, the generated tools of `dynamic-three-step`,
take their list from
`gatool.dev.experimental.dynamic-operations.required-scopes`. Scopes derived
from the schema's own authorization directives arrive in a later release.

A client whose step-up fails can be served with
`gatool.mcp.security.unsafe.request-every-scope-at-sign-in`. That property
publishes every scope this server knows in the metadata and in every `401`.
MCP's security best practices call publishing every scope a common mistake, and
startup warns.

Over stdio GATool runs each tool call on the thread that reads stdin. The MCP
SDK writes every answer through one queue that takes one answer at a time. An
answer emitted beside another is dropped. That closes the transport and leaves
the server silent while its process stays up. An application that declares its
own `McpSyncServerCustomizer` keeps `immediateExecution(true)` in it. Startup
stops while that setting is left out.

Under stdio GATool sets `spring.main.web-application-type=none` and
`spring.main.banner-mode=off` while those properties are unset. The application
then starts without a web server. An application that serves pages or an
actuator of its own beside the stdio server sets
`spring.main.web-application-type=servlet`. Stdio stays the one MCP transport
there. The MCP endpoint is absent from the HTTP port, and both of GATool's
security chains stay out of the application. With Spring Security on the
classpath, Spring Boot's default chain secures the application's own paths.

Over stdio the caller is the process that started the server.
`gatool.mcp.stdio.granted-scopes`, set in the environment, names the scopes that
process holds. Startup stops while tools require scopes and the list is unset. A
call to a tool that needs a scope outside the list answers with a tool error
naming the scope. Console logging is off under stdio, because Spring Boot logs
to stdout, which is the JSON-RPC channel. `logging.console.enabled=true`
corrupts the stream. The log goes to stderr, the stream MCP leaves to a stdio
server for its logging. A host may capture, forward or ignore it. By default
stderr carries WARN and above. The lines of a normal start therefore stay out of
it. `logging.threshold.console` changes that. `INFO` adds the lines of the
start. `DEBUG`, together with a `logging.level` setting, adds the DEBUG lines of
that logger. `logging.pattern.console`, `logging.charset.console` and
`logging.structured.format.console` apply to stderr as they would to the
console. The log is written without making the server wait. A host that leaves
stderr unread loses lines once the pipe and the queue behind it are full, and
the server keeps answering. Only the log GATool attaches on Logback lets the
server keep answering in that way. Three other writers wait once the host has
left about 64 KB unread. They are a direct write to stderr, the log on
`java.util.logging`, and an application's own Logback or Log4j2 configuration
that writes to stderr. A host therefore reads stderr, or discards it when an
operator lowers the levels. GATool counts the lines it drops. The first line
that gets through again follows a line that holds the count, such as `GATool
dropped 412 log lines while stderr was not being read`. That line is written at
WARN under the logger `io.gatool.boot.mcp.autoconfigure.LogbackStderrLog`. A
threshold above WARN, or a level above WARN for that logger, keeps it out. A
host that reads again finds it ahead of the next line the server writes. Once
the server has stopped, the line sits at the end of the stream.
`logging.file.name` writes the log to a file as well, and that file receives
every line. `gatool.mcp.stdio.log-to-stderr=false` turns the stderr log off.
GATool steps back where the application configured the log itself. The
application does that by setting `logging.console.enabled`, by naming a file in
`logging.config`, or by bringing a Logback file under a name Spring Boot finds,
such as `logback-spring.xml`. The stderr log is built on Logback, Spring Boot's
default. On Log4j2 the console is off as well. It stays off where
`logging.threshold.console` is set, because Spring Boot's console would write
the log to stdout. The log is therefore written only in two cases: the
application sets `logging.file.name`, or its own Log4j2 configuration sends the
log to stderr. While `logging.file.name`, `logging.file.path` and
`logging.config` are all unset, startup says so in one line on stderr.
`gatool.mcp.stdio.log-to-stderr=false` keeps that line out. What Log4j2 reports
about itself, such as a log file it cannot create, is written to stderr. That
holds at either value of that property, because the listener Spring Boot
registers for those reports writes to stdout. On `java.util.logging` the log
reaches stderr through the console handler of the JDK, which Spring Boot leaves
on under `logging.console.enabled=false`. That handler carries INFO and above,
and the thread that logs writes it. A host that leaves stderr unread therefore
makes such a server wait once the pipe is full. While the console stays off, a
startup failure is still written to stderr. That write bypasses the queue. It
carries Boot's failure analysis, or the exception's message chain where every
analyzer passes on it. Beside a full pipe, GATool gives up the report after 2
seconds. The exit code is then the only signal.

The scopes a token holds are read from the `SCOPE_` authorities Spring's JWT
converter grants. An application that converts scopes under another prefix or
claim has every call refused until it keeps that prefix.

**Microsoft Entra ID.** Microsoft Entra ID writes the short name of a scope
into a token, such as `mcp.tools`, according to Microsoft's documentation. It
expects a client to ask for the qualified name, with the App ID URI in front of
it, such as `api://6e74172b/mcp.tools`. Setting
`spring.security.oauth2.resourceserver.jwt.authority-prefix` to
`SCOPE_api://<app-id>/` turns that short name into the authority
`SCOPE_api://<app-id>/mcp.tools`. That authority matches the qualified name a
baseline and an operation file's `@gatool(scopes:)` write with the same App ID
URI. The qualified names belong to one tenant. An adopter therefore keeps one
set of operation files for each environment.
`gatool.mcp.operations.locations` points at the set for the current
environment.

An application's own `@McpTool` methods keep Spring Security method security
once the application adds `@EnableMethodSecurity` itself. `@PreAuthorize` takes
effect on the MCP server's thread. A denied call comes back as a tool error,
without the `403` challenge above.

### Calling the API with a credential

`gatool.api.credentials.strategy` says how GATool authenticates to the GraphQL
API. While the property is unset, GATool calls the API without a credential.
That suits a local API and an internal one behind a gateway:

- `static-header` sends one header on every call, from
  `gatool.api.credentials.header-name` and `.header-value`, such as an API key.
  The value has to be one an HTTP client can send. A control character in the
  value stops startup, such as the line break a secret read from a file ends
  in. So does a space or a tab at the start or the end of the value. That stop
  names `gatool.api.credentials.header-name`, so the secret stays out of the
  message. A space inside the value, as in `Bearer <token>`, is fine.

- `client-credentials` obtains a token for GATool itself through a Spring Boot
  client registration named by `gatool.api.credentials.client-registration-id`.
  The API sees GATool as the caller of every tool. One token is held for the
  application until it expires.

- `token-exchange` hands the caller's token to the issuer with
  `gatool.api.credentials.audience`, `gatool.api.credentials.resource`, or both.
  The API receives a token issued for itself that still names the real user. The
  registration's grant type is
  `urn:ietf:params:oauth:grant-type:token-exchange`. GATool keeps one exchanged
  token per inbound token in memory, looked up by a hash of that token. It drops
  the expired ones each time it saves one, and holds at most ten thousand. Each
  entry holds the exchanged token's text, so a busy server holds tens of
  megabytes of tokens at that cap. A caller who steps up to a token with more
  scopes therefore gets an exchange of its own. The API sees the new scopes on
  the next call. A call on a thread without the caller's token, which is a call
  made outside the secured endpoint, fails as a tool error. The error names the
  strategy and says the arguments are not the cause. A call under the forwarded
  token fails the same way. So does a token request the issuer refuses, and that
  error carries the issuer's error code. The exchange names the caller's token
  as an access token. RFC 8693 gives that type to a token the receiving
  authorization server issued. The registration's token endpoint therefore has
  to belong to the issuer that signed the inbound token. The exchange described
  here is impersonation: the API sees the user alone. An application that wants
  delegation, where the API sees both the user and GATool, declares its own
  `ApiCredentialStrategy` bean. That bean wraps Spring Security's
  `TokenExchangeOAuth2AuthorizedClientProvider` with an actor token.

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          movies:
            client-id: gatool-server
            client-secret: ${MOVIES_CLIENT_SECRET}
            authorization-grant-type: urn:ietf:params:oauth:grant-type:token-exchange
            provider: issuer
        provider:
          issuer:
            token-uri: https://issuer.example.com/realms/movies/protocol/openid-connect/token
gatool:
  api:
    credentials:
      strategy: token-exchange
      client-registration-id: movies
      audience: movies-api
```

The two OAuth2 strategies need `spring-boot-starter-security-oauth2-client`.
Startup stops when the starter is missing, when the registration is missing, or
when the grant type does not match the strategy. The message names the missing
or wrong item. Token exchange and the forwarded token need an authenticated
caller. Both therefore stop startup over stdio, and while
the unauthenticated switch is on. `ApiCredentialStrategy` is a Spring
`ClientHttpRequestInterceptor` under another name. A bean of that type replaces
every built-in strategy. Use it for an API that authenticates in another way.

The test suite runs the whole chain against Keycloak 26.7.3 in a container. A
user signs in. The endpoint accepts the realm's tokens. It refuses a missing
scope until the client steps up. The movie API receives the exchanged token and
the client credentials token. The tests skip themselves where Docker is absent.

## Untrusted content and prompt injection

A model reads the text a tool publishes and the text a tool returns, and it
may follow an instruction it finds in either. The table lists the text that
reaches a model through GATool and who writes it:

| What a model reads | Who writes it |
| --- | --- |
| Tool names, titles and descriptions | The operation files in your repository. A file without a comment takes its description from the schema. Under `gatool.dev.experimental.generate-tools` the schema names and describes every generated tool |
| The input schema, and the output schema where a tool publishes one | The variables of the operation file, with the descriptions, enum values, defaults and deprecation reasons of the schema, and the scalar fragments of your configuration. A scalar fragment is a piece of JSON Schema configured under `scalar-schemas` |
| Results | The API. A result holds the data the operation selected. Part of that data is text the users of the API wrote, such as the comment of a review |
| Error text | The API: the `errors` of a GraphQL response, and the first 2,000 characters of the body of a refused call |
| The Model Context Protocol (MCP) `instructions` | GATool, as a fixed text, or your `spring.ai.mcp.server.instructions` |

The owner of the API writes the schema. A schema fetched from a registry is
written by whoever can publish to that registry. Access to the registry
therefore decides what a model reads as tool text.

**What GATool does.**

- A tool sends the document its operation file holds. The model chooses a tool
  and writes its arguments. The arguments travel as GraphQL variables beside
  the document, so GraphQL text inside an argument stays a value.
  `executeGraphql` is the one tool through which a model writes a document of
  its own. It is one of the dynamic tools, present while
  `gatool.dev.experimental.generate-tools` is `dynamic-three-step`.

- A result is written again as `data` and `errors`. Its strings stay as the API
  returned them. GATool replaces the value of
  `gatool.api.credentials.header-value` wherever a result or an error text
  holds it. It also replaces a bearer token in the `errors` and in the body of
  a refused call. An application's own `ApiCredentialStrategy` bean may send
  another header, such as an API key under its own name. Both rules leave that
  header alone. An API or a proxy that echoes the request into an error then
  hands the value to the model. Such a strategy therefore needs an API that
  keeps request headers out of its errors.

- `gatool.api.max-response-size` bounds what GATool reads from the API.
  `gatool.results.max-characters` bounds the text one call returns to a model.

- The default folder of the schema cache is checked before it is used.
  "Reading the schema from a registry" describes the check.

- On the MCP endpoint a call needs the scopes its operation file lists. The
  token of a caller therefore bounds the tools an injected instruction can
  reach.

- A mutation is a tool like a query. Every tool publishes `readOnlyHint` and
  `destructiveHint`. A query carries `readOnlyHint: true`, and a mutation
  carries `destructiveHint: true`, so a client can tell the two apart.

**What GATool leaves to the client and the application.**

- GATool does not look for instructions in a result, in an error text or in the
  schema. The client or the application that holds the model treats what a
  tool returns as untrusted input. Scopes bound what such text can cause. Give
  the tools that write a scope of their own. Leave that scope out of the token
  of a caller whose model reads user-written text.

- An error's `extensions` reach the model as the API wrote them. Some APIs put
  an exception class or the lines of a stack trace there. The configuration of
  the API is where a team switches that off.

- GATool does not ask a person before a mutation runs. Over MCP that question
  belongs to the client. MCP asks a client to keep a person in the loop and to
  confirm a sensitive operation. The hints above tell a client which tools
  write.

- In process, Spring AI's `ToolCallingManager` calls the callback that a
  model's response names. A mutation in the in-process folder,
  `gatool/in-process/`, therefore runs as soon as the model asks for it. Spring
  AI's `ToolMetadata` carries `returnDirect` alone. The application reads which
  tools write from the catalog. `GAToolCatalog.inProcessTools()` lists each
  tool with `readOnly()`. An application that wants a person to agree first
  wraps the callbacks of those tools in a `ToolCallback` of its own. That
  callback asks the person, then hands the call to GATool's callback.

## Checking your tools in CI

The starter `gatool-spring-boot-starter-test` brings two assertions. Add it in
test scope beside `spring-boot-starter-test`:

```xml
<dependency>
    <groupId>io.gatool</groupId>
    <artifactId>gatool-spring-boot-starter-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

**The operation files validate.** This assertion is the CI check, the
`OperationFilesAssert` test. It runs the checks that startup runs on the
operation files and the schema. It runs from a plain JUnit test, without an
issuer, a running application or a reachable schema URL. A broken file
therefore fails the build that changed it. Five stops depend on the rest of
the configuration, so they run at startup alone:

- A tool name that clashes with one of the dynamic tools, the tools generated
  when `gatool.dev.experimental.generate-tools` is set to `dynamic-three-step`.

- A tool name that clashes with a tool the application declares.

- The scope rules under a shared credential, which is client credentials or a
  static header, one identity for every caller.

- The scopes of `gatool.mcp.stdio.granted-scopes`.

- An image mime type beside an output schema.

A test that starts the context covers those five:

```java
@Test
void operationFiles_shouldValidate() {
    OperationFilesAssert.assertValid(Path.of("src/main/resources/movies.graphqls"),
            List.of(Path.of("src/main/resources/gatool/mcp")), List.of(), CheckSettings.defaults());
}
```

The second argument lists the folders of operation files served over the
Model Context Protocol (MCP), the MCP folders. The third lists the folders of
operation files served in process, the in-process folders.
The CI check reads each folder under that folder's own rules. An application
with only one of the two passes `List.of()` for the other. A listed folder that
does not exist fails the assertion, and the message names the folder. A renamed
folder therefore cannot pass with zero tools. The CI check follows a symbolic
link to a folder. It lists the tools in path order, the way startup reads a
location. The fourth argument is a `CheckSettings`. It carries the naming
strategy, the output schema switch and the scalar fragments the application
configures. A scalar fragment is a piece of JSON Schema configured under
`scalar-schemas`. The CI check then runs under the same settings startup uses.
An application on every default passes `CheckSettings.defaults()`. One with
`gatool.naming.strategy: snake-case` passes
`CheckSettings.defaults().withNamingStrategy(ToolNamingStrategy.snakeCase())`.
One that publishes output schemas or declares scalar fragments builds its
settings with the other `with...` methods.

**The tool contract matches its snapshot.** A tool name is what the model calls
and what a client configuration records. A renamed tool or a changed input
schema therefore shows up in review as a changed file:

```java
@SpringBootTest
class ToolContractTests {

    @Autowired
    GAToolCatalog catalog;

    @Test
    void tools_shouldMatchTheSnapshot() {
        ToolContractSnapshot.of(catalog).assertMatches(Path.of("src/test/resources/gatool-contract.json"));
    }
}
```

When the snapshot is missing, the test writes it and then fails, asking for the
file to be committed. A run in CI without the file is therefore a failure. A
deliberate change is accepted with `-Dgatool.snapshot.update=true`. That flag
rewrites the file for review. The snapshot records each tool's scopes beside
its schemas, because a changed scope list is a contract change a client
notices. The file is written with line feeds on every platform. The path is
resolved against the JVM's working directory. Under Maven, Gradle and the IDEs
that is the module directory. Under Gradle the flag reaches the test JVM
through `systemProperty` on the test task.

## Running in a container

GATool has two response caps, `gatool.api.max-response-size` and
`gatool.results.max-characters`. The first decides the memory a call holds. The
figures
below were measured with `gatool.api.max-response-size` raised to 10MB. One
refused 9 MB result allocates about 87 to 111 MiB. It holds about 120 MiB of
heap while in flight. The body is read, parsed and written as text before
`gatool.results.max-characters` refuses it. Eight concurrent 9 MB responses
peaked at 1,088 to 1,331 MiB. `gatool.api.max-response-size=256KB` brings one
such call to about 10 MiB. JDK 21 in a 512 MiB container with two CPUs picks
SerialGC with a 128 MiB heap. One 9 MB response under a cap raised to 10MB
fills that heap.

The default of `gatool.api.max-response-size` is 1MB. That is the measured
safe point for a 512 MiB
container. The measurement used `MALLOC_ARENA_MAX=2` in the environment and
`-XX:MaxRAMPercentage=60`. It reached 350 MiB RSS for 64 concurrent 1 MB
results. Add `-XX:+ExitOnOutOfMemoryError`, because an `OutOfMemoryError` on a
Tomcat or client thread leaves the health endpoint answering `UP` while calls
fail. Heap per call in flight is about ten times `max-response-size` during the
parse. Multiply that by the concurrency the deployment allows. Tomcat allows
200 threads by default. The 350 MiB figure above was measured at 64 concurrent
calls. A deployment on Tomcat's 200 threads lowers `server.tomcat.threads.max`
to stay near that figure. GATool does not limit concurrent calls in this
release, so the deployment bounds them with `server.tomcat.threads.max` or at
the gateway.

`gatool.api.schema.max-size` bounds the schema fetched from a URL, at 10MB by
default. A schema of a few megabytes therefore loads while
`gatool.api.max-response-size` stays at the size the heap allows.

On the stateful transport of the Model Context Protocol (MCP), a connected
client holds a listening stream open. Spring Boot's graceful shutdown counts
that stream as an active request. GATool ends the sessions before that shutdown
begins. A stop with connected clients therefore does not wait out
`spring.lifecycle.timeout-per-shutdown-phase`. A client that was connected
opens a new session against the next instance.

## What this release does

- Tools from query and mutation operations. A query publishes `readOnlyHint`
  and `idempotentHint` as true and `destructiveHint` as false. A mutation
  publishes the three the other way round, which are the defaults of the Model
  Context Protocol (MCP).

- Streamable HTTP, stateless by default and stateful with
  `spring.ai.mcp.server.protocol: STREAMABLE`. On the stateful transport,
  `gatool.mcp.sessions.max-count` and `gatool.mcp.sessions.idle-timeout` bound
  the sessions the server keeps. Also stdio, for a client that runs on the same
  machine.

- An OAuth 2.1 resource server on the MCP endpoint, built with Spring Security.
  The scopes each operation file lists are checked on every call and named in
  every challenge. The endpoint runs unsecured only behind
  `gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication`, the
  unauthenticated switch.

- A static header, client credentials or token exchange towards the GraphQL
  API, proved against Keycloak.

- Both embedded mode, where the GraphQL API runs inside the same application
  through Spring for GraphQL, and a remote API over HTTP.

- On the stateful transport, GATool stops startup where the MCP server bean
  was built without immediate execution. It also refuses a tool call that
  arrives off the request thread. Both are described under
  [The caller and the request thread](#the-caller-and-the-request-thread).

## Compatibility

GATool is at 0.x. Until 1.0 these rules hold:

- A patch release, such as 0.1.1 after 0.1.0, publishes the same tools for the
  same schema and operation files. It keeps the public Java API binary
  compatible. The exceptions are a security fix and a fix that the Model
  Context Protocol (MCP) or GraphQL requires. The release note names each one.

- A minor release, such as 0.2.0, may change the tool contract, the public Java
  API, a property or a default. Its release note lists every such change.

- Everything under `gatool.dev.experimental` may change in any release.

The tool contract is what the model and its client see. It covers the name,
title, description, input schema, output schema and annotations of each tool.
It also covers the shape of a tool result, and the name and tags of the
[`gatool.call` observation](#configuration).

The public Java API is every public type in these packages:

| Module | Packages |
| --- | --- |
| `gatool-core` | `io.gatool.core.check`, `io.gatool.core.model`, `io.gatool.core.naming`, `io.gatool.core.search` |
| `gatool-spring-boot` | `io.gatool.boot`, `io.gatool.boot.autoconfigure`, `io.gatool.boot.execution` |
| `gatool-mcp-spring-boot` | `io.gatool.boot.mcp.autoconfigure`, `io.gatool.boot.mcp.limit`, `io.gatool.boot.mcp.security` |
| `gatool-in-process-spring-boot` | `io.gatool.boot.inprocess`, `io.gatool.boot.inprocess.autoconfigure` |
| `gatool-spring-boot-test` | `io.gatool.boot.test` |

A package with `internal` in its name may change in any release. The published
Javadoc leaves those packages out.

`GAToolProperties` and the three classes beside it (`GAToolApiProperties`,
`GAToolMcpProperties` and `GAToolDevProperties`) have a narrower promise than
the rest of those packages. Their contract is the properties themselves: the
names, types and defaults under `gatool.*`. The rules above cover those. Their
getters and setters exist for Spring Boot's binder and may change in any
release. Spring Boot states the same rule for [its own properties
classes](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.java-bean-binding).

An application replaces a part of GATool by publishing a bean of its type:

| Bean type | What it replaces |
| --- | --- |
| `GraphQlExecutor` | How a document reaches the GraphQL API |
| `ApiCredentialStrategy` | The credential GATool sends to the API |
| `ToolNamingStrategy` | How an operation name becomes a tool name |
| `ToolRateLimiter` | The rate limit in front of an MCP tool call |
| `SchemaSearch` | The ranking behind `searchSchema` |
| `GAToolCatalog` | The tools themselves, built in Java. GATool then leaves its executor unbuilt, so `gatool.api.url` may stay unset |

A public record may gain a component in a minor release. The constructor of
the release before stays for at least one minor release. Code that takes a
record apart with a record pattern changes with the record.

## Requirements

GATool needs Java 21 or newer, Spring Boot 4.1.x and Spring AI 2.0.x from
2.0.1. Spring AI 2.0.1 fixes CVE-2026-59279 and CVE-2026-59318. The
auto-configuration is compiled against Spring Boot 4.1 and Spring AI 2.0 APIs,
so Spring Boot 4.0.x and Spring AI 1.x are unsupported. Startup warns where the
application runs a Spring Boot or Spring AI line this release did not test. The
warning names the tested lines and the versions found. The Model Context
Protocol (MCP) starter needs Spring MVC, which means a servlet web application.
Every guard in front of the endpoint is a servlet filter. With both stacks
present, Boot runs the servlet stack, and GATool serves MCP there. Startup
warns and names the `WebFilter`, `SecurityWebFilterChain` and reactive
`RouterFunction` beans that stay unserved. With
`spring.main.web-application-type=reactive` set, startup stops. The message
says so and points at the in-process starter. That starter's tools run without
those filters. The endpoint reads JWT access tokens only. The build runs on
Java 21 and Java 25, and the release build runs on Java 21.

**This release runs on the JVM.** GATool has yet to be run in a GraalVM native
image. The starters ship without runtime hints for the operation folders. With
`gatool.dev.experimental.generate-tools` set to `dynamic-three-step`, which
generates the dynamic tools, startup stops inside a native image and names the
property. Spring Boot documents a faster start on the JVM under [AOT Cache and
CDS](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html).
That page covers the AOT cache on Java 25 and class data sharing on Java 21.

**Tomcat's version comes from your build.** The MCP starter brings Tomcat
through Spring AI's WebMVC server starter. It leaves the Tomcat version to the
Spring Boot release your application builds on. An application on Spring Boot
4.1.1 therefore runs Tomcat 11.0.24. Three advisories affect that version:
GHSA-9xv2-5v5q-p794 in the DIGEST authenticator, GHSA-h3x4-894j-xpx5 in the FORM
authenticator, and GHSA-gcx9-497g-6cp6 in access control. Tomcat 11.0.25 fixes
all three. GATool's own build and tests run on Tomcat 11.0.26. The SBOM of a
release lists 11.0.26, because it records what GATool was built with. Your
application's dependency tree shows the version it runs:

```bash
./mvnw dependency:tree -Dincludes=org.apache.tomcat.embed
```

Until a Spring Boot release manages Tomcat 11.0.25 or later, set the version in
your own build. With `spring-boot-starter-parent` as the parent, one property
sets it:

```xml
<properties>
    <tomcat.version>11.0.26</tomcat.version>
</properties>
```

With `spring-boot-dependencies` imported as a BOM, manage `tomcat-embed-core`,
`tomcat-embed-el` and `tomcat-embed-websocket` at 11.0.26 ahead of the import.
Maven resolves an imported POM's properties against that POM. A property in
your build does not reach it. With Gradle and Spring's dependency management
plugin, `ext['tomcat.version'] = '11.0.26'` sets it.

## Standards

GATool serves two revisions of the Model Context Protocol (MCP): 2025-11-25
and 2025-06-18. It targets the transport rules of 2025-11-25. The compliance
filter, the
`gaToolMcpComplianceFilter` bean on the MCP endpoint, applies the transport
rules that the MCP Java SDK and Spring AI leave open. It checks the `Origin`
header and the protocol version, and caps the request body. It also refuses a
body it cannot read the way the server would. The refused bodies are:

- A body declared in a charset other than UTF-8.

- A media type other than JSON, or one that carries a wildcard.

- A body that fails to parse, or one that is not a JSON object.

- An id other than a string or an integer.

- A method that is not a string.

- A `jsonrpc` member that is missing or other than `"2.0"`.

- A `params` that is not an object.

- A `tools/call` whose `arguments` is not an object.

- A JSON-RPC batch.

The `Origin` check runs before the body is read. So does the answer to an HTTP
method the transport does not route. Those methods are `PUT` on both transports
and `DELETE` on the stateless one. The answer is `405`, with an `Allow` header
and a JSON-RPC error. The compliance filter passes a request without
`MCP-Protocol-Version` through, and the SDK serves it at a revision this release
supports. The specification says the server should assume 2025-03-26. That
revision requires JSON-RPC batches, which this release cannot read, so assuming
it would refuse every such call. An explicit `2025-03-26` header is refused with
`400`, unless `gatool.mcp.security.unsafe.allow-superseded-mcp-revisions` adds
that revision back. A `tools/call` that names a tool outside printable ASCII is
refused as well. The refusal travels inside a `200`, so that the client keeps
its session. Such a name could otherwise reach a challenge header. The scope
check and the server therefore always read the same message.

The current MCP revision is
[2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/changelog).
It removes the `initialize` handshake and sessions, and it requires
`server/discover`. This release does not implement it. In that revision's terms,
GATool is a legacy server. The revision's [compatibility
matrix](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning#compatibility-matrix)
gives the outcome for each client. A client that supports both revisions falls
back to `initialize` and works. GATool answers an `initialize` that asks for
`2026-07-28` with `2025-11-25`. It answers any other request carrying that
revision with `400` and a JSON-RPC error. A client that supports 2026-07-28
alone cannot connect.

When an application declares its own `McpStatelessServerTransport` or
`McpStreamableServerTransportProvider` bean, the server is built on that
transport. GATool's own transport backs off. Such a transport answers the
handshake from its own revision list. The compliance filter still refuses a
superseded revision on every request.

Two deviations are inherited from Spring AI 2.0.1 and MCP Java SDK 2.0.0. Every
SSE event id on a `POST` response stream carries the session id. The
specification asks for an id that is unique across the session. On the stateful
transport, a request for a method the server does not serve gets its `-32601`
as an SSE event. An example is `resources/list` on a server without the
resources capability. The stream then stays open. The specification says the
server should end it. A client therefore waits until its own timeout. Both are
SDK behaviour. A test pins the second until the SDK closes the stream. Two more
are inherited the same way. A `Last-Event-ID` header opens a stream without a
replay, so resumability is unsupported. The SDK does not send the priming SSE
event the specification recommends. GATool normalises the `Accept` header ahead
of the transports. It drops q-values. It reads `*/*` or a missing header as the
two media types MCP names. Only a client whose header leaves both types out is
refused.

GATool leaves the pagination rule to the SDK. Every `tools/list` answer holds
the whole list and leaves `nextCursor` out. MCP's rule says a server should
refuse a cursor with -32602. MCP Java SDK 2.0.0 implements pagination in its
schema and in its client, and leaves the server half out. The compliance filter
is the only place where GATool could add such a rule. A rule in the filter
would apply over HTTP alone, while GATool serves stdio as well. A client that
sends a cursor reads every tool either way.

Every build runs the official [MCP conformance
suite](https://www.npmjs.com/package/@modelcontextprotocol/conformance), version
0.2.0-alpha.11, against a fixture application. It runs with `--requirements
2025-11-25`, once in stateless mode and once in stateful mode. This release
claims the tool contract and the transport rules. These scenarios pass in both
modes:

`server-initialize`, `ping`, `tools-list`, `tools-call-simple-text`,
`tools-call-image`, `tools-call-audio`, `tools-call-embedded-resource`,
`tools-call-mixed-content`, `tools-call-error` and `dns-rebinding-protection`.

The stateful run also passes `logging-set-level`, `tools-call-with-logging`,
`tools-call-with-progress`, `server-sse-multiple-streams` and the suite's own
`server-session-lifecycle`. Those pass there because a session lets the server
reach the client.

The suite counts a baselined failure as a failure. The file
[conformance-baseline.yml](conformance-baseline.yml) therefore lists all twenty
scenarios that the stateless run leaves out, each with its reason. The file
[conformance-baseline-stateful.yml](conformance-baseline-stateful.yml) lists
the twelve of the stateful run the same way. They fall into three groups:

- **Resources, prompts and completions.** GATool turns GraphQL operation files
  into tools. An application declares its own resources and prompts when it
  wants them. The fixture therefore declares tools alone.

- **Sampling, elicitation and multiple streams.** The first two ask the server
  to call the client. The third wants several streams open in one session. A
  stateless server answers a request and closes. Spring AI 2.0.1 leaves a tool
  that takes a request context unregistered there. The stateful run passes all
  of them.

- **Progress, logging and `logging/setLevel`, in stateless mode.** Spring AI
  2.0.1 skips a tool method that takes a request context on a stateless server.
  The stateless server answers `logging/setLevel` with "Missing handler for
  request type". All three pass in stateful mode.

A stateful server keeps a session until its client deletes it or the application
stops. The server also evicts a session after `gatool.mcp.sessions.idle-timeout`
of silence. It keeps at most `gatool.mcp.sessions.max-count` at once. It
answers `503` to a client that initializes past the cap. Spring AI's own
auto-configuration leaves both settings at the transport's defaults, which are
100,000 sessions without eviction. GATool therefore builds the transport bean
itself. Startup warns while a stateful server runs without authentication,
because any caller can then fill the cap.

That cap counts the whole server, so one caller can fill it and leave every
other caller with `503`. Authentication bounds who may do it and makes the
denial attributable. One authenticated caller can still fill the cap. Set
`gatool.mcp.sessions.max-count-per-caller` to bound what one caller takes. A
caller past their own cap reads `429` with `Retry-After` and the wait, the way
the rate limiter answers. The server cap keeps answering `503`, because that cap
is about the server. The per-caller cap defaults to 100, a tenth of the server
cap. Zero or below turns it off. A deployment whose workers share one service
principal sets it to the number of sessions those workers hold. A secured
stateful server may leave the per-caller cap off, or set it at or above
`gatool.mcp.sessions.max-count`. Either setting leaves one caller free to fill
the whole server cap. Startup then warns and names both properties. One MCP
client normally holds one session. A caller that reconnects may briefly hold
two. A small number therefore suits a deployment where each caller runs one
client.

### Taking the endpoint's security over

Excluding `GAToolMcpSecurityAutoConfiguration` removes the one place that
applies `McpServerSecurityConfigurer`. The per-tool scope check goes with it.
Spring Boot's own resource server chain then serves the endpoint under a rule
that asks for a valid token alone. Any token for the audience then calls every
tool, the scoped ones included. Startup stops and says so. The same stop
applies when Spring Security's filter is absent because Boot's web security
auto-configuration was excluded too. The endpoint would then answer every
caller.

An application that wants to own the endpoint's security declares a chain of
its own and applies the configurer in it. GATool supports that set-up, and the
application starts normally:

```java
@Bean
@Order(Ordered.HIGHEST_PRECEDENCE)
SecurityFilterChain mcpChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource/mcp")
        .with(McpServerSecurityConfigurer.mcpServer(), withDefaults())
        .build();
}
```

## Contributing and security

Pull requests are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) explains the
sign-off and the build. To report a vulnerability, follow
[SECURITY.md](SECURITY.md).

## Licence

[Apache License 2.0](LICENSE).
