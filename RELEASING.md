# Releasing GATool

The maintainer's runbook for publishing to Maven Central. Everything here needs
credentials or accounts that belong to the maintainer.

## Before the first release

The first release needs each of these:

1. **A Sonatype Central account** at [central.sonatype.com](https://central.sonatype.com).
2. **The `io.gatool` namespace, verified.** Central asks for a DNS TXT record on
   `gatool.io` carrying a token it shows you. Verification takes minutes once the
   record propagates.
3. **A GPG signing key.** Central requires a signature on every artifact:
   ```bash
   gpg --full-generate-key
   gpg --list-secret-keys --keyid-format=long
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```
4. **The repository public**, at the address in the POM's `scm` block.

Then put the Central token in `~/.m2/settings.xml`, where the server id matches
`publishingServerId` in the release profile:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

## Every release

**Before step 1, check whether a pin can go.** Where the root POM pins a
dependency above the version Spring Boot manages, as `tomcat.version` does
today, the comment at the property names the condition under which the pin goes
away. `CONTRIBUTING.md` lists what is removed with it.

**1. Check the build, on Java 21.** A release is built on Java 21, the oldest
version the library supports and the one CI publishes the release artifacts
with, so the class files and the javadoc come from the same toolchain on every
machine. The JDK also lands in every jar's manifest as `Build-Jdk-Spec`, so
anyone who rebuilds a release from its tag to check the bytes has to use Java
21 too; a build on Java 26 stamps `Build-Jdk-Spec: 26` and the jars stop
matching. The `release` profile stops the build on any other JDK. `./mvnw -v`
prints the Java in use, and `JAVA_HOME` moves it. Both builds have to pass:

```bash
./mvnw clean verify
./mvnw clean verify -Prelease -Dgpg.skip=true
```

The second builds the javadoc jar, the sources jar and the CycloneDX SBOM for
each module, which is what a release publishes beside the code. Run it online:
with `-o` Maven skips the SBOM goal, says so in one warning for each module,
and the build still passes. The javadoc of the published modules is written
without a warning, so a javadoc warning in the log of this build is new.

**2. Check the conformance result in CI.** CI runs the official MCP conformance
suite on every commit, in the jobs `MCP conformance, stateless` and
`MCP conformance, stateful`. Open the CI run of the commit the release is built
from and check that both jobs are green. A red job stops the release. A job
turns red when a scenario fails outside the baseline, and also when a baselined
scenario starts passing, so the baseline lists only scenarios that fail.

The suite is an npm package whose dependencies are resolved at the time of the
run, so the release reads CI's result and the suite stays off the machine that
holds the signing key and the Central token.

**3. Set the version and the build timestamp, then commit.** The parent
carries the version and every module inherits:

```bash
./mvnw versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
```

The flag keeps the plugin from leaving a `pom.xml.versionsBackup` beside each
POM. Then set `project.build.outputTimestamp` in the root POM to the current
time in UTC, such as `2026-10-01T00:00:00Z`. Every jar entry and the SBOM take
their timestamp from that value, so a rebuild of the release commit gives the
same bytes, and a value left from the previous release would date this one
wrongly. Check that `README.md` names the same version in its dependency
blocks, then commit:

```bash
git commit -am "Release 0.1.0"
```

The tag in step 6 lands on this commit, so the sources the tag points at carry
the released version.

**4. Deploy.** The release profile signs each artifact and uploads the bundle.
The seven fixture, test and coverage modules stay out of it, through
`excludeArtifacts` in the root POM:

```bash
./mvnw clean deploy -Prelease
```

GPG asks for the passphrase unless an agent holds it.

**5. Release the bundle by hand.** The publishing plugin sets `autoPublish` to
false, so the bundle waits in the Central portal. Open
[central.sonatype.com/publishing](https://central.sonatype.com/publishing), read
what the validation says, and publish it. Central keeps every published version
forever, so this last step stays manual on purpose.

**6. Tag the release commit and publish the release note.** The tag goes on
the commit from step 3:

```bash
git tag -s v0.1.0 -m "GATool 0.1.0"
git push origin main v0.1.0
```

The release note is the `CHANGELOG.md` section of this release. Copy its
content, whose sections the last section of this file describes, into a file
and create the GitHub release from the tag with that file as its body,
either on the releases page or with the CLI:

```bash
gh release create v0.1.0 --title "GATool 0.1.0" --notes-file release-note.md
```

**7. Open the next snapshot and commit:**

```bash
./mvnw versions:set -DnewVersion=0.1.1-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Open 0.1.1-SNAPSHOT"
git push
```

## After the first release

japicmp is configured in the root POM's plugin management, with its rules and
the internal packages it skips, and without an execution, because the
comparison needs an earlier release and 0.1.0 is the first. Once 0.1.0 ships,
add an execution of its `cmp` goal to the release profile with `0.1.0` as the
old version, so `0.1.1` onwards fails the build on a breaking change to the
public API. The tool contract
has its own guard: a snapshot test holds the published `tools/list`, and
`HandWrittenToolParityTests` compares a generated tool with a hand-written one.

The eight published modules carry an Automatic-Module-Name in their manifest:
`io.gatool.core`, `io.gatool.boot`, `io.gatool.boot.mcp`,
`io.gatool.boot.inprocess`, `io.gatool.boot.test`, `io.gatool.boot.mcp.starter`,
`io.gatool.boot.inprocess.starter`, and `io.gatool.boot.test.starter`. From the
tag of 0.1.0 these eight names are part of the public contract, so a later
release keeps every one of them.

A minor release runs the `cmp` execution for its report alone, with both
`breakBuildOnBinaryIncompatibleModifications` and
`breakBuildOnSourceIncompatibleModifications` set to `false` in the plugin's
configuration, because a value pinned there overrides the same property passed
on the command line. A patch release runs the same execution with both
switches left at the `true` the plugin management already sets, so the build
fails the moment a change is binary or source incompatible with the release it
patches.

## What a release note covers

A release note says what changed for an application that upgrades, which
scenarios the conformance baseline still carries, and any Spring AI or MCP Java
SDK version the release moves to.
