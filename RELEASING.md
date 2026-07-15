# Releasing `reputation-pool-core`

Only **`reputation-pool-core`** is published to Maven Central, as
`io.github.preagile:reputation-pool-core`. The `adapters`, `persistence`, and `server` modules are
not published — they are consumers of the core, not artifacts. Internal test fixtures (the
`testFixtures` source set: `SettableClock`, `DomainArbitraries`) are deliberately excluded from the
publication (see the `withVariantsFromConfiguration { skip() }` block in
`reputation-pool-core/build.gradle.kts`), so no test-only code reaches Central.

Publishing is driven by the [`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/)
plugin (version `0.37.0`), already configured in `reputation-pool-core/build.gradle.kts`:
`publishToMavenCentral()` targets the Central Portal, `signAllPublications()` GPG-signs every
artifact, and the version comes from the `releaseVersion` Gradle property at publish time (default
`0.1.0-SNAPSHOT`). The build file needs no edits to cut a release — you pass the version on the
command line.

This procedure is run **by a maintainer on a machine that holds the Central Portal credentials and
the GPG signing key**. It is not part of CI today (see "Future" at the end).

## 1. Prerequisites

### Central Portal (Sonatype) credentials

A [Central Portal](https://central.sonatype.com/) account with publish rights to the
`io.github.preagile` namespace, and a **user token** generated from the portal (Account → Generate
User Token). The token's username and password are supplied to Gradle as:

| Gradle property        | Purpose                    |
|------------------------|----------------------------|
| `mavenCentralUsername` | Central Portal token username |
| `mavenCentralPassword` | Central Portal token password |

### GPG signing key

Central requires every artifact to be GPG-signed. Provide the key in **one** of two ways.

File-based (a key in a local keyring):

| Gradle property             | Purpose                              |
|-----------------------------|--------------------------------------|
| `signing.keyId`             | 8-char (short) key id                |
| `signing.password`          | key passphrase                       |
| `signing.secretKeyRingFile` | path to the secret keyring file      |

In-memory (an exported ASCII-armored key — preferred for CI/ephemeral machines):

| Gradle property               | Purpose                                          |
|-------------------------------|--------------------------------------------------|
| `signingInMemoryKey`          | the exported ASCII-armored private key           |
| `signingInMemoryKeyId`        | key id (optional; only if the keyring has several) |
| `signingInMemoryKeyPassword`  | key passphrase (if the key is encrypted)         |

### Where to put the properties

Any of these works; pick one and do not commit secrets:

- `~/.gradle/gradle.properties` (never the repo's `gradle.properties`), or
- environment variables with the `ORG_GRADLE_PROJECT_` prefix, e.g.
  `ORG_GRADLE_PROJECT_mavenCentralUsername`, `ORG_GRADLE_PROJECT_signingInMemoryKey`, etc., or
- `-P` flags on the command line (avoid for secrets — they land in shell history).

## 2. Pre-flight

1. Be on `main` at the exact commit you intend to release, clean working tree, up to date with
   `origin/main`.
2. Confirm `CHANGELOG.md` has a `[0.2.0]` section describing this release.
3. Build the whole repo green: `./gradlew build`.

## 3. Publish

The DSL is `publishToMavenCentral()` **without** automatic release, so publishing is a two-step,
manually-confirmed process — the recommended default for the first releases:

```bash
./gradlew :reputation-pool-core:publishToMavenCentral -PreleaseVersion=0.2.0
```

This builds, signs, and uploads a **deployment** to the Central Portal but does **not** release it.
Then go to <https://central.sonatype.com/publishing/deployments>, verify the deployment validated,
and click **Publish** to release it to Maven Central.

If you would rather have Gradle release automatically once validation passes (no portal click), use
the combined task instead:

```bash
./gradlew :reputation-pool-core:publishAndReleaseToMavenCentral -PreleaseVersion=0.2.0
```

`publishToMavenCentral` uploads only; `publishAndReleaseToMavenCentral` uploads **and** auto-releases
the deployment (equivalent to setting `publishToMavenCentral(automaticRelease = true)` in the build
file). Both take the same `-PreleaseVersion` argument. Use the manual (`publishToMavenCentral`) path
unless you are confident the deployment is correct — releasing is irreversible; a version cannot be
re-published once released.

> Note: `-PreleaseVersion=0.2.0` sets a release version. Omitting it defaults to
> `0.1.0-SNAPSHOT`, which is not a valid Central release — always pass the flag.

## 4. Tag the release

**This is the first git tag in the repository — it starts the release-tagging discipline.** After the
artifact is released to Central, tag the exact commit you published, then push the tag:

```bash
git tag -a v0.2.0 -m "reputation-pool-core 0.2.0"
git push origin v0.2.0
```

The tag names a released, immutable contract. It is what lets follow-up work (e.g. the `buf breaking`
baseline) point at a released version instead of the moving `main`.

## 5. Post-publish smoke check

Central propagation can take a few minutes to tens of minutes. Once the artifact is searchable at
<https://central.sonatype.com/artifact/io.github.preagile/reputation-pool-core/0.2.0>, verify a fresh
consumer resolves and compiles against the public API.

Gradle:

```bash
mkdir -p /tmp/rp-smoke && cd /tmp/rp-smoke
cat > build.gradle.kts <<'EOF'
plugins { `java-library` }
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
dependencies { implementation("io.github.preagile:reputation-pool-core:0.2.0") }
EOF
mkdir -p src/main/java
cat > src/main/java/Smoke.java <<'EOF'
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;

public class Smoke {
    public static void main(String[] args) {
        // resolves the jar and compiles against a public API call
        var id = new ResourceId(ResourceKind.PROXY, "10.0.0.7:8080");
        System.out.println(id);
    }
}
EOF
gradle compileJava --refresh-dependencies
```

Maven equivalent — a single dependency on `io.github.preagile:reputation-pool-core:0.2.0` and a
`mvn -q compile` against the same `ResourceId` call — is fine too. A clean compile against a public
type confirms the artifact and its POM resolved.

## Future

This procedure can move to a **tag-triggered GitHub Actions release workflow**: pushing `v*` runs
`publishAndReleaseToMavenCentral` with credentials from repository secrets. Not built now —
deliberately kept manual while the release cadence is low and the credentials live on one machine.
