# Security policy

## Reporting a vulnerability

Report a vulnerability through GitHub's private vulnerability reporting on this
repository, under the Security tab, using "Report a vulnerability". That channel
keeps the report private between you and the maintainers while a fix is
prepared.

Please include the affected version, the configuration that exposes the problem,
and the smallest set of steps that reproduces it. A test or a sample application
helps most.

Please avoid opening a public issue for a vulnerability, because an open issue
tells everyone about the problem before a release carries the fix.

## What happens next

- An acknowledgement arrives within three working days.
- An assessment follows, with the affected versions and a planned fix.
- The fix goes into a release, and the advisory names the reporter unless the
  reporter asks otherwise.

## Which versions receive fixes

Security fixes go to the latest minor release of the 0.x line. An application on
an older 0.x minor upgrades to receive them. Once the project reaches 1.0, the
support windows follow the Spring lines this project tests against.

## Scope

This project is a pair of Spring Boot starters that turn curated GraphQL
operation files into tools for agents. A report is in scope when it concerns:

- the MCP endpoint and the transport rules the starter applies to it
- the tool contract: tool names, input schemas, and what a tool call sends to
  the GraphQL API
- the limits: request body size, result size, and the rate limiter
- the outbound credential strategies and what reaches the GraphQL API
- the unsafe switches, each of which is off by default and logs a warning while
  it is on

A configuration that turns an unsafe switch on is documented as unsafe, so a
finding there is in scope when the documented behaviour differs from what the
starter does.

The README section "Untrusted content and prompt injection" lists the text
that reaches a model through the starters, who writes it, and what the starters
do with it. A report is in scope when a starter does less than that section
says: an argument that changes the document a tool sends, a configured
credential or a bearer token that reaches a result or an error text, a result
past the size limits, a tool that runs for a caller whose token lacks its
scopes, or a schema read from a default cache folder that fails the check.
Text the API returned that a model then follows is the documented behaviour,
because a result is passed on as data, and so is a mutation that runs without
a person's approval, because that question belongs to the MCP client or to the
application. A report about either is out of scope.

## Software bill of materials

Every release publishes a CycloneDX SBOM for each module, which lists the
dependencies that went into the artifact.

The SBOM records the versions GATool was built and tested with. An application
resolves its own versions from the Spring Boot release it builds on, and the two
can differ: GATool builds on Tomcat 11.0.26, and an application on Spring Boot
4.1.1 runs Tomcat 11.0.24 until its own build sets a later one. The README's
Requirements section names the advisories this concerns and shows how to set the
version.
