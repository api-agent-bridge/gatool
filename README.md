# GATool

Spring Boot starters that turn trusted GraphQL documents into tools an AI
agent can call, over MCP or in-process through Spring AI.

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

The operation name becomes the tool name; the comment above it becomes the
description an agent reads, and the variables become the tool's input schema. An
agent calls the tool with arguments, GATool sends this document to your GraphQL
API, and the `data` and `errors` of the JSON response go back as the tool result,
written again by GATool: `extensions` and any other top-level key stay out, and a
number reaches the model with every digit the API sent.

The operations are trusted documents: the repository holds the list of queries
that exist, a pull request reviews each one, and the agent chooses from that
list. The schema stays out of the agent's context, and the API receives
only the documents in the repository, each one as graphql-java prints it, without
its comments. `CheckedTool.document()` in the CI check returns the text GATool
sends. The advice on what to hash waits for the release that sends persisted
documents.

## Getting started

Add the MCP starter:

```xml
<dependency>
    <groupId>io.gatool</groupId>
    <artifactId>gatool-mcp-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Put the query above in `src/main/resources/gatool/mcp/TopRatedMovies.graphql`, and
point the application at your API:

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

The schema file is what GATool validates every operation against at startup. A
query that names a field the schema lacks stops startup with a message naming
the file and the field. It fails the build as well once the CI assertion under
"The operation files validate" is in place, and a test that starts the context
fails the same way.

GATool builds the schema with graphql-java, which refuses one schema that the
GraphQL specification allows: a type that gives an argument another default
than its interface declares, such as `memberOf(limit: Int! = 150)` under an
interface that declares `limit: Int! = 100`. Startup stops and names each such
argument, and
[graphql-java#4480](https://github.com/graphql-java/graphql-java/issues/4480)
asks graphql-java to build such a schema. Until a release does, a copy of the
schema that gives those arguments the default of their interface starts, and
GATool then describes that default where the API applies its own.

Without Spring Security on the classpath, that switch is required, and the
application logs a warning at every startup while it is on. Keep the endpoint on
a loopback address with `server.address`, or behind a gateway that authenticates
for it. The section on securing the endpoint below shows the other way: add the
resource server starter, name the issuer, and leave the switch off.

Start the application, and an agent that connects to `/mcp` reads one tool. The
entry is shortened here: the one on the wire also carries a `title`, the
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

The second starter hands the same operation files to a `ChatClient`, without an
MCP server:

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
`spring-ai-starter-model-anthropic`, which the application adds beside this
one.

Files under `gatool/in-process/` reach the `ChatClient`, and files under `gatool/mcp/`
reach the MCP server, so one application can serve both with different lists.
An in-process tool runs inside the application, so the scopes an operation file
lists, the rate limiter and Spring AI's `ToolContext` stay out of its path: the
tool takes its arguments from the text alone, and the caller is the application
itself. Under `token-exchange` and under the forwarded token, the caller is
the user on the thread that calls the tool, because both strategies read the
token from that thread's security context. Under a per-caller credential,
GATool refuses a call where the thread's security context may belong to
another caller, described under
[The caller and the request thread](#the-caller-and-the-request-thread). The
arguments travel to the API as the model wrote them: an argument the input
schema does not name is forwarded
as sent, and one left out stays out, so a misspelled name gets the API's answer
for the unnamed argument, its default or its own error, where the MCP path
refuses the call before the API sees it. A later minor release may validate
arguments before the call, as the MCP side does. A scope list on such a file
states what the application has to enforce, with method security on the
resolver or in the code that builds the `ChatClient`, and the startup listing
says so beside the tool. Spring AI's own `spring.ai.tool` observation covers
each call. A refusal, such as a credential the configured strategy could not
supply, reaches the model as a plain sentence of text, where an answer arrives
as the same GraphQL envelope the MCP path returns.

## How a tool is built

- **One file makes one tool.** An operation file holds one operation and the
  fragments it spreads, and a file that holds a second operation stops startup.
  A file that holds a subscription, or that uses `@defer` or `@stream`, stops
  startup as well, because a tool call returns one result.
- **The name** comes from the operation name through a naming strategy, and
  `camelCase` is the default. `@gatool(name: "movies_top_rated")` sets an
  explicit name, and a `ToolNamingStrategy` bean replaces the strategy. An
  operation written without a name takes one from its file, so
  `top-rated-movies.graphql` and `TopRatedMovies.graphql` both give
  `topRatedMovies`. Under `as-written`, the file's own case is kept, and a run
  of separators becomes one underscore, so the first file gives
  `top_rated_movies`. An operation written without a name, in a file whose
  name holds a letter outside ASCII such as `über-filme.graphql`, is refused on
  every platform, whether the file system hands the name over composed or
  decomposed. Name the operation in the file, and the file keeps its name. An explicit name follows the same rules as a strategy's:
  letters, digits, `_` and `-`, at most 64 characters, at least one letter or
  digit.
- **The description** comes from the comment directly above the operation.
  Without one, it comes from the schema description of the root field the
  operation selects, and startup warns when both are silent. A comment or a
  schema description made of characters that render empty, such as a no-break
  space, counts as silent. Where the schema marks that root field `@deprecated`,
  the description ends with the deprecation and the schema's reason, as in
  `Lists every movie. Deprecated: Use movies.`, so a model reads that the API is
  removing what the tool calls. The sentence follows the comment as it follows
  the schema description, and a generated tool carries the same one. A
  deprecated field deeper in the selection, and one root field of several,
  reach the startup warning alone. A description written as a string ahead of
  the operation, which the September 2025 edition of GraphQL allows, is refused
  at startup, because graphql-java 25.0 predates that syntax; the refusal says
  so and points at the `#` lines.
- **The input schema** comes from the variables, with their types, their
  descriptions and their defaults. An `ID` argument accepts a string or an
  integer, which is what GraphQL's own input coercion takes, and an `ID` in a
  result is a string, which is what a conforming service serializes. A default
  is published as the JSON Schema `default` keyword, coerced the way GraphQL
  coerces it, so a model reads what it gets by leaving the argument out. A whole
  number keeps every digit the file wrote, so a `Long` default past 64 bits
  publishes as those digits, and a default that fails the property beside it is
  left out and named at startup. A nullable variable that fills a `@oneOf` input
  field through a nullable argument or input field is refused even with a
  default; declare it non-null. The September 2025 specification, section 5.8.5,
  allows that default; graphql-js 17 refuses it in that position, and GATool
  sides with graphql-js.
- **The title** comes from `@gatool(title: "Top rated movies")`, and a blank
  title is refused: one that is empty, and one made of spaces or of characters
  that render empty, such as a no-break space or a zero-width space.
- **The open world hint** comes from `@gatool(openWorld: true)` or
  `@gatool(openWorld: false)`, which sets `openWorldHint` on the MCP tool
  definition; a file that leaves it out publishes the tool without the hint.
- **The result** is the `data` and `errors` of the GraphQL response as JSON
  text, written again by GATool, so the response-level `extensions` and any
  other top-level key stay out, while an error's own `extensions` travel with
  the error. A `294 Partial Success`, or any 2xx under a JSON content type, is
  read the same way as a `200`. A GraphQL error becomes a tool error that the
  model can read and act on.
- **A query carries `readOnlyHint`, and a mutation carries `destructiveHint`.**
  A mutation file becomes a tool the same way a query file does, and the
  operation type is what tells a client which it is.
- **The output schema** is off by default. `gatool.results.publish-output-schema: true`
  turns it on for every tool, and `@gatool(outputSchema: true)` or
  `@gatool(outputSchema: false)` overrides that for one operation either way,
  so an application can publish for the tools it trusts and hold back for one
  awkward operation.

Every `@gatool` problem names the line and column of the argument it is about,
and an empty or comment-only operation file is reported as empty. An operation
file, a shared fragment file and the schema file read the same with `\n`, `\r\n`
or a lone `\r` ending each line, so a block string holds one value, and a tool
publishes one contract, on a Windows checkout and on a Linux one. The three are
the operator's own text, so they are read however long they are, and "The size
of a document" below says which limits remain and which the API sets.
`gatool-core/src/main/resources/io/gatool/core/gatool-directives.graphqls` is
the SDL of the `@gatool` directive, for an editor's completion and a schema
linter; GATool reads the directive from the operation file itself, and the SDL
file serves editors alone.

### What the input schema checks

The MCP SDK validates the arguments of a call against the published input
schema before the tool runs, and the API coerces the values that reach it. The
table lists the positions where the two answer differently for one value:

| Position | What the schema does | What the API does |
| --- | --- | --- |
| A Non-Null custom scalar published as `{}` | Accepts `null`, because the empty schema leaves every type open, `null` included. The variable stays in `required`, so a call that leaves it out is refused | Refuses the `null` with a request error, which the model reads as the tool error |
| A list, such as `[Int!]!` | Refuses the single value `1999`, because the property says `array` | Takes `1999` as `[1999]`, which is GraphQL's input coercion for a list. A model sends the list, because the schema is what it reads |
| An input type inside another of its own kind, and one past the node budget | Accepts a field the type lacks, because the object there is published with a description and without its fields | Refuses the field with a request error, which the model reads as the tool error |

A custom scalar is published as `{}` until a fragment, or a specification
GATool has read, describes it. A fragment that names a `type` closes the
position: the Non-Null variable then publishes that type without a null branch,
and "Custom scalars" below shows how to write one. The third row covers two
cases. A type that holds another of its own kind, such as a filter with `and`
and `or`, lists its fields and closes the object where it first appears, and
the one inside it is published open. One variable expands at most 500 input
objects, and a position past that budget is published open as well, with a
startup warning that names it. An in-process tool sends its arguments without
this validation, so the API answers for every row there.

A variable typed as a generated filter, the kind Hasura and PostGraphile
publish, costs more than its one line in the operation suggests. One `where`
variable over eight tables with three relationships publishes an input schema
of about 100,000 characters, and `tools/list` carries it to every client.
Startup warns about a tool with more than 4,000 estimated tokens and names it. The way
out is to declare the scalar variables the tool needs and to write the filter
as a literal in the operation. The budget of 500 input objects and the text
published past it may change in a later release.

The schemas GATool writes are built for function calling without OpenAI's
strict mode. Two positions fail OpenAI's rules for a tool under `strict: true`:
an optional property, since strict mode requires every property in `required`,
and a custom scalar published as `{}`, since strict mode requires a `type` on
every property. A fragment under `gatool.inputs.scalar-schemas` closes the
second position. The first stays open for a later release.

### What the output schema promises

MCP makes an output schema optional and binding: a server that publishes one
must return structured content that conforms to it on every call. GATool describes
the GraphQL response envelope, and the structured content is read back from the
written text, so the two halves carry the same digits and nulls in embedded mode
as well as remote. Where the selection set fails to prove a shape, the schema
stays permissive:

| Case | What the schema says |
| --- | --- |
| `data` | Nullable, because a failed call answers with `data: null` |
| Any selected field | Present in `required`, and its value nullable |
| A field under `@skip` or `@include` | Out of `required`, since the model decides whether it arrives |
| A field inside a fragment with a type condition | Out of `required`, since another concrete type leaves it out. A condition naming the surrounding type, an interface it implements or a union holding it keeps its fields in `required`, because it holds for every object that can answer there |
| An enum | `string`, without the value list, so a value the API adds before the schema file catches up still conforms |
| A union or an interface | One branch per member the operation's type conditions cover, plus an open branch and `null` |
| A custom scalar | The `type` and the `format` of its fragment under `gatool.inputs.scalar-schemas`, or its entry under `gatool.results.scalar-schemas` as written. A scalar without an entry in either map publishes the `type` and the `format` of the specification its `@specifiedBy` URL cites, where GATool has read it, and otherwise the empty schema, which accepts any JSON value |
| `errors` | The GraphQL shape, an array of objects whose `message`, `locations`, `path` and `extensions` may each be null or left out, then `null`, then an open branch that accepts any JSON value. An API or a gateway in front of it writes other shapes, such as an entry without `message`, an entry that is a plain string, or one object where the list belongs, and each of them conforms |

A response that carries errors beside its data conforms as well, so it arrives
with both halves: the tool error flag says the response carries errors, and the
structured content says what came back with them. Errors in a shape other than
the GraphQL one count as errors for that flag, and the text and the structured
content carry them as the API wrote them. A refusal GATool writes itself,
such as a result above the size limit, carries a sentence in place of the envelope
and goes back as text alone.

Structured content reaches an MCP client alone. Spring AI's in-process tools
carry text, so the `ChatClient` side is unchanged.

### Unions, and the errors-as-data pattern

A union or interface position publishes one branch per member its type
conditions cover, with `__typename` pinned in each branch when the operation
selects it:

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
Both error members carry `message`, and the pinned `__typename` is what lets a
model tell them apart, which is what it switches on.

That shape describes more than it validates: with the open branch present, the
position enforces "an object, or null", and the branches are there for the
model to read. The open branch is what makes the rest safe, because a member
the API adds after the operation file was written lands there, where otherwise
a call would fail on an operation file that had not changed.

An operation that leaves `__typename` out gets the same branches without the
`const`, and startup warns: nothing in the response then tells a model which
member it is holding, and GATool takes only its own directive out of a document,
so it does not add `__typename` to your query. A type condition naming the
position's own type, or an interface it declares, adds to the one shape every
member shares without a branch of its own, so a fragment on `Node` at a `node`
position publishes one shape and startup stays quiet. One output schema holds
at most 500 object shapes; a position past that bound is published as an open
object; a result there still conforms, and startup names the position.

### Custom scalars

A custom scalar reaches a model as `{}`, the schema that accepts any JSON value,
so the model guesses the format and learns it from the API's request error. A
`@specifiedBy` URL GATool has read narrows that, and so does a fragment you
write.

**A `@specifiedBy` URL GATool has read.** Every specification the community
registry publishes was read, 31 of them, plus RFC 4122 and RFC 3986, which the
GraphQL specification writes in its own examples. Nine carry a JSON Schema
`format`, and the rest carry the shape in prose, because their format would
refuse a value the API takes. The URL is normalised before the lookup, so the
spellings of one page find one entry: `tools.ietf.org` and `datatracker.ietf.org`
for an RFC, with or without `.html`, and with or without a section anchor. The
scalar keeps its own description either way, and where no format is named, the
property carries the URL so the model can see which page defines the value.

**A fragment you write.** Most schemas leave `@specifiedBy` unset, and a scalar's
name is only a name: the values its API takes live in coercion the schema leaves
out. So you state the format:

```yaml
gatool:
  inputs:
    scalar-schemas:
      DateTime:
        type: string
        format: date-time
        description: An RFC 3339 timestamp, for example 2026-09-18T10:00:00Z.
```

Startup names every scalar still undescribed, so the warning tells you which
ones to write. A fragment wins over the `@specifiedBy` table, and its
`description` stands in place of the scalar's own.

Six keywords are published, and startup refuses the rest with the reason:

| Keyword | What it does |
| --- | --- |
| `type` | `string`, `integer`, `number` or `boolean`. It asserts, so an incorrect value is rejected before it reaches your API. GATool writes the null branch itself |
| `format` | One of the ten Anthropic documents: `date-time`, `time`, `date`, `duration`, `email`, `hostname`, `uri`, `ipv4`, `ipv6` and `uuid`. It guides the model, and validation reads past it. OpenAI's strict subset leaves `uri` out, so a fragment writing it publishes with a startup warning |
| `pattern` | A regular expression the string has to match. It asserts, so prefer one that over-accepts. It stays inside the syntax Java and ECMA 262 share: inline flags, `\A`, `\z`, `\Z`, `\Q...\E`, `\p{...}`, the escapes `\R`, `\h`, `\H`, `\v`, `\V`, `\X`, `\G`, `\e`, `\a`, `\N{...}` and `\x{...}`, `&&` or a nested `[...]` inside a class, possessive quantifiers and atomic groups are refused by name, because a client compiles the pattern as ECMA 262. Lookaround, a backreference, a named group and `\b` are refused as well, because Anthropic's strict tool use answers them with `400 Invalid regex in pattern field` |
| `enum` | A closed value set, which only the API's owner knows. It asserts, because the MCP SDK's validator enforces it |
| `description` | The highest-leverage field here, and the replacement for every refused keyword |
| `anyOf` | Two to eight branches, for a scalar with more than one wire form |

A numeric bound is refused because Anthropic's strict tool use answers `minimum`
with HTTP 400: `For 'integer' type, properties maximum, minimum are not
supported`. `oneOf` is refused the same way. State a bound in `description`,
which is what Anthropic's own SDKs do.

Three of the six assert, so a fragment can refuse a call before the API sees it:
`type`, `pattern` and `enum`, each measured against the MCP SDK's own validator. That is
the cost of describing a scalar, and it is why the fragment is yours to write:
you know whether your `Long` travels as a number, as a quoted string, or as
either. Write the wider shape when you are unsure, and let the API answer the
rest.

**A profile adds to a fragment.** `gatool.inputs.scalar-schemas` is a map of
maps, and Spring Boot merges a map across property sources. A fragment written
in `application.yml` and again in `application-prod.yml` therefore publishes
the keywords of both files. The profile changes the value of a keyword the base
file wrote, and it adds keywords of its own. A keyword the base file wrote
stays in the fragment while the profile leaves it out. A profile that writes
`type: string` and `format: date-time` over a base fragment holding a `pattern`
for a date publishes all three, and the timestamp its description asks for then
fails the pattern. Keep a keyword that differs between environments out of the
base file and write it in each profile, or write it in the profile with the
value that profile needs. `gatool.results.scalar-schemas` merges the same way.

**A result reuses the fragment.** You write the fragment once. Where a tool
publishes an output schema, a result describes the scalar from the same fragment:
its `type`, its `format` and its `description`, and for a fragment that is an
`anyOf`, the `type` and the `format` of each branch. GATool writes `null` beside
them, as it does for every value of a result. The description is written at the
first position of each output schema where the scalar appears and left out at the
later ones, because the schema travels in every `tools/list`.

`enum` and `pattern` stay on the input side. A fragment says what a model should
send, and you may write it narrower than the API on purpose: an `enum` that lists
fewer values than the API holds, or a `pattern` that asks for `2026-09-28` while
the API returns `2026-09-28T00:00:00Z`. An output schema is binding in MCP, so a
result that fails it is reported as an error after the API already ran the call,
and for a mutation that invites a retry of a write that was applied. `type` and
`format` describe the wire form, which a scalar normally shares in both
directions.

One case needs a second entry: a scalar that returns another form than it
accepts. A `Money` that is accepted as a number and returned as a quoted string
fails the reused `type: number` on every result, so
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

An entry there follows the rules of the table above. The output schema publishes
it as written, its `enum` and `pattern` included, because you wrote them about
results, and it takes the place of the input fragment there. The input schema
reads `gatool.inputs.scalar-schemas` alone. To switch the reuse off for one
scalar, give it an entry under `gatool.results.scalar-schemas` that holds a
`description` alone: its results then publish the empty schema, which accepts any
JSON value.

A scalar without a fragment that cites a specification GATool has read publishes
the `type` and the `format` of that specification in a result, as it does in an
argument, because a specification states one wire form for both directions. A
fragment wins over the specification on both sides. An API that cites a
specification and returns another form takes an entry under
`gatool.results.scalar-schemas`, like any scalar that returns another form than
it accepts.

**A 64-bit integer needs both forms.** A JSON reader using IEEE 754 binary64
changes `9223372036854775807` into `9223372036854776000`, so a model can send the
right digits and a client still hands the server a different number:

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
extended `Long` takes both forms, and three different `Long` specifications are
published, one of which takes the string alone. That is the reason the fragment
is yours to write: the same scalar name means different things behind different
APIs.

**A decimal reaches the API with every digit.** GATool's own mapper reads a
float as a `BigDecimal` on every surface: a result, an in-process argument and
an MCP argument, so `spring.jackson.deserialization.use-big-decimal-for-floats`
is unnecessary. The `mcpServerJsonMapper` bean that the MCP transport reads a
request with enables the same setting, `USE_BIG_DECIMAL_FOR_FLOATS`. The digits
an API receives can differ from what a model wrote, because a `BigDecimal`
prints its own way: `1e2` reaches the API as `1E+2`, and `12.50` reaches it as
`12.50`, both valid JSON numbers. A decimal past the range of a `double`, such
as `1E400`, reaches the API as a number.

### Shared fragments

An operation file defines the fragments it spreads, and a fragment several
operations share can live in a file of its own, for example under
`gatool/mcp/fragments/`. A file that holds fragments alone becomes a shared
fragment file for the locations that reach it. Startup attaches each shared
fragment to every operation that spreads it and validates the assembled
document. The fragments are appended in the order of the first spread, depth
first, so the text an API receives stays the same between builds.

- A spread resolves to the operation file's own fragment first, and to a shared
  fragment file on the same side second, so two operation files may each define
  their own `MovieCard`.
- An operation file whose own fragment has the name of a shared one keeps its
  own, and startup warns about naming both files.
- Two shared fragment files on one side that define the same name stop startup,
  and the message names both files.
- A shared fragment that every operation file leaves unspread earns a warning
  with its file and is validated on its own, so a wrong field in it stops
  startup with the fragment file's line and column, and a spread without a
  definition in either place stops startup the way any validation error does.
- `@defer` and `@stream` inside a shared fragment file stop startup the way they
  do in an operation file, naming the fragment file, and a validation error
  inside a shared fragment names the fragment file with its own line and column
  beside the operation file.

The CI check returns the assembled text of every tool, and the startup listing
prints it at `DEBUG` for `io.gatool` for the tools that use shared fragments.
`#import` comments stay unsupported because the GraphQL specification treats
comments like whitespace.

### The size of a document

GATool limits what it reads from a file, and the API limits what it accepts in a
request. The two are set in different places, so they can disagree.

**What GATool reads.** An operation file, a shared fragment file and the schema
file are your own text, so GATool reads them however long they are. One limit
stays on an operation file and a fragment file: selection sets nested 165 deep,
because a document nested far deeper runs the thread that prints it out of
stack. A file past that depth stops startup with a problem that names the limit.
The limit is fixed in this release, and a property cannot change it.

**What the API reads.** The API applies limits of its own to each request, and
it refuses a call whose document passes them. GraphQL's introspection describes
types and leaves request limits out, so GATool cannot read them from the API.
You state them under `gatool.api.request-limits`, and startup and the CI check
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

Set each to your API limit. The defaults hold for an API built on
graphql-java that left its parser options alone. Apollo Router stops at 15,000
tokens by default as well, and it counts the ignored tokens, such as commas,
among them, so its limit is reached sooner. A value below 1 leaves that size
unchecked. The properties decide what GATool warns about. The API keeps
applying its own limits whatever they say, so a value above the limit of the
API silences a warning about a call the API refuses.

The document counted is the one GATool sends: the operation as graphql-java
prints it, with the fragments of every shared file it spreads. The printer
indents each field, so a deeply nested document gains whitespace the file did
not hold. The tool is served either way, and the way out is to select less or
to split the operation.

**What a model writes.** A document a model writes for `executeGraphql` is read
under graphql-java's limits for a request, nesting included, and under three
limits you set: `max-depth`, `max-fields` and `max-aliases` under
`gatool.dev.experimental.dynamic-operations`. They protect the API from a
document that reached it without review, so set them at or below the limits of
the API.

### Reading the schema from a registry

`gatool.api.schema.location` is a Spring `Resource`, so the value decides where
the schema comes from. `classpath:` and `file:` read a file in the jar or on a
mounted volume. An `http` or `https` URL is fetched at startup by GATool itself,
because a registry serves the schema with a key in a header and Spring's own
`UrlResource` cannot send one. `s3://`, `gs://` and `azure-blob://` arrive
through Spring Cloud AWS, GCP and Azure, which register those protocols with the
same resource loader, so a team adds that starter and writes the URL.

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

The key stays out of every log line, and startup names the URL without its
userinfo and query. A value holding a line break, which a secret read from a
file often ends in, stops startup naming the property, and so does a header
name outside RFC 9110's token characters; a space inside the value, as in
`Bearer <key>`, is accepted. GATool follows up to five redirects itself and
sends the key to the configured origin alone: a `Location` on the same scheme,
host and port gets it, and any other host, such as the pre-signed storage URL
Hive's CDN answers with a 404, and it is fetched without it. A redirect loop or a sixth hop
stops startup naming the URL, and `spring.http.clients.redirects` does not
apply to this request. The body is bounded by `gatool.api.schema.max-size`,
10MB by default, so a schema larger than the response cap of a tool call is
fetched while that cap stays where the deployment set it. A schema above the
bound stops startup naming the property. GATool fills whichever of
`spring.http.clients.connect-timeout` and `read-timeout` the application left
unset, with 3 seconds to connect and 15 for an answer, which startup says at
INFO, as it does for the API.
Hive's CDN answers with an `ETag`, which
GATool keeps on the first line of the cached copy under
`gatool.api.schema.cache-directory`. The default is `schema`, inside a folder
named `gatool-` and the account the JVM runs as, under the JVM's temporary
directory (`java.io.tmpdir`). The copy is written after
every operation file validated against the fetched schema, so it holds the last
schema that validated, and a fetch that breaks an operation stops startup and
leaves the copy alone. The next startup sends
`If-None-Match`, a `304` reuses the copy, and a registry that cannot be reached,
or that answers `5xx`, starts the application on the copy with a warning naming
its age. A `4xx` such as `401` names a setting to fix, so it stops startup even
with a copy on disk. The directory is configurable: a team that wants the copy
to outlive the temporary directory, or to live on a persistent volume, sets the
property to a path of its own. A blank value fetches at every startup and stops
when the fetch fails.

**The default folder is checked before it is used.** The temporary directory of a
Linux host is shared by every account on it, so another account can make
GATool's folder first and put a schema into it, and the descriptions of a schema
read from there reach the model as tool text. GATool makes the folder for its
owner alone, and it uses the folder only while the account it runs as owns it
and group and others cannot write to it. A folder that fails is left unread and
unwritten, and startup warns with the reason: the schema is then fetched at
every startup, and a startup while the registry is down stops. A directory you
configure is used as it is, because a volume mounted into a container is often
owned by root and writable by a group, and it is yours to secure.

Apollo GraphOS and WunderGraph Cosmo hand the schema to their own command line
tools: `rover supergraph fetch` and `rover graph fetch` write the supergraph or
the API schema, and `wgc federated-graph fetch-schema` writes the router schema
of a federated graph, or the client schema with `--client-schema`. Package the
API or client schema: a supergraph or router schema carries fields marked
`@inaccessible`, which pass validation at startup and which the router refuses
at call time, and GATool reads the file as written until it derives the API
schema itself. A build step that runs one of them and packages the file gives
GATool a `classpath:` or `file:` location, and a team that publishes the file to a
bucket or behind its own URL uses the protocols above.

### Getting started without writing operation files

A development switch, off by default, that startup warns about on every boot
while it is on, gives you tools from the schema alone:

```yaml
gatool:
  dev:
    experimental:
      generate-tools: all-root-queries    # or all-root-queries-and-mutations
      generated-selection-depth: 1        # 1 to 5
      generate-tools-for-deprecated-root-fields: true
      generated-operations-include-deprecated-fields: true
```

Every root field becomes one tool: the field name becomes the tool name, its
arguments become the tool's arguments with their defaults, and the field's own
schema description becomes the tool description. The selection set takes the
leaves of the type the field returns, so depth `1` selects the scalars one level
in. A wrapper costs one level per hop, so a Relay connection needs `3` to reach
node data: the connection, its edges, then the node. At `2` you get cursors and
page counts, and the node data stays out.
A union selects `__typename` and one inline fragment per member; an interface
selects `__typename` and its own fields.

The generator leaves out a root field whose type expands to an empty selection
within the depth, and one whose generated operation is longer than 20,000
characters. Startup names each one it skipped, and tells you whether a deeper
setting would reach the first kind and to lower the depth for the second.
Inside a selection, a field that would reach a type already on the path is
left out, and the rest of the selection stays. A required argument of a
root field becomes a required argument of the tool. A field inside the
selection that demands an argument is left out of the selection, because the
generator cannot supply its value. A root field you asked to drop with the deprecation property below
leaves quietly, since you asked for it.

**Deprecated fields stay in until you say otherwise.** Both deprecation
properties default to `true`, which is what the generator has always done: a root
field your schema marks `@deprecated` becomes a tool, its deprecation goes into
the tool description where the model reads it, and a deprecated field takes its
place in a generated selection set. Set
`generate-tools-for-deprecated-root-fields` to `false` and that root field is left
out of the generation entirely. Set
`generated-operations-include-deprecated-fields` to `false` and a deprecated
field stays out of a generated selection, judged by the declaration of the type
being selected, which is one way to trim what a tool brings back. A root field
whose type offers deprecated fields alone then has an empty selection set and
loses its tool. Startup says which setting reaches it, the depth or this property,
or that only both together do.

**An operation file wins.** A root field your files already select keeps your
operation, so you can generate the lot, keep the three you want, and write those
as files while the rest stay generated.

**Why it lives under `dev.experimental`.** GATool's product is the trusted documents:
The operation files state which operations exist, a pull request reviews each one,
and the model chooses from that list. This publishes the whole root of your API to
a model instead, which is what trusted documents exist to prevent, and it may
change in any release. Startup says so on every boot while it is on. The generated
text goes through the same parser, validator, and catalog as a file you wrote, so
a generated tool behaves as a tool from a file does.

**The three-step layer needs two dependencies.** With
`gatool.dev.experimental.generate-tools` set to `dynamic-three-step`, GATool
publishes `searchSchema`, `introspectType` and `executeGraphql`, and the search
ranks schema coordinates with Lucene. Neither starter carries Lucene, because the
layer is off by default, and the two jars are 6.3 MB together (lucene-core 4.6 MB,
lucene-analysis-common 1.7 MB), so add both yourself:

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
layer is on, and one of the two is absent. lucene-core holds the index, and
lucene-analysis-common splits an identifier such as `topRatedMovies` into words
and stems them, which is what lets a question in plain words find a field on a
schema without descriptions. An application that publishes a `SchemaSearch`
bean of its own ranks with that bean, and both jars stay out of its build. The
bean builds its entries with `SchemaCorpus.of`, from a schema the application
reads on its own. `GAToolCatalog` holds the tools alone, without the schema, and a `SchemaSearch`
bean that injects the catalog makes a cycle Spring reports at startup, because
the catalog depends on the search bean. An operation file whose tool would take one of the three names stops startup,
naming the file and the property; give that operation another name with
`@gatool(name:)`. A search hit on a field an interface declares says so and
names an implementation to select it on, `introspectType` lists an interface's
implementations in a trailing comment, and its description names the query
root, and the mutation root while `allow-mutations` is on.

A deprecated field, argument, enum value or input field that
`gatool.dev.experimental.dynamic-operations.include-deprecated-fields=false`
hides from `searchSchema` and `introspectType` still executes when the model
writes it into a document, because the property saves tokens and is not an
access control; a field a caller must not reach stays out of the schema the API
serves. A federated subgraph publishes `Query._service { sdl }`, which lets a
model read the whole SDL through `executeGraphql`, so the layer publishes what
the API publishes, with one exception: a document selecting `__schema` or
`__type` at any depth is refused by `executeGraphql`, which sends the model to
the two schema tools. The three limits on the document it sends, `max-depth`,
`max-fields` and `max-aliases`, count the document's syntax; argument values,
list sizes and resolver cost stay with the API's own demand control, and the
two response caps apply after the API computes the result.

## Configuration

| Property | What it does |
| --- | --- |
| `gatool.api.url` | The GraphQL API every document goes to. A redirect from it is left unfollowed and reported with its status, so name the URL the API answers at |
| `gatool.api.schema.location` | The schema that operations validate against: a `classpath:` or `file:` resource, an `https://` URL, or a protocol a Spring Cloud module registers |
| `gatool.api.schema.header-name` | The header that carries a registry key when the location is an `http` or `https` URL |
| `gatool.api.schema.header-value` | Its value, kept in the environment |
| `gatool.api.schema.cache-directory` | Where a fetched schema and its `ETag` are kept, by default `schema` inside `gatool-<account>` under the JVM's temporary directory (`java.io.tmpdir`), where `<account>` is the account the JVM runs as; GATool makes the default folder for its owner alone and uses it only while that account owns it and group and others cannot write to it; a path of your own keeps the copy on a persistent volume and is used as it is, and blank fetches at every startup |
| `gatool.api.credentials.strategy` | How GATool authenticates to that API: unset sends each call without a credential, `static-header` sends the header below, `client-credentials` obtains GATool's own token, `token-exchange` exchanges the caller's |
| `gatool.api.credentials.header-name` | The header that carries the credential, such as `Authorization` or `X-API-Key` |
| `gatool.api.credentials.header-value` | Its value, such as a bearer token, kept in the environment |
| `gatool.api.credentials.client-registration-id` | The Spring Boot client registration the two OAuth2 strategies authenticate through |
| `gatool.api.credentials.audience` | The audience token exchange asks the issuer for, which the API validates |
| `gatool.api.credentials.resource` | The resource token exchange names to the issuer, sent when set |
| `gatool.mcp.operations.locations` | Where the MCP operation files live, `optional:classpath*:gatool/mcp/` by default. Naming a folder replaces that default, so list it again beside a new folder to keep the tools already there. A `classpath*:` location inside a jar needs directory entries in that jar, which Maven and Gradle write, because Spring lists a jar's folder through those entries |
| `gatool.in-process.operations.locations` | Where the in-process operation files live, `optional:classpath*:gatool/in-process/` by default, replaced the same way and with the same jar rule |
| `gatool.naming.strategy` | `camel-case`, `snake-case` or `as-written` |
| `gatool.results.max-characters` | Largest result a tool returns, 60000 characters by default. The count is Java's `String.length()`, which counts UTF-16 code units, so a character outside the Basic Multilingual Plane, such as an emoji, counts as two |
| `gatool.results.publish-output-schema` | Whether tools publish an output schema and structured content; the three dynamic tools are published without one either way |
| `gatool.results.partial-results-as-success` | Whether a response holding both data and errors counts as a success, off by default |
| `gatool.results.scalar-schemas.<Name>` | The JSON Schema fragment the output schema publishes for a custom scalar that returns another form than it accepts, in place of what it reuses from `gatool.inputs.scalar-schemas.<Name>` |
| `gatool.api.request-limits.max-characters` | Characters you expect the API to read of a document, 1,048,576 by default, which is what graphql-java reads of a request; startup and the CI check warn about a tool whose document holds more, and the tool is served all the same; a value below 1 leaves the size unchecked |
| `gatool.api.request-limits.max-tokens` | Tokens you expect the API to read of a document, 15,000 by default; the warning and the value below 1 work as for the characters |
| `gatool.api.request-limits.max-whitespace-tokens` | Whitespace tokens you expect the API to read of a document, 200,000 by default, counted in the printed document GATool sends; the warning and the value below 1 work as for the characters |
| `gatool.api.max-response-size` | Largest response GATool reads from the API, 1MB by default. The value determines the heap size for one in-flight call, which "Running in a container" sizes. A value in bytes below `gatool.results.max-characters` makes the cap refuse every result the result limit would have allowed, and startup warns naming both |
| `gatool.api.schema.max-size` | Largest schema GATool fetches from a URL, 10MB by default; a schema above it stops startup naming the property. The bound is the schema's own, so a public schema of a few megabytes is fetched while `gatool.api.max-response-size` stays where the deployment set it |
| `gatool.mcp.rate-limit.calls-per-minute` | Calls one caller makes to one tool each minute, 60 by default. The caller is the token's subject once the endpoint is secured, or the token's fingerprint where an application's own converter leaves the name blank, which is the key the session binding uses, and the client address while `allow-mcp-calls-without-authentication` is on; it is read through the `SecurityContextHolderStrategy` bean an application declares, so a strategy of the application's own keeps the count per caller. Behind a proxy that address is the proxy's, so set `server.forward-headers-strategy=native` with `server.tomcat.remoteip.internal-proxies` naming the proxy, which replaces a default that covers every private address range; `framework` trusts the forwarded headers every client sends, so a caller could then send a different address on every call and escape the count |
| `gatool.mcp.rate-limit.max-tracked-pairs` | Most caller and tool pairs the limiter counts at once, 100,000 by default; each pair holds a bucket of a few hundred bytes for a minute after its last call. Above it, the limiter drops a live pair's bucket for each new one, and that caller starts with a fresh allowance; the Micrometer counter `gatool.rate-limit.evictions` counts those drops, and the log warns once a minute, naming the property. Set it above the pairs active within a minute |
| `gatool.mcp.transport.max-request-body-size` | Largest request body the MCP endpoint reads, 256KB by default |
| `gatool.mcp.transport.allowed-origins` | The origins the endpoint accepts in an `Origin` header, empty by default, so a request carrying the header is refused with `403` until its origin is listed; a request without the header is accepted. Each entry is a serialized origin, `scheme://host[:port]`: the scheme and the host are compared case-insensitively, one trailing slash is ignored, and an entry of another shape stops startup. CORS stays the application's: a browser client also needs a `CorsConfigurationSource` bean of the application's own, such as a `UrlBasedCorsConfigurationSource`, which is what Spring Security's `cors()` reads |
| `gatool.mcp.sessions.max-count` | Most stateful sessions the server keeps at once, 1000 by default |
| `gatool.mcp.sessions.max-count-per-caller` | Most stateful sessions one caller may hold at once, 100 by default; 0 turns it off |
| `gatool.mcp.sessions.idle-timeout` | How long a stateful session may stay idle before eviction, 30 minutes by default; `spring.ai.mcp.server.streamable-http.keep-alive-interval` has to be shorter than it, and startup stops naming both otherwise |
| `gatool.mcp.security.baseline-scopes` | Scopes every MCP call needs, published in the metadata and every `401` |
| `gatool.mcp.security.resource` | The canonical resource URI the metadata publishes; unset publishes the request's URL |
| `gatool.mcp.stdio.granted-scopes` | The scopes the process that started a stdio server holds, which tools requiring scopes are checked against |
| `gatool.mcp.stdio.log-to-stderr` | Whether a stdio server writes its log to stderr, on by default. stderr carries WARN and above until `logging.threshold.console` sets another threshold, and the log is written without making the server wait, so a host that leaves stderr unread loses lines while the server keeps answering, and a WARN line says how many once lines get through again. It applies on Logback, while the application leaves `logging.console.enabled` unset and the logging configuration to Spring Boot's defaults. On Log4j2 startup, it prints a one-line message to stderr that the log is lost, while the application leaves the log file and `logging.config` unset. On `java.util.logging` a log file leaves the stderr log on, and the application's own `logging.config` is what changes it |
| `gatool.inputs.scalar-schemas.<Name>` | The JSON Schema fragment to publish for a custom scalar |
| `gatool.inputs.send-explicit-nulls` | Whether a null argument for a variable that declares a default reaches the GraphQL API as an explicit null, off by default; a null for a variable without a default is sent either way |
| `gatool.dev.experimental.generate-tools` | Which tools to write from the schema: `none` by default, `all-root-queries`, `all-root-queries-and-mutations` or `dynamic-three-step` |
| `gatool.dev.experimental.generate-tools-for-deprecated-root-fields` | Whether a deprecated root field becomes a tool |
| `gatool.dev.experimental.generated-operations-include-deprecated-fields` | Whether a deprecated field is selected |
| `gatool.dev.experimental.generated-selection-depth` | How many object levels a generated selection expands |
| `gatool.dev.experimental.dynamic-operations.corpus-format` | How each schema coordinate is written for the search index, `sdl` by default; `gloss` is one sentence and `raw` the coordinate alone |
| `gatool.dev.experimental.dynamic-operations.search-backend` | What ranks a search, `bm25` by default; `embedding` uses the application's `EmbeddingModel` bean and startup stops without one |
| `gatool.dev.experimental.dynamic-operations.search-token-budget` | Tokens of search results one `searchSchema` call returns, 2000 by default, a token counted as four characters of the text and the hint; a best match above the budget is named with its cost so the model can read it with `introspectType` |
| `gatool.dev.experimental.dynamic-operations.ranked-hits` | Coordinates a search ranks before the budget cuts the list, 50 by default |
| `gatool.dev.experimental.dynamic-operations.allow-mutations` | Whether a mutation the model wrote runs, off by default; while off, the mutation root and the types only it reaches stay out of `searchSchema`, `introspectType` and the hints |
| `gatool.dev.experimental.dynamic-operations.include-deprecated-fields` | Whether a deprecated field, argument, enum value or input field is searchable, readable and used as a step in a hint, off by default; while on, each is written with the reason the schema gives |
| `gatool.dev.experimental.dynamic-operations.validate-only` | Whether `executeGraphql` returns the validated operation for a person to run, without calling the API, off by default |
| `gatool.dev.experimental.dynamic-operations.search-schema-description`, `.introspect-type-description` and `.execute-graphql-description` | The whole description of one of the three tools, replacing the text GATool writes, sentences GATool generates from the schema and the settings included. Unset by default, and a blank value keeps GATool's text |
| `gatool.dev.experimental.dynamic-operations.max-depth` | Deepest field nesting `executeGraphql` sends, 15 by default; 0 switches the check off |
| `gatool.dev.experimental.dynamic-operations.max-fields` | Most field selections `executeGraphql` sends, spreads counted where spread, 500 by default; 0 switches the check off, and graphql-java's own ceiling of 100,000 selections still applies |
| `gatool.dev.experimental.dynamic-operations.max-aliases` | Most aliased fields `executeGraphql` sends, 30 by default; 0 switches the check off |
| `gatool.dev.experimental.dynamic-operations.question-prefix` | Text put in front of a question before it is embedded, empty by default; joined as it is, so quote the value in YAML to keep a trailing space |
| `gatool.dev.experimental.dynamic-operations.field-prefix` | Text put in front of each schema field before it is embedded, empty by default, joined the same way |
| `gatool.dev.experimental.dynamic-operations.embed-batch-size` | Schema fields sent to the embedding model in one call, 128 by default |
| `gatool.dev.experimental.dynamic-operations.vector-cache-directory` | Where embedded fields are cached so a schema is embedded once, by default `vectors` inside `gatool-<account>` under the JVM's temporary directory (`java.io.tmpdir`), checked the way the schema cache's default folder is; a path of your own keeps the vectors on a persistent volume and is used as it is, and blank embeds at every startup; the files are written through a temporary file and an atomic rename, and a cache an earlier build wrote is embedded once more, because the key format changed |
| `gatool.dev.experimental.dynamic-operations.required-scopes` | The scopes a caller needs for the three dynamic tools on top of the baseline, all of them; required under a shared credential |
| `gatool.observations.include-content` | Whether arguments and results join the `gatool.call` observation as high-cardinality values, off by default |

Every property has Javadoc, so an IDE explains it while you type. Each one says
what it does in a sentence; what a setting costs is here.

A call to a remote API is bounded by `spring.http.clients.connect-timeout` and
`spring.http.clients.read-timeout`, which Spring Boot's `RestClient` builder
carries into the client GATool uses. GATool fills whichever of the two the
application left unset, 3 seconds to connect and 15 for an answer, and says so
at INFO, because a call to an API that stops answering would otherwise hold
the thread until the socket closes. Under `client-credentials` and
`token-exchange` the request to the issuer's token endpoint waits under the
same two deadlines. When the API takes longer to answer a query
than the read timeout, before the status arrives or inside the body, the model
reads that, with the deadline and the property that sets it, and is asked for
fewer fields or a smaller page, because the same call is likely to take as long
again. When the connection did not open, the
model reads that the call did not run and that a later call may succeed, and
for any other transport failure that GATool cannot tell whether the call ran.
A mutation reads the same account of how far the call got, and after each of
the three it is asked to read the current state before sending the call again.
The model's sentence and the operator's line open with the same words. The
operator reads a WARN line for each such call, of a query
or a mutation, which says how far the call got and ends with the cause: the
tool `could not reach the GraphQL API`, the API
`took longer to answer than the read timeout of 15 seconds`, or the tool
`failed in transport while calling the GraphQL API, and the cause does not say whether the call ran`.
The stack is written at DEBUG. An embedded API runs in the same JVM, and this
release leaves that call without a deadline.

The MCP Java SDK client and Spring AI's MCP client wait 20 seconds for a tool
call by default, and their clock starts before GATool's does. Keep
`spring.http.clients.read-timeout` below the request timeout of the client
that calls the server, or raise `spring.ai.mcp.client.request-timeout`. A
client that reaches its own deadline first gives up on the call, and the model
then reads the client's timeout message instead of GATool's sentence. After a
mutation, GATool's sentence is the one that asks the model to read the current
state before it sends the write again.

GATool builds the request factory of that client from the application's
`ClientHttpRequestFactoryBuilder` bean and `spring.http.clients.*`, and startup
names the builder at INFO. The headers and interceptors a `RestClientCustomizer`
sets reach every request GATool sends, and startup names the customizer beans;
a request factory the customizer installs stays on the application's own
clients, because GATool's factory carries the response size cap and leaves
redirects unfollowed. Without a builder bean,
`ClientHttpRequestFactoryBuilder.detect()` prefers Apache HttpClient 5 and
Jetty over the JDK client, so `httpclient5` on the classpath for any reason
moves GATool onto Apache's pool of 5 connections per route and 25 in all,
measured at about 48 calls per second against 398 with the JDK client, and
startup warns on that builder.
`spring.http.clients.imperative.factory=jdk` selects the JDK client, and a
`ClientHttpRequestFactoryBuilder` bean built from
`ClientHttpRequestFactoryBuilder.httpComponents().withConnectionManagerCustomizer(...)`
raises both sizes.

A proxy reaches GATool's client through that same builder bean. The bean
replaces the builder Spring Boot declares, so every client the application
builds from Spring Boot's builder goes through the proxy as well. A factory a
`RestClientCustomizer` installs stays on the application's own clients, so a
proxy set only there does not reach GATool's calls:

```java
@Bean
ClientHttpRequestFactoryBuilder<?> gatoolRequestFactoryBuilder() {
    return ClientHttpRequestFactoryBuilder.jdk()
            .withProxySelector(ProxySelector.of(new InetSocketAddress("proxy.example.com", 8080)));
}
```

Mutual TLS reaches GATool's client the same way, through
`spring.http.clients.ssl.bundle`, which GATool reads with the rest of
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

In embedded mode, the document runs on the application's `WebGraphQlHandler`
bean, or on one GATool builds from the `ExecutionGraphQlService` with the
application's `WebGraphQlInterceptor` beans, so the interceptors apply in every
application type, including non-web ones, and startup names them. That call
runs past the servlet filter chain and every URL rule on
`spring.graphql.http.path`, so the controls on it are the MCP endpoint's scopes,
those interceptor beans and method security in the resolvers.

Every MCP tool call is a Micrometer observation named `gatool.call`, with
`gatool.call.name` and `gatool.operation.type` (`query` or `mutation`) as
low-cardinality tags and `gatool.call.outcome` as one of
`success`, `graphql-errors`, `api-refused`, `credential-unavailable`,
`response-too-large`, `result-too-large`, `tool-error`, `rate-limited`,
`insufficient-scope` and `off-request-thread`. An
answer the operator has to act on reaches the log at WARN: a `5xx`, and a `2xx`
without a GraphQL response, are written once per tool per minute, and the next
line says how many such answers were left out since the last one; a refused
credential and a redirect are written on every call. A `4xx` stays at DEBUG,
because the model reads it and can act on it: it corrects the call, or sends
the same one again after a `408`, a `425` or a `429`, where it reads that a
later call may succeed, as it does for a `5xx`.

Four Spring AI settings take a default from GATool while the application leaves
them unset, and the application's own value wins: `spring.ai.mcp.server.protocol`
becomes `STATELESS` over HTTP, `spring.ai.mcp.server.name` becomes
`spring.application.name`, `spring.ai.mcp.server.version` becomes
`spring.application.version` when that property is set, and
`spring.ai.mcp.server.instructions` becomes one sentence saying what every tool
result holds, or, under `dynamic-three-step`, the search, read, run and correct
loop the three dynamic tools serve. `spring.main.lazy-initialization=true` is
supported: GATool keeps Spring AI's server bean eager, so the endpoint answers
from the first request, and it keeps its tool catalog eager on both starters,
so a broken operation file still stops startup.

**Spring AI's line about tool methods.** An application whose tools all come
from operation files reads this line at every startup, at WARN, from
`SyncStatelessMcpToolProvider` on the stateless transport and from
`SyncMcpToolProvider` on the stateful one:

```
No tool methods found in the provided tool objects: []
```

Spring AI's annotation scanner collects the beans that carry `@McpTool`
methods and builds tool specifications from them. Without such a method, the
list of beans is empty, which is the `[]`, and the provider says so. The tools
of the operation files are registered another way: GATool publishes them as a
list of tool specifications of its own, and Spring AI's server reads that list
beside the scanner's. Its line `Registered tools:` follows at INFO with the
count, which includes them, and `tools/list` serves them. An application
without `@McpTool` methods of its own can set
`spring.ai.mcp.server.annotation-scanner.enabled=false`, and the scanner and
its line stay out of the startup.

A null argument is the one place GATool writes JSON with its own mapper.
GraphQL reads a variable set to `null` as clearing a value, and a variable left
out as asking for the default the operation declares, or the one the schema
declares on the argument or input field it fills, so every null GATool holds
reaches the request it sends. An application that sets
`spring.jackson.default-property-inclusion` to a value dropping nulls would
otherwise lose them on the way out, and startup says so on the boot where that
happens. Every other HTTP client in the application writes with the mapper it
had.

**A null argument reaches the API.** Spring AI's MCP transports read each
request body with the `mcpServerJsonMapper` bean, and Spring AI's own bean drops
a `null` map entry while reading. GATool declares that bean with a null map value
kept, so a model can clear a value by sending `null`, and the rules above decide
what the API receives. The protocol JSON the transports write is unchanged; a
result map of your own tool that holds a `null` value writes it. Declare a bean
of that name yourself, and GATool leaves it to you.

### The unsafe switches

Five properties weaken a control the server would otherwise keep, four under
`gatool.mcp.security.unsafe` and one under `gatool.api.credentials.unsafe`. Each
is `false` by default, and GATool logs a warning naming it at every startup while
it is on.

| Property | What you give up |
| --- | --- |
| `gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication` | Any caller that reaches the port may call every tool. Keep the port on a loopback address with `server.address`, or behind a gateway that authenticates for it. Startup warns while `server.address` is unset, because the server then listens on every interface, and Spring Boot's own default stays as it is, the way Spring AI's MCP starter leaves it. |
| `gatool.api.credentials.unsafe.forward-client-tokens` | Every call to the GraphQL API carries the token the MCP caller sent. MCP says a server must not pass that token through: the API cannot tell GATool from the caller, a token minted for this server reaches another audience, and a server with this switch on is outside the MCP specification. It only works with an API that skips audience validation. |
| `gatool.mcp.security.unsafe.request-every-scope-at-sign-in` | The metadata and every `401` name every scope this server knows, so a client asks for all of them at sign-in, which MCP's security best practices call a common mistake. It serves clients whose step-up fails. |
| `gatool.mcp.security.unsafe.allow-unlimited-tool-calls` | The rate limiter stops running. MCP requires a server to rate limit tool calls, so a deployment that sets this covers it at a gateway. |
| `gatool.mcp.security.unsafe.allow-superseded-mcp-revisions` | The endpoint accepts 2024-11-05 and 2025-03-26. GATool serves 2025-03-26 in part because it cannot read the JSON-RPC batches that revision asks for. |

`gatool.dev.experimental.*` is separate. Those settings do not weaken a control,
they publish more of your API than a folder of trusted documents would, and they may change
in any release.

## Securing the MCP endpoint

The MCP endpoint is an OAuth 2.1 resource server once Spring Security is on the
classpath. Add the starter and name the issuer, the audience and the scopes every
call needs:

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

Startup stops naming the property when any of the three is unset, and it
stops naming the starter when Spring Security is absent and the unsafe switch is
off. The endpoint reads JWT access tokens only: an opaque token that needs
introspection is unsupported, and the stop for a missing `issuer-uri` says so.
Spring Boot's decoder reads the issuer's discovery document at the first
request, so a wrong `issuer-uri` surfaces there, and the stop at startup covers
the unset property alone. An `issuer-uri` that cannot be reached, or that
answers without a discovery document, gives `500` on every call that carries a
token, because Spring Security raises `JwtDecoderInitializationException`
outside the bearer token handling. An `issuer-uri` that answers and differs
from the `iss` claim of the token gives `401` with `invalid_token`. With the three set, a
call without a token gets a `401` whose challenge
names this endpoint's metadata document,
`/.well-known/oauth-protected-resource/mcp`, and the baseline scopes, so a
client finds the issuer and asks for the scopes at sign-in. The document
answers anonymously with the issuer and the scopes; the root document at
`/.well-known/oauth-protected-resource` stays unserved, because RFC 9728 gives
it the root resource's identifier and a client reads the URL the challenge
names. A request there meets the default chain's `401` with a bearer
challenge. A token for another
audience gets `401` with `invalid_token`, because Spring Boot's decoder checks
the audience, and a token that lacks a baseline scope gets `403` with
`insufficient_scope`. CSRF stays off for the endpoint and its metadata path,
since an MCP client authenticates with a bearer token alone.

An application without a `SecurityFilterChain` of its own gets two from the
starter: one for the endpoint and its metadata path, and a default chain at
Spring Boot's basic-auth order in the shape of the chain Boot would have
contributed. Every other path needs a bearer token for the same issuer, the way
Boot's resource server chain works, since Boot's generated user backs off for a
`JwtDecoder` bean; under the unsafe switch without an issuer the chain keeps
form and basic login against the generated user. The bearer shape runs without
an HTTP session, a saved request or CSRF protection, where Boot's own chain
keeps all three, so a request without a token cannot open a session. The actuator's health endpoint
stays open where the actuator is present, and an error dispatch is left to
Boot's error controller, so a request the endpoint refuses with a `404` reads
Boot's error body in place of a login challenge. Both back off as soon as the
application declares any chain of its own, which is Boot's rule for its own
defaults. An application that declares its
own chains gives the endpoint a chain of its own, ordered ahead of the rest, with
a matcher that covers both paths and the configurer alone:

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
chain's own, and the first matching rule wins, so a broader rule would decide the
endpoint ahead of the scope check. Startup also stops, naming the chain above,
when the chain that serves the endpoint lacks the resource server.

Behind a proxy that terminates TLS, the challenge and the metadata document
would name the address the servlet container saw. Set
`gatool.mcp.security.resource` to the URL clients reach, such as
`https://mcp.example.com/mcp`, and both name it, or set
`server.forward-headers-strategy=native` with
`server.tomcat.remoteip.internal-proxies` naming the proxy, so the container
reads the forwarded headers from that proxy alone. The default of that property
covers every private address range, so with the default any caller on a
private network is read as a proxy, and `trusted-proxies` adds to that list
without narrowing it. The `framework` strategy
trusts the forwarded headers every client sends, so a caller could then choose
the host the challenge names and the address the rate limiter counts. That
limiter counts per caller under the same key the session binding uses: the
token's subject once the endpoint is secured, and the client address while
`allow-mcp-calls-without-authentication` is on.
The value is an absolute URL on the endpoint's own path, without a query, a
fragment or a trailing slash, and startup stops on any other. MCP ties the
audience to that canonical URL, so the issuer should write it as the token's
`aud` and `spring.security.oauth2.resourceserver.jwt.audiences` should name it.
The audience check belongs to Spring Boot's decoder, so an application that
declares a `JwtDecoder` bean of its own owns that check.

Some issuers name an API by an identifier of their own and write that
identifier as the audience. Microsoft Entra ID gives an API an App ID URI,
`api://<client-id>` by default, and the `aud` claim of its access tokens holds
the client ID in a v2.0 token and the client ID or the App ID URI in a v1.0
token, as
[Microsoft's claims reference](https://learn.microsoft.com/en-us/entra/identity-platform/access-token-claims-reference)
describes. With such an issuer, the two properties take different values.
`gatool.mcp.security.resource` takes the `https` URL of the endpoint, because
RFC 9728 defines the resource identifier as an `https` URL and every challenge
builds the address of the metadata document from the scheme, host and port of
that value. Startup stops on `api://<client-id>` there, and the message names
the URL to set and the property for the identifier.
`spring.security.oauth2.resourceserver.jwt.audiences` takes the identifier as
the token carries it. Spring Boot's decoder compares the `aud` claim with that
property alone, so a token issued for `api://<client-id>` is accepted once the
property names it, whatever URL the resource property holds.

Once every bean exists, startup checks that the metadata document answers an
anonymous `GET` under the chain that serves it. It checks that the chain
serving the endpoint is the configurer's own, so an application chain ordered
ahead of it with a resource server of its own is caught. It checks that the MCP
compliance filter, which reads each request ahead of the scope check, is
registered and runs ahead of Spring Security's filter. Every stop names the
line to change.

GATool serves the endpoint with the dispatcher servlet mapped at the root, so
startup stops on `spring.mvc.servlet.path` set to anything else, and on an
endpoint written as a pattern. Behind `server.servlet.context-path` the endpoint
and its metadata document carry the context path, and
`gatool.mcp.security.resource` names it in front of the endpoint. The document
then sits at the context path followed by the well-known path, which is where
every challenge points, where RFC 9728 would insert the well-known path ahead
of the whole resource path. A client that follows the challenge finds it, and a
client that probes the RFC location behind a context path does not.

On the stateful transport, each session is bound to the caller that opened it,
as MCP's security best practices recommend. A request carrying another
caller's session ID, a deletion included, answers `404` with `-32001` and the
session unknown, so that client starts a session of its own, and a request
carrying an id the server does not know answers the same `404` with the same
code on the secured path and the unsafe one. The binding key is the
token's subject, and the token's fingerprint where an application's own
converter leaves the name blank; a token lacking both answers `401`. Every
request that carries the session id refreshes the binding, and the binding is
forgotten when the SDK confirms a deletion or, at the next session opening,
once it has been silent for three times `gatool.mcp.sessions.idle-timeout`, by
which time the SDK has evicted the session itself. The SDK's own error bodies, an
exception it writes as the body of a `400`, a `404` or a `500`, are rewritten
to the JSON-RPC error alone, carrying the call's id where the request held one,
so a stack trace stays out of every answer.

In production, set `gatool.mcp.security.resource`. Without it, every challenge and
the metadata document name the address a request carried in its `Host`
header, which the caller writes, and startup warns when the server is bound
off loopback.

### The caller and the request thread

GATool reads the caller of a tool call from the thread the call runs on. The
rate limiter names the caller from Spring Security's context on that thread,
and from the servlet request bound to it while
`allow-mcp-calls-without-authentication` is on. The `token-exchange` strategy
and the forwarded token take the caller's token from the same context. The
scope check runs earlier, in the security filter chain, so it reads the token
of the request itself.

That reading is right while the call runs on the thread that served its HTTP
request, and GATool enforces that instead of assuming it. The MCP SDK hands a
synchronous tool handler to a worker thread of Reactor's bounded elastic
scheduler unless the server was built with `immediateExecution(true)`. Spring
AI's stateless server bean sets that itself, and its stateful server bean takes
it from a `McpSyncServerCustomizer` bean that Spring AI contributes. On a
worker thread, every caller would share one rate limit, and under Spring
Security's `MODE_INHERITABLETHREADLOCAL` a call could reach the API with the
token of another caller.

**Startup stops for a server built without immediate execution.** The stateful
server bean takes one customizer. An application's own `McpSyncServerCustomizer`
bean beside Spring AI's stops startup, because Spring finds two beans for one
parameter. Marked `@Primary`, or named `mcpSyncServerCustomizer` like the
parameter of Spring AI's bean method, the application's bean takes the place of
Spring AI's, and immediate execution goes with it unless the application's bean
asks for it. GATool reads the setting from every `McpSyncServer` and
`McpStatelessSyncServer` bean once every singleton exists, and a server built
without it stops startup with a report naming the customizer that dropped it
and the line to add:

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
WARN naming the SDK version and carries on, and the refusal below still holds.

**A call that arrives off the request thread is refused.** GATool's compliance
filter marks the thread it serves each request on, with a plain thread-local
that a child thread leaves behind and that Reactor's context propagation does
not carry. A tool call that reaches the handler on an unmarked thread is
answered with a tool error saying that the server ran the call on a thread that
does not carry the caller, and `gatool.call.outcome` records it as
`off-request-thread`. The operator reads one ERROR line a minute for each tool,
naming the thread. The check runs on the servlet transports and is off over
stdio, where one process is the only caller. It runs whether or not the
deployment's rate limiter or credential strategy reads the caller, because
GATool cannot see what a `ToolRateLimiter` or `ApiCredentialStrategy` bean of
the application does with it.

This also refuses an application that reads each caller correctly: one with
`spring.reactor.context-propagation=auto`, its own customizer without immediate
execution and a secured endpoint, which reads the caller on the worker thread
through the accessor Spring Security registers. Adding
`immediateExecution(true)` to the customizer serves that application, and the
property keeps serving its own Reactor code. Calls on virtual threads are
served: with `spring.threads.virtual.enabled=true` the filter and the handler
run on the same virtual thread.

**A per-caller credential is refused where the thread's context may belong to
another caller.** `token-exchange` and the forwarded token both read the caller
from the security context holder strategy. Under Spring Security's default
strategy, a token on the thread was put there for the work the thread is doing,
by a filter, by context propagation, or by the application's own code. Under
`MODE_INHERITABLETHREADLOCAL` a thread created during one request keeps that
request's context for life, and under `MODE_GLOBAL` one context is shared by
every thread. Under either of those two, a call on a thread that is not serving
a request is refused. The model reads which strategy is in force, and that the
default strategy with `spring.reactor.context-propagation=auto` carries each
caller to the thread its call runs on. This check covers the in-process
surface too, where a streamed chat legitimately runs its tools on Spring AI's
worker threads.

Two shapes get past that last check. A `SecurityContextHolderStrategy` bean of
an application's own class, and a `ListeningSecurityContextHolderStrategy`
wrapped around another strategy, cannot be classified, and GATool treats them
as it treats the default. An application that made `RequestContextHolder`
inheritable, with `setThreadContextInheritable(true)` on its
`RequestContextFilter` or `DispatcherServlet`, gives a child thread a bound
request, which is the signal this check reads.

Six more shapes are refused that would otherwise be served:

- With `allow-mcp-calls-without-authentication` on, in an application whose bean
  `gaToolMcpComplianceFilter` holds another filter than GATool's, the mark stays
  off every thread, so every call is refused and startup carries on without a
  word about it. With the endpoint secured, startup already stops on that
  set-up.
- A call that transports the application's own hands to another thread while
  it carries the security context there itself.
- A call that application code makes on a thread of its own in a servlet
  application, by invoking the handler of one of GATool's
  `SyncToolSpecification` beans from a scheduled job.
- In-process, a streamed call under `MODE_INHERITABLETHREADLOCAL` with
  `spring.reactor.context-propagation=auto`, where propagation restores the
  right caller on Spring AI's worker thread.
- In-process, a call on a thread the application started for the request itself
  under `MODE_INHERITABLETHREADLOCAL`, which is the use that strategy exists
  for. The new thread inherited the right caller.
- In-process, a call in an application without a servlet request, such as a
  message listener that sets the security context itself, under
  `MODE_INHERITABLETHREADLOCAL`.

### Scopes per tool

An operation file lists the scopes its tool needs, all of them, and an empty
list opens the tool to every caller with the baseline scopes:

```graphql
# Deletes one review. Only a moderator may call this.
mutation DeleteReview($id: ID!) @gatool(scopes: ["reviews:moderate"]) {
  deleteReview(id: $id) { id }
}
```

A `tools/call` needs the baseline scopes and the tool's own, and the startup
listing names the effective set beside each tool, baseline first, or `open to
every caller` for a file that declares `[]` without a baseline. The tool list
is built once at startup and served to every caller, so `tools/list` shows
every tool with its name, description and input schema to every caller holding
the baseline scopes, and a call to a tool whose scopes the token lacks is
refused with the `403` below, so the tool's data needs the call. A call without a token gets a `401` whose
`scope` parameter already names the tool's scopes beside the baseline, so a
client signing in for that call asks for everything it needs. A caller without
a token therefore learns, from that `scope` parameter, which tool names carry a
scope list and which scopes they need, because the challenge names them so a
client can ask for them at sign-in. A token that
lacks one gets `403` with `insufficient_scope`, a `scope` parameter naming what
the call needs together with those of this server's scopes the token already
holds, so a client that signs in again keeps what it had, and a description
that says which of this
server's scopes the token held and which it lacked. Two refusals of one caller
for one missing scope carry the same challenge, so a client comparing them can
tell a step-up that can succeed from one that cannot. Each `403` is logged
at INFO with the tool, the scopes the token held and lacked, and a correlation
id: the trace id where Micrometer Tracing runs, and an id of GATool's own
otherwise. A `401` is answered without a log line. A scope name is printable ASCII without a space, a
quote or a backslash, and startup stops on any other. A file without the argument
needs the baseline alone, which suits an API that sees the real user through
token exchange or embedded mode. An embedded call runs past the servlet filter
chain and every URL rule on `spring.graphql.http.path`, so a token holding the
baseline alone runs an operation that a `POST` to that path would refuse with
`403`. Its controls are these scopes, the `WebGraphQlInterceptor` beans and
method security in the resolvers, and startup warnings while embedded mode runs
with Spring Security on the classpath. Under client credentials or a static header,
the API sees GATool as every caller, so there every MCP tool has to list its
scopes, an empty list included, and startup stops naming the files that do
not. The three dynamic tools take their list from
`gatool.dev.experimental.dynamic-operations.required-scopes`. Scopes derived from the
schema's own authorization directives arrive in a later release.

Clients whose step-up fails can be served with
`gatool.mcp.security.unsafe.request-every-scope-at-sign-in`, which publishes every
scope this server knows in the metadata and in every `401`; MCP's security best
practices call publishing every scope a common mistake, and startup warns.

Over stdio, GATool runs each tool call on the thread that reads stdin. The MCP SDK
writes every answer through one queue that takes one answer at a time, and an
answer emitted beside another is dropped, which closes the transport and leaves
the server silent with its process up. An application that declares its own
`McpSyncServerCustomizer` keeps `immediateExecution(true)` in it, and startup
stops while that setting is left out.

Under stdio, GATool sets `spring.main.web-application-type=none` and
`spring.main.banner-mode=off` while those properties are unset, so the
application starts without a web server. An application that serves pages or
an actuator of its own beside the stdio server sets
`spring.main.web-application-type=servlet`. Stdio stays the one MCP transport
there: the MCP endpoint is absent from the HTTP port, GATool contributes
neither of its security chains, and with Spring Security on the classpath
Spring Boot's default chain secures the application's own paths.

Over stdio the caller is the process that started the server, so
`gatool.mcp.stdio.granted-scopes`, set in the environment, names the scopes that process
holds. Startup stops while tools require scopes and the list is unset, and a
call to a tool needing a scope outside it answers with a tool error naming
the scope. Console logging is off under stdio, because Spring Boot logs to
stdout, which is the JSON-RPC channel, and `logging.console.enabled=true`
corrupts the stream. The log goes to stderr; the stream MCP leaves to a stdio
server for its logging, which a host may capture, forward, or ignore. stderr
carries WARN and above, so the lines of a normal start stay out of it, and
`logging.threshold.console` changes that: `INFO` adds the lines of the start,
and `DEBUG` beside a `logging.level` setting adds the DEBUG lines of that
logger. `logging.pattern.console`, `logging.charset.console` and
`logging.structured.format.console` apply to stderr as they would to the
console. The log is written without making the server wait: a host that leaves
stderr unread loses lines once the pipe and the queue behind it are full, and
the server keeps answering. The promise of a server that keeps answering covers
the log GATool attaches on Logback. A direct write to stderr, the log on
`java.util.logging`, and an application's own Logback or Log4j2 configuration
that writes to stderr all wait once the host has left about 64 KB unread. A
host therefore reads stderr or discards it when an operator lowers the levels.
GATool counts the lines it drops, and the first line
that gets through again follows a line that holds the count, such as
`GATool dropped 412 log lines while stderr was not being read`. That line is
written at WARN under the logger
`io.gatool.boot.mcp.autoconfigure.LogbackStderrLog`, so a threshold above WARN,
or a level above WARN for that logger, keeps it out. A host that reads again
finds it ahead of the next line the server writes, or at the end of the stream
once the server has stopped. `logging.file.name` writes the log to a file as
well, which receives every line, and `gatool.mcp.stdio.log-to-stderr=false`
turns the stderr log off. GATool steps back where the application configured
the log itself, by setting `logging.console.enabled`, by naming a file in
`logging.config`, or by bringing a Logback file under a name Spring Boot finds,
such as `logback-spring.xml`. The stderr log is built on Logback, Spring Boot's
default. On Log4j2 the console is off as well, and it stays off where
`logging.threshold.console` is set, because Spring Boot's console would write
the log to stdout. So the log is written only where the application sets
`logging.file.name` or sends the log to stderr in its own Log4j2
configuration. While `logging.file.name`, `logging.file.path` and
`logging.config` are all unset, startup says so in one line on stderr, and
`gatool.mcp.stdio.log-to-stderr=false` keeps that line out. What Log4j2 reports
about itself, such as a log file it cannot create, is written to stderr with
that property at either value, because the listener Spring Boot registers for
those reports writes to stdout. On
`java.util.logging` the log reaches stderr through the console handler of the
JDK, which Spring Boot leaves on under `logging.console.enabled=false`: it
carries INFO and above, and the thread that logs writes it, so a host that
leaves stderr unread makes such a server wait once the pipe is full. While
the console stays off, a startup failure is written to stderr by a
write of its own that passes the queue by: Boot's failure analysis or, where
every analyzer passes on it, the exception's message chain. Beside a full pipe
the report of a failed start is given up after 2 seconds, and the exit code is
what remains.

The scopes a token holds are read from the `SCOPE_` authorities Spring's JWT
converter grants, so an application that converts scopes under another prefix
or claim has every call refused until it keeps that prefix.

**Microsoft Entra ID.** Microsoft Entra ID writes the short name of a scope
into a token, such as `mcp.tools`, according to Microsoft's documentation. It
expects a client to ask for the qualified name, the App ID URI in front of
it, such as `api://6e74172b/mcp.tools`. Setting
`spring.security.oauth2.resourceserver.jwt.authority-prefix` to
`SCOPE_api://<app-id>/` turns that short name into the authority
`SCOPE_api://<app-id>/mcp.tools`, which matches the qualified name a baseline
and an operation file's `@gatool(scopes:)` write with the same App ID URI.
`gatool.mcp.operations.locations` then points at the operation files of that
environment, because the qualified names belong to one tenant, so an adopter
keeps one set of operation files for each environment.

An application's own `@McpTool` methods keep Spring Security method security
once the application adds `@EnableMethodSecurity` itself:
`@PreAuthorize` takes effect on the MCP server's thread, and a denied call comes
back as a tool error, without the `403` challenge above.

### Calling the API with a credential

`gatool.api.credentials.strategy` says how GATool authenticates to the GraphQL API,
and unset calls it plainly, which suits a local API and an internal one behind a
gateway:

- `static-header` sends one header on every call, from
  `gatool.api.credentials.header-name` and `.header-value`, such as an API key.
  The value has to be one an HTTP client can send: a control character, such as
  the line break a secret read from a file ends in, stops startup naming the
  property, and a space inside the value, as in `Bearer <token>`, is fine.
- `client-credentials` obtains a token for GATool itself through a Spring Boot
  client registration named by `gatool.api.credentials.client-registration-id`,
  and the API sees GATool as the caller of every tool. One token is held for the
  application until it expires.
- `token-exchange` hands the caller's token to the issuer with
  `gatool.api.credentials.audience`, `gatool.api.credentials.resource`, or both,
  and the API receives a token issued for itself that still names the real
  user. The registration's grant type is
  `urn:ietf:params:oauth:grant-type:token-exchange`. GATool keeps one exchanged
  token per inbound token in memory, looked up by a hash of that token, and
  drops the expired ones each time it saves one, holding at most ten thousand.
  Each entry holds the exchanged token's text, so a busy server holds tens of
  megabytes of tokens at that cap. A caller who steps up to a
  token with more scopes therefore gets an exchange of its own, and the API
  sees the new scopes on the next call. A call on a thread without the caller's
  token, one made outside the secured endpoint, fails as a tool error that names
  the strategy and says the arguments are not the cause, and so does a forwarded
  call and a token request the issuer refuses, which carries the issuer's error
  code. The exchange names the caller's token
  as an access token, which is the type RFC 8693 gives a token the receiving
  authorization server issued, so the registration's token endpoint has to
  belong to the issuer that signed the inbound token. An
  application that delegates in place of impersonating declares its own
  `ApiCredentialStrategy` bean around Spring Security's
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

The two OAuth2 strategies need `spring-boot-starter-security-oauth2-client`,
and startup stops naming it, the registration, or a grant type that does not
match the strategy. Token exchange and the forwarded token need an
authenticated caller, so both stop startup over stdio and while the
unauthenticated switch is on. A bean of `ApiCredentialStrategy`, a Spring
`ClientHttpRequestInterceptor` under a name, replaces every built-in strategy
for an API that authenticates in another way.

The whole chain runs in the test suite against Keycloak 26.7.3 in a container:
a user signs in, the endpoint accepts the realm's tokens and refuses a missing
scope until the client steps up, and the movie API receives the exchanged token
and the client credentials token. The tests skip themselves where Docker is
absent.

## Untrusted content and prompt injection

A model reads the text a tool publishes and the text a tool returns, and it
may follow an instruction it finds in either. The table lists the text that
reaches a model through GATool and who writes it:

| What a model reads | Who writes it |
| --- | --- |
| Tool names, titles and descriptions | The operation files in your repository. A file without a comment takes its description from the schema, and under `gatool.dev.experimental.generate-tools` the schema names and describes every generated tool |
| The input schema, and the output schema where a tool publishes one | The variables of the operation file, with the descriptions, enum values, defaults and deprecation reasons of the schema and the scalar fragments of your configuration |
| Results | The API. A result holds the data the operation selected, and part of that data is text the users of the API wrote, such as the comment of a review |
| Error text | The API: the `errors` of a GraphQL response, and the first 2,000 characters of the body of a refused call |
| The MCP `instructions` | GATool, as a fixed text, or your `spring.ai.mcp.server.instructions` |

The API owner writes the schema. A schema fetched from a registry is
written by whoever can publish to that registry, so access to the registry
decides what a model reads as tool text.

**What GATool does.**

- A tool sends the document its operation file holds. The model chooses a tool
  and writes its arguments, and the arguments travel as GraphQL variables beside
  the document, so GraphQL text inside an argument stays a value. A model
  writes a document through `executeGraphql` alone, which exists while
  `gatool.dev.experimental.generate-tools` is `dynamic-three-step`.
- A result is written again as `data` and `errors`, with its strings as the API
  returned them. GATool replaces the value of
  `gatool.api.credentials.header-value` wherever a result or an error text
  holds it, and a bearer token in the `errors` and in the body of a refused
  call. An `ApiCredentialStrategy` bean of the application's own that sends
  another header, such as an API key under a name of its own, is outside both
  rules: an API or a proxy that echoes the request into an error hands that
  value to the model, so such a strategy needs an API that keeps request
  headers out of its errors.
- `gatool.api.max-response-size` bounds what GATool reads from the API, and
  `gatool.results.max-characters` bounds the text one call returns to a model.
- The default folder of the schema cache is checked before it is used, as
  "Reading the schema from a registry" describes.
- On the MCP endpoint, a call needs the scopes its operation file lists, so the
  token of a caller bounds the tools an injected instruction can reach.
- A mutation is a tool like a query. It publishes `destructiveHint`, and a
  query publishes `readOnlyHint`, so a client can tell the two apart.

**What GATool leaves to the client and the application.**

- GATool does not look for instructions in a result, in an error text or in the
  schema. The client or the application that holds the model treats what a
  tool returns as untrusted input. Scopes bound what such text can cause: give
  the tools that write a scope of their own, and leave that scope out of the
  token of a caller whose model reads user-written text.
- An error's `extensions` reach the model as the API wrote them, and some APIs
  put an exception class or the lines of a stack trace there. The
  configuration of the API is where a team switches that off.
- GATool does not ask a person before a mutation runs. Over MCP that question
  belongs to the client: MCP asks a client to keep a person in the loop and to
  confirm a sensitive operation, and the hints above tell a client which tools
  write.
- In process, Spring AI's `ToolCallingManager` calls the callback that a
  model's response names, so a mutation under `gatool/in-process/` runs as soon
  as the model asks for it. Spring AI's `ToolMetadata` carries `returnDirect`
  alone, so the application reads which tools write from the catalog, where
  `GAToolCatalog.inProcessTools()` lists each tool with `readOnly()`. An
  application that wants a person to agree first wraps the callbacks of those
  tools in a `ToolCallback` of its own, which asks and then hands the call to
  GATool's.

## Checking your tools in CI

Two assertions come with `gatool-spring-boot-starter-test`, added in test scope
beside `spring-boot-starter-test`:

```xml
<dependency>
    <groupId>io.gatool</groupId>
    <artifactId>gatool-spring-boot-starter-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

**The operation files validate.** The checks startup runs on the operation
files and the schema, from a plain JUnit test, without an issuer, a running
application or a reachable schema URL, so a broken file fails the build that
changed it. Five stops depend on the rest of the configuration and run at
startup alone: a tool name that clashes with a dynamic tool, one that clashes
with a tool the application declares, the scope rules under a shared
credential, the scopes of `gatool.mcp.stdio.granted-scopes`, and an image mime
type beside an output schema. A test that starts the context covers those:

```java
@Test
void operationFiles_shouldValidate() {
    OperationFilesAssert.assertValid(Path.of("src/main/resources/movies.graphqls"),
            List.of(Path.of("src/main/resources/gatool/mcp")), List.of(), CheckSettings.defaults());
}
```

The second argument lists the MCP operation folders and the third the
in-process ones, each read under its own side's rules, so an application with
one side passes `List.of()` for the other. A listed folder that does not exist
fails the assertion naming it, so a renamed folder cannot pass with zero tools.
The check follows a symbolic link to a folder and lists the tools in path
order, the way startup reads a location. The fourth argument is a
`CheckSettings`, which carries the naming strategy, the output schema switch
and the scalar fragments an application configures, so the check runs under
the same settings startup does. An application on every default passes
`CheckSettings.defaults()`, one with `gatool.naming.strategy: snake-case`
passes `CheckSettings.defaults().withNamingStrategy(ToolNamingStrategy.snakeCase())`,
and one that publishes output schemas or declares scalar fragments builds its
settings from the same `with...` methods.

**The tool contract matches its snapshot.** Tool names are what agents call and
what client configurations record, so a renamed tool or a changed input schema
shows up in review as a changed file:

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

A missing snapshot is written, and the test then fails asking for the file to be
committed, so a run in CI without it is a failure. A deliberate change is
accepted with `-Dgatool.snapshot.update=true`, which rewrites the file for review.
The snapshot records each tool's scopes beside its schemas, since a changed scope
list is a contract change a client notices, and it is written with line feeds on
every platform. The path is resolved against the JVM's working directory, which
is the module directory under Maven, Gradle and the IDEs. Under Gradle the flag
reaches the test JVM through `systemProperty` on the test task.

## Running in a container

The response cap determines how much memory a call can use. The figures below were
measured with the cap raised to 10MB. One refused 9 MB result
allocates about 87 to 111 MiB and holds about 120 MiB of heap while in flight,
because the body is read, parsed, and written as text before the result limit
refuses it, and eight concurrent 9 MB responses peaked at 1,088 to 1,331 MiB.
`gatool.api.max-response-size=256KB` brings one such call to about 10 MiB. JDK
21 in a 512 MiB container with two CPUs picks SerialGC with a 128 MiB heap, and
one 9 MB response under a cap raised to 10MB fills it.

The default cap is 1MB, which is the measured safe point for a 512 MiB
container, together with `MALLOC_ARENA_MAX=2` in the environment and
`-XX:MaxRAMPercentage=60`, at 350 MiB RSS for 64 concurrent 1 MB results. Add
`-XX:+ExitOnOutOfMemoryError`, because an `OutOfMemoryError` on a Tomcat or
client thread leaves the health endpoint answering `UP` while calls fail. The
sizing rule: heap per call in flight is about ten times `max-response-size`
during the parse, times the concurrency the deployment allows, which is
Tomcat's 200 threads by default. The 350 MiB figure above was measured at 64
concurrent calls, so a deployment on Tomcat's 200 threads lowers
`server.tomcat.threads.max` to stay near it. GATool does not limit concurrent
calls in this release, so the deployment bounds them with
`server.tomcat.threads.max` or at the gateway.

The schema fetched from a URL is bounded by `gatool.api.schema.max-size`, 10MB
by default, so a schema of a few megabytes loads while the response cap stays
at the size the heap allows.

On the stateful transport, a connected client holds a listening stream open,
which Spring Boot's graceful shutdown counts as an active request. GATool ends
the sessions before that shutdown begins, so a stop with connected clients does
not wait out `spring.lifecycle.timeout-per-shutdown-phase`. A client that was
connected opens a new session against the next instance.

## What this release does

- Tools from query and mutation operations. A query publishes `readOnlyHint`
  and `idempotentHint` as true and `destructiveHint` as false; a mutation
  publishes the three the other way round, which are MCP's own defaults.
- Streamable HTTP, stateless by default and stateful with
  `spring.ai.mcp.server.protocol: STREAMABLE`, where `gatool.mcp.sessions.max-count`
  and `gatool.mcp.sessions.idle-timeout` bound the sessions the server keeps, and
  stdio for a local agent.
- An OAuth 2.1 resource server on the MCP endpoint with Spring Security, with
  the scopes each operation file lists checked on every call and named in every
  challenge, or unsecured behind the explicit switch above.
- A static header, client credentials or token exchange towards the GraphQL
  API, proved against Keycloak.
- Both an embedded GraphQL API, through Spring for GraphQL in the same
  application, and a remote one over HTTP.
- On the stateful transport, GATool stops startup where the MCP server bean
  was built without immediate execution, and refuses a tool call that arrives
  off the request thread, both described under
  [The caller and the request thread](#the-caller-and-the-request-thread).

## Compatibility

GATool is at 0.x. Until 1.0 these rules hold:

- A patch release, such as 0.1.1 after 0.1.0, publishes the same tools for the
  same schema and operation files, and keeps the public Java API binary
  compatible. The exceptions are a security fix and a fix that MCP or GraphQL
  requires, and the release note names each one.
- A minor release, such as 0.2.0, may change the tool contract, the public Java
  API, a property or a default. Its release note lists every such change.
- Everything under `gatool.dev.experimental` may change in any release.

The tool contract is what an agent and its client see: the name, title,
description, input schema, output schema and annotations of each tool, the
shape of a tool result, and the name and tags of the
[`gatool.call` observation](#configuration).

The public Java API is every public type in these packages:

| Module | Packages |
| --- | --- |
| `gatool-core` | `io.gatool.core.check`, `io.gatool.core.model`, `io.gatool.core.naming`, `io.gatool.core.search` |
| `gatool-spring-boot` | `io.gatool.boot`, `io.gatool.boot.autoconfigure`, `io.gatool.boot.execution` |
| `gatool-mcp-spring-boot` | `io.gatool.boot.mcp.autoconfigure`, `io.gatool.boot.mcp.limit`, `io.gatool.boot.mcp.security` |
| `gatool-in-process-spring-boot` | `io.gatool.boot.inprocess`, `io.gatool.boot.inprocess.autoconfigure` |
| `gatool-spring-boot-test` | `io.gatool.boot.test` |

A package with `internal` in its name may change in any release, and the
published Javadoc leaves those packages out.

`GAToolProperties` and the three classes beside it (`GAToolApiProperties`,
`GAToolMcpProperties` and `GAToolDevProperties`) have a narrower promise than
the rest of those packages. Their contract is the properties themselves: the
names, types and defaults under `gatool.*`, which the rules above cover. Their
getters and setters exist for Spring Boot's binder and may change in any
release, which is the rule Spring Boot states for
[its own properties classes](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.java-bean-binding).

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
the release before stays for at least one minor release, and code that takes a
record apart with a record pattern changes with the record.

## Requirements

Java 21 or newer, Spring Boot 4.1.x and Spring AI 2.0.x from 2.0.1, which fixes
CVE-2026-59279 and CVE-2026-59318. The auto-configuration is compiled against
Spring Boot 4.1 and Spring AI 2.0 APIs, so Spring Boot 4.0.x and Spring AI 1.x
are unsupported. Startup warns where this application runs a Spring Boot or a
Spring AI line this release did not test, naming the tested lines and the
versions found. The MCP starter needs Spring MVC, a servlet
web application: every guard in front of the endpoint is a servlet filter. With
both stacks present, Boot runs the servlet stack, GATool serves MCP there, and
startup warns naming the `WebFilter`, `SecurityWebFilterChain` and reactive
`RouterFunction` beans that stay unserved; with
`spring.main.web-application-type=reactive` set, startup stops with a message
that says so and points at the in-process starter, whose tools run without
those filters. The endpoint reads only JWT access tokens. The build runs on
Java 21 and Java 25, and the release build runs on Java 21.

**This release runs on the JVM.** GATool has yet to be run in a GraalVM native
image, and the starters ship without runtime hints for the operation folders.
With `gatool.dev.experimental.generate-tools` set to `dynamic-three-step`,
startup stops inside a native image and names the property. For a faster start
on the JVM, Spring Boot documents the AOT cache on Java 25 and class data
sharing on Java 21 under
[AOT Cache and CDS](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html).

**Tomcat's version comes from your build.** The MCP starter brings Tomcat through
Spring AI's WebMVC server starter and leaves its version to the Spring Boot
release your application builds on, so an application on Spring Boot 4.1.1 runs
Tomcat 11.0.24. Three advisories affect that version: GHSA-9xv2-5v5q-p794 in the
DIGEST authenticator, GHSA-h3x4-894j-xpx5 in the FORM authenticator, and
GHSA-gcx9-497g-6cp6 in access control. Tomcat 11.0.25 fixes all three. GATool's
own build and tests run on Tomcat 11.0.26, and the SBOM of a release lists
11.0.26 because it records what GATool was built with. Your application's
dependency tree shows the version it runs:

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
`tomcat-embed-el` and `tomcat-embed-websocket` at 11.0.26 ahead of the import,
because Maven resolves an imported POM's properties against that POM and a
property in your build does not reach it. With Gradle and Spring's dependency
management plugin, `ext['tomcat.version'] = '11.0.26'` sets it.

## Standards

GATool targets MCP revision 2025-11-25. A filter on the MCP endpoint applies the
transport rules that the MCP Java SDK and Spring AI leave open. It checks the
`Origin` header and the protocol version, and caps the request body. It also
refuses a body it cannot read the way the server would: one declared in a
charset other than UTF-8, one whose media type is not JSON, one that fails to
parse, one that is not a JSON object, one whose media type carries a wildcard,
one whose id is something other than a string or an integer, one whose method
is not a string, one whose `jsonrpc` member is missing or other than `"2.0"`,
one whose `params` is not an object, a `tools/call` whose `arguments` is not an
object, and a JSON-RPC batch. The `Origin` check runs before the body is read,
and so does the answer to a request whose HTTP method the transport does not
route, `PUT` on both transports and `DELETE` on the stateless one: `405`, an
`Allow` header and a JSON-RPC error. A request without `MCP-Protocol-Version`
is served as if it carried `2025-11-25`, where the specification says the
server should assume 2025-03-26: assuming that revision would refuse the call,
because this release cannot read the JSON-RPC batches it requires, and an
explicit `2025-03-26` header is refused with `400`. A `tools/call` naming a
tool outside printable ASCII is refused as well, inside a `200` so that a
client keeps its session, because such a name could reach a challenge header.
The scope check and the server therefore always read the same message.

The current MCP revision is
[2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/changelog),
which removes the `initialize` handshake and sessions and requires
`server/discover`. This release does not implement it, so in that revision's
terms GATool is a legacy server, and its
[compatibility matrix](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning#compatibility-matrix)
gives the outcome for each client. A client that supports both revisions falls
back to `initialize` and works: GATool answers an `initialize` asking for
`2026-07-28` with `2025-11-25`, and any other request carrying that revision
with `400` and a JSON-RPC error. A client that supports 2026-07-28 alone
cannot connect.

An application's own `McpStatelessServerTransport` or
`McpStreamableServerTransportProvider` bean is the transport the server is built
on, and GATool's own backs off. Such a transport answers the handshake from its
own revision list, and the filter still refuses a superseded revision on every
request.

Two deviations are inherited from Spring AI 2.0.1 and MCP Java SDK 2.0.0. Every
SSE event id on a `POST` response stream carries the session id, where the
specification asks for an id unique across the session. On the stateful
transport, a request for a method the server does not serve, `resources/list`
on a server without the resources capability, gets its `-32601` as an SSE event
and the stream then stays open, where the specification says the server should
end it, so a client waits until its own timeout. Both are SDK behaviour, and a
test pins the second until the SDK closes the stream. Two more
are inherited the same way: a `Last-Event-ID` header opens a stream without a
replay, so resumability is unsupported, and the SDK does not send the priming
SSE event the specification recommends. The `Accept` header is normalised ahead
of the transports: q-values are dropped, and `*/*` or a missing header reads as
the two media types MCP names, so the refusal is left for a client whose header
leaves both types out.

Pagination is the rule GATool leaves to the SDK. Every `tools/list` answer holds the
whole list and leaves `nextCursor` out, so MCP's pagination rule would have a
cursor refused with -32602. MCP Java SDK 2.0.0 implements pagination in its schema
and in its client and leaves the server half out, and the filter is the only place
GATool has for such a rule, which would make it an HTTP rule while GATool serves stdio
as well. A client that sends a cursor reads every tool either way.

Every build runs the official
[MCP conformance suite](https://www.npmjs.com/package/@modelcontextprotocol/conformance),
version 0.2.0-alpha.11, against a fixture application, with
`--requirements 2025-11-25`, once in stateless mode and once in stateful mode.
This release claims the tool contract and the transport rules, and these
scenarios pass in both modes:

`server-initialize`, `ping`, `tools-list`, `tools-call-simple-text`,
`tools-call-image`, `tools-call-audio`, `tools-call-embedded-resource`,
`tools-call-mixed-content`, `tools-call-error` and `dns-rebinding-protection`.

The stateful run also passes `logging-set-level`, `tools-call-with-logging`,
`tools-call-with-progress`, `server-sse-multiple-streams` and the suite's own
`server-session-lifecycle`, because a session lets the server reach the client.

The suite counts a baselined failure as a failure, so
[conformance-baseline.yml](conformance-baseline.yml) lists all twenty that the
stateless run leaves out and
[conformance-baseline-stateful.yml](conformance-baseline-stateful.yml) the
twelve of the stateful run, each with its reason. They fall into three groups:

- **Resources, prompts and completions.** GATool turns GraphQL operation files into
  tools, and an application declares its own resources and prompts when it wants
  them, so the fixture declares tools alone.
- **Sampling, elicitation and multiple streams.** The first two ask the server to
  call the client, and the third wants several streams open in one session. A
  stateless server answers a request and closes, and Spring AI 2.0.1 leaves a
  tool that takes a request context unregistered there. The stateful run passes
  all of them.
- **Progress, logging and `logging/setLevel`, in stateless mode.** Spring AI
  2.0.1 skips a tool method that takes a request context on a stateless server,
  and the stateless server answers `logging/setLevel` with "Missing handler for
  request type". All three pass in stateful mode.

A stateful server keeps a session until its client deletes it, the server
evicts it after `gatool.mcp.sessions.idle-timeout` of silence, or the application
stops. It keeps at most `gatool.mcp.sessions.max-count` at once, and answers `503`
to a client that initializes past the cap. Spring AI's own auto-configuration
leaves both settings at the transport's defaults, 100,000 sessions without
eviction, so GATool builds the transport bean itself. Startup
warns while stateful runs without authentication, because any caller can then
fill the cap.

That cap counts the whole server, so one caller can fill it and leave every
other caller with `503`. Authentication bounds who may do it and leaves the
denial attributable, and it does not prevent it. Set
`gatool.mcp.sessions.max-count-per-caller` to bound what one caller takes: a
caller past their own cap reads `429` with `Retry-After` and the wait, the way
the rate limiter refuses, while the server cap keeps answering `503` because
that one is about the server. The per-caller cap defaults to 100, a tenth of
the server cap; `0` turns it off, and a deployment whose workers share one
service principal sets it to the number of sessions those workers hold.
Startup warns naming both properties where a secured stateful server leaves
the per-caller cap off or at `gatool.mcp.sessions.max-count` or above, because
either setting still leaves one caller free to fill the whole server cap. One
MCP client normally holds one session, and a caller reconnecting may briefly
hold two, so a small number suits a deployment where each caller runs one
client.

### Taking the endpoint's security over

Excluding `GAToolMcpSecurityAutoConfiguration` removes the one place that
applies `McpServerSecurityConfigurer`, and with it the per-tool scope check.
Spring Boot's own resource server chain then serves the endpoint under a rule
that asks for a valid token alone, so any token for the audience calls every
tool, the scoped ones included. Startup stops and says so. The same stop applies
when Spring Security's filter is absent as well, because Boot's web security
auto-configuration was excluded too, since the endpoint would then answer every
caller.

An application that wants to own the endpoint's security declares a chain of its
own and applies the configurer in it, which is the supported route and starts
normally:

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

Pull requests are welcome, and [CONTRIBUTING.md](CONTRIBUTING.md) explains the
sign-off and the build. To report a vulnerability, follow
[SECURITY.md](SECURITY.md).

## Licence

[Apache License 2.0](LICENSE).
