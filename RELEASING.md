# Releasing `reputation-pool-core`

Several modules are published to Maven Central, sharing one version:

- **`reputation-pool-core`** — `io.github.preagile:reputation-pool-core` (the pure decision engine),
  published since 0.1.0.
- **`reputation-pool-persistence`** (the PostgreSQL adapter: snapshot store + audit trail),
  **`reputation-pool-adapters`**, and **`reputation-pool-server`** (the assembled reference server) —
  published since 0.2.1.
- **`reputation-pool-grpc`** — the gRPC surface, extracted into its own module and published at 0.3.0.

Core's internal test fixtures (the `testFixtures` source set: `SettableClock`, `DomainArbitraries`)
are deliberately excluded from core's publication (see the `withVariantsFromConfiguration { skip() }`
block in `reputation-pool-core/build.gradle.kts`); persistence's `integrationTest` source set is not
part of the published `java` component, so neither ships to Central.

Publishing is driven by the [`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/)
plugin (version `0.37.0`), configured in each publishable module's `build.gradle.kts`:
`publishToMavenCentral()` targets the Central Portal, `signAllPublications()` GPG-signs every
artifact, and the version comes from the `releaseVersion` Gradle property at publish time (default
`0.1.0-SNAPSHOT`). The build files need no edits to cut a release — you pass the version on the
command line. Persistence carries runtime dependencies (the PostgreSQL driver and Flyway) into its
generated POM by design; this is expected for an adapter and does not violate core's zero-dependency
rule (core has none).

Releases are normally cut by the **tag-triggered GitHub Actions workflow**
(`.github/workflows/release.yml`): push a `vX.Y.Z` tag (or use its manual "Run workflow" dispatch and
type the version) and it builds, signs, publishes every module to Maven Central, and cuts a GitHub
Release. The version comes from the tag — no version lives in a committed file — and the Central
credentials and GPG key come from repository Secrets, so no maintainer machine has to hold them (see
"Automated release" at the end). The manual procedure below is the local fallback for publishing by
hand.

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

Both publishable modules are released together under the same version, so pass both tasks in one
invocation:

```bash
./gradlew :reputation-pool-core:publishToMavenCentral \
          :reputation-pool-persistence:publishToMavenCentral \
          -PreleaseVersion=0.2.0
```

This builds, signs, and uploads a **deployment** for each module to the Central Portal but does
**not** release them. Then go to <https://central.sonatype.com/publishing/deployments>, verify both
deployments validated, and click **Publish** to release them to Maven Central.

If you would rather have Gradle release automatically once validation passes (no portal click), use
the combined task instead:

```bash
./gradlew :reputation-pool-core:publishAndReleaseToMavenCentral \
          :reputation-pool-persistence:publishAndReleaseToMavenCentral \
          -PreleaseVersion=0.2.0
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

Central propagation can take a few minutes to tens of minutes. Verify both artifacts are searchable:

- <https://central.sonatype.com/artifact/io.github.preagile/reputation-pool-core/0.2.0>
- <https://central.sonatype.com/artifact/io.github.preagile/reputation-pool-persistence/0.2.0>

Then verify a fresh consumer resolves and compiles against the public API. Depending on
`reputation-pool-persistence` also pulls in `reputation-pool-core` transitively (it is an `api`
dependency), so a single dependency on the adapter exercises both POMs:

Gradle:

```bash
mkdir -p /tmp/rp-smoke && cd /tmp/rp-smoke
cat > build.gradle.kts <<'EOF'
plugins { `java-library` }
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
dependencies {
    // the adapter, which brings reputation-pool-core in transitively (api dependency)
    implementation("io.github.preagile:reputation-pool-persistence:0.2.0")
}
EOF
mkdir -p src/main/java
cat > src/main/java/Smoke.java <<'EOF'
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.persistence.PostgresResourceStore;

public class Smoke {
    public static void main(String[] args) {
        // core type comes transitively; adapter type comes from the persistence jar
        var id = new ResourceId(ResourceKind.PROXY, "10.0.0.7:8080");
        Class<?> store = PostgresResourceStore.class;
        System.out.println(id + " " + store.getSimpleName());
    }
}
EOF
gradle compileJava --refresh-dependencies
```

Maven equivalent — a single dependency on `io.github.preagile:reputation-pool-persistence:0.2.0` and
a `mvn -q compile` against the same two types — is fine too. A clean compile confirms both artifacts
and their POMs resolved (the driver and Flyway coming transitively from the persistence POM).

## Automated release (the normal path)

The `release.yml` workflow makes the tag the release trigger — the tag name *is* the version, so no
version is committed to a file. To cut a release, tag the commit you want and push it:

```bash
git tag -a v0.3.1 -m "reputation-pool 0.3.1"
git push origin v0.3.1
```

(Or run the workflow manually from the Actions tab and type the version, e.g. `0.3.1`, which tags the
current commit for you.) The workflow then runs `publishAndReleaseToMavenCentral` for every published
module with the Central credentials and signing key from repository Secrets, and creates the GitHub
Release. The manual `./gradlew publishToMavenCentral` steps above remain the local fallback for when
you must publish by hand.
