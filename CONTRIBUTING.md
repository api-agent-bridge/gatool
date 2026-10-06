# Contributing

Thank you for looking at this project. Issues and pull requests are all
welcome.

## Sign your commits

Every commit needs a `Signed-off-by` trailer, which certifies the
[Developer Certificate of Origin](https://developercertificate.org). Git adds it
for you:

```bash
git commit -s -m "Your message"
```

The trailer says that you wrote the change, or that you have the right to submit
it under the project's licence. Spring Boot has asked contributors for the same
trailer since January 2025, and this project follows that practice.

## Build and test

The build needs Java 21 or newer, and it uses the Maven wrapper in the
repository:

```bash
./mvnw clean verify
```

That compiles every module, runs the tests, and runs the end-to-end tests that
call the starters the way an application does. The build treats compiler
warnings as errors, and it runs NullAway over the main code, so a nullability
mistake fails the build.

To run the tests of one module, add `-am`, so that Maven builds the modules it
depends on from your sources:

```bash
./mvnw test -pl tests/skeleton -am -Dtest=StatefulSessionCapTests -Dsurefire.failIfNoSpecifiedTests=false
```

Without `-am` Maven takes those modules from `~/.m2`, where a jar can be older
than your sources, and the test then runs against code you have since changed. A
run like that once reported ten sessions served at a cap of two, from a filter
that the sources had already replaced.

Continuous integration runs the same command on Java 21 and Java 25. Users run
different Java versions, which is why both are tested, and they all run the
graphql-java that Spring Boot manages, so a new one arrives with a Boot upgrade
and the ordinary build covers it then.

## Coverage

`./mvnw clean verify` writes a coverage report for each module and one report
over the whole library:

```
coverage/target/site/jacoco-aggregate/index.html
```

Read the aggregate, because `gatool-spring-boot`, `gatool-mcp-spring-boot` and
`gatool-in-process-spring-boot` are exercised mostly from `tests/skeleton`,
which is a different module, so read on its own each looks close to uncovered
when its auto-configuration runs on every one of those tests. The `coverage`
module depends on the five published code modules for their classes and on the
three test modules with test scope for their execution data, which is what
brings the two together.

The build fails below a floor of 90% of lines and 80% of branches, measured
over `gatool-core` and the three auto-configuration modules with the same
execution data the aggregate reads. `gatool-spring-boot-test` appears in the
report and stays outside the floor. `jacoco:check` reads one
execution data file and one classes directory, so the `coverage` module merges
the eight execution data files into `coverage/target/jacoco-aggregate.exec`,
unpacks the four modules' classes into its own `target/classes`, and checks one
against the other. A run with `-DskipTests` leaves the execution data unwritten
and skips the check, and a build without `clean` keeps classes a module has
since deleted in that directory and counts them as uncovered, which is one more
reason the command is `./mvnw clean verify`.

## SonarQube, locally

Sonar runs in a container, and it reads the JaCoCo report the build already
writes:

```bash
docker run -d --name gatool-sonar -p 9000:9000 sonarqube:community
```

Open http://localhost:9000, sign in as `admin` / `admin`, set a new password,
and create a token under My Account, Security. Then:

```bash
./mvnw clean verify
./mvnw sonar:sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=<token>
```

The two commands are separate on purpose: `verify` produces the coverage report,
and the scanner reads it through `sonar.coverage.jacoco.xmlReportPaths`, which
the root POM points at the aggregate.

The container keeps its data in Docker volumes, so stopping it and starting it
again keeps the history. `docker rm -f gatool-sonar` removes it.

## What a change needs

- **A test.** A fix needs a test that fails without it. A feature needs tests
  that cover the behaviour a user relies on.
- **A reason in the code.** Where the code alone leaves its reason open, a
  comment says why the code does what it does.
- **The tool contract stays stable.** Tool names, input schemas and result
  shapes are what an agent depends on, and a snapshot test guards them. A change
  there adds its line to `CHANGELOG.md`, under "Unreleased", and it waits for a
  minor release.
- **A public record that grows keeps its old constructor.** A public record
  such as `GraphQlExecutionRequest` that gains a component keeps its earlier
  constructor compiling for at least one minor release, the way
  `CheckSettings` grows. A record pattern that matches every component of
  such a record still changes with it, because the pattern names each
  component.
- **A public type with more than a few values gets a builder.** `GATool` is
  built through `GATool.builder()`, so each value is named where it is set and
  a value added later is one more method.

## Pinning a dependency above Spring Boot's version

Spring Boot manages the version of most dependencies this project uses, and a
newer one arrives with a Boot upgrade. The root `pom.xml` sets a version of its
own in one case today: Tomcat, because advisories affect the version Spring Boot
4.1.1 manages, a later version of the same line fixes them, and the Boot release
that manages the fixed version has yet to arrive. A pin for another dependency
needs the same three reasons, and it follows the same steps:

1. **A property for the version** in the root POM's `properties`, such as
   `tomcat.version`. The comment above it names each advisory by its id, the
   version Boot manages, the version that fixes the advisories, and the
   condition under which the pin goes away, which is a Boot release that manages
   the fixed version or a later one.
2. **A managed entry for each artifact** in the root POM's
   `dependencyManagement`, ahead of the import of `spring-boot-dependencies`,
   with the property as its version. The entries are needed because this
   project imports Boot's POM, and Maven resolves the properties of an imported
   POM against that POM, so a property of the same name in this build leaves
   Boot's managed version as it was. The comment above the entries repeats the
   condition.
3. **A look at what the build resolves.** The tests have to run on the pinned
   version, and this shows the version each artifact resolves to:

   ```bash
   ./mvnw dependency:tree -Dincludes=org.apache.tomcat.embed -pl tests/skeleton
   ```

4. **A paragraph for applications** in the README's Requirements section, and
   a sentence in the section on the software bill of materials in
   `SECURITY.md`. The pin reaches this project's own build and tests. An
   application resolves its versions from its own build, so with the MCP
   starter on Spring Boot 4.1.1 it runs the Tomcat that Boot manages. The
   README names the advisories and the version that fixes them, and it shows
   how an application sets that version under the Boot parent, under the
   imported BOM and under Gradle.

`RELEASING.md` checks at each release whether a pin can go. Removing one takes
out the property, the managed entries and the paragraphs of step 4 together.

## Style

The code follows Spring's conventions, and the build checks them:

- **Spring's formatter.** [spring-javaformat](https://github.com/spring-io/spring-javaformat),
  the formatter Spring Boot formats its own code with, runs in the `validate`
  phase and fails the build on a file it would change. `./mvnw spring-javaformat:apply`
  rewrites the files; the plugin's page lists the IDE plugins that format on save.
- **Spring's Checkstyle rules.** `src/checkstyle/checkstyle.xml` holds
  `SpringChecks`, the rule set Spring Boot applies to itself: the Apache licence
  header on every file, Spring's import order, `this` on every field access,
  Javadoc with `@param` and `@return` on every documented method, nested types
  after the methods of their enclosing type, lambda parameters in parentheses,
  ternaries written as `(a != b) ? y : n`, and test classes named `FooTests`.
  `src/checkstyle/checkstyle-suppressions.xml` grants tests the same exemptions
  Spring Boot grants its own.
- **Three size limits of GATool's own.** At most 7 parameters for a method, a
  constructor or a record, 60 lines for a method or a constructor, and 1,000
  lines for a file, on main code. A type with more than seven values is built
  through a builder, as `GATool` is.
- **Imports, never full class names**, which the compiler enforces through Error
  Prone's `UnnecessarilyFullyQualified` check.

Comments and documentation explain the mechanism plainly. They avoid citing
documents a reader cannot open, and they avoid em dashes.

## Licence

Contributions arrive under the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0), the licence
this project publishes under.
