# Third-party notices — JVM implementation

The project code is MIT. Third-party components retain their own conditions. Versions, original Maven POMs, artifact SHA-256 hashes and upstream source JAR URLs are recorded in `third_party/dependencies.json`. No third-party source has been modified. Dependencies are resolved as separate, unmodified Maven artifacts and are not shaded into the plugin JAR.

| Component | Version | Declared terms / retained notices | Use |
| --- | --- | --- | --- |
| Kotlin compiler embeddable | 2.2.21 | Apache-2.0 plus embedded third-party conditions; `third_party/kotlin-2.2.21/` | PSI parser in Gradle worker |
| Kotlin stdlib, script runtime, daemon embeddable | 2.2.21 | Apache-2.0 and applicable third-party notices in the Kotlin snapshot | Parser/compiler dependencies; policy analysis does not launch a compiler daemon |
| Kotlin Gradle Plugin and Kotlin build/scripting utilities | 2.2.21 | Apache-2.0 plus embedded notices below | Compile the Kotlin task implementation and tests only |
| Gson / Error Prone annotations | 2.11.0 / 2.27.0 | Apache-2.0; exact original notices under `third_party/` | Build-tool dependencies only |
| Kotlin reflect | 1.6.10 | Apache-2.0; `third_party/kotlin-reflect-1.6.10/` | Compiler's declared transitive version |
| kotlinx-coroutines-core-jvm | 1.8.0 | Apache-2.0; `third_party/kotlinx-coroutines-1.8.0/` | Compiler transitive dependency |
| JetBrains annotations | 13.0 | Apache-2.0; `third_party/annotations-13.0/` | Kotlin stdlib transitive dependency |
| SnakeYAML | 2.3 | Apache-2.0; `third_party/snakeyaml-2.3/` | Safe YAML loading |
| JUnit | 4.13.2 | EPL-1.0; `third_party/junit-4.13.2/` | Tests only; not distributed in plugin |
| Hamcrest core | 1.3 | BSD-3-Clause; `third_party/hamcrest-core-1.3/` | Tests only; not distributed in plugin |
| Gradle wrapper / API / Groovy provided by Gradle | 8.10.2 / Groovy 3.0.22 | Apache-2.0; wrapper original license retained | Host build tool; Gradle's distribution retains its own notices |
| GitHub Actions | Exact commits in `third_party/components.json` | MIT; original notices in `third_party/actions-*/` | CI only |

## Kotlin embedded code

Kotlin's Maven Apache-2.0 declaration is not a claim that every class in its compiler JAR has only that license. The exact 2.2.21 release's `kotlinc/license/` directory is preserved, including its README, COPYRIGHT, NOTICE and third-party texts. The archive URL and SHA-256 are in the inventory.

In particular, the compiler JAR contains GWT/Rhino JavaScript parser classes under **Netscape Public License 1.1** (`third_party/kotlin-2.2.21/third_party/rhino_LICENSE.txt`). They are not used by our Kotlin PSI path, but their presence still matters when deciding whether a company permits the artifact. Other upstream notices include ASM/BSD, Dart/BSD, fastutil/Apache-2.0, and standard-library derivatives. The snapshot also includes test-only and non-JVM components; see the upstream README for scope. It must not be read as a list of active code paths.

The Kotlin dependency remains unmodified and separate. Preserve its notices and source availability when redistributing that artifact. Official source JAR URLs and the exact upstream source tag are recorded; source repository: https://github.com/JetBrains/kotlin/tree/v2.2.21 . If a company's dependency policy excludes NPL-containing artifacts, this compiler dependency must be replaced before adoption; there is no claim of blanket company approval based on its top-level Apache label.

## Kotlin build-tool embedded code

The Kotlin Gradle Plugin is used to compile this project; consuming the published plugin does not apply KGP or add KGP to the consumer's plugin dependencies. Its exact `gradle88` artifact and Kotlin build/scripting dependencies are recorded with POMs and checksums in `third_party/dependencies.json`.

The KGP JAR also embeds Guava 33.3.1-jre, failureaccess 1.0.2, Gson 2.11.0, Commons Codec 1.17.1, Commons IO 2.18.0, Commons Compress 1.27.1 and Commons Lang 3.16.0 (Apache-2.0), semver4j 0.16.4 (MIT), and ANTLR runtime 4.5.2-1 (BSD-3-Clause). Versions come from the embedded Maven metadata. The IDEA protobuf artifact embeds protobuf-java/kotlin 4.28.2 (BSD-3-Clause), as pinned by Kotlin's version catalog. Their original license/NOTICE files or exact-version source headers are preserved in the corresponding `third_party/<component>-<version>/` directories. Original KGP JAR notices are in `third_party/kotlin-gradle-plugin-2.2.21/`.

KGP's embedded ASM 9.6.1 (BSD) and TeamCity service messages (Apache-2.0) are covered by the retained Kotlin release's `asm_license.txt` and `teamcity-service-messages_LICENSE.txt`. Build tools remain separate unmodified upstream artifacts. Their licenses permit commercial use subject to the retained notice and redistribution conditions; company-specific approval rules still apply.

The build may use Kotlin's compilation daemon to compile the plugin itself. Running policy analysis uses the Gradle classloader-isolated worker and does not start an external analyzer or compile the inspected application.

## Distribution

The plugin JAR includes this file, our MIT license and the notice inventory under `META-INF/spring-policy/`. It does not bundle Kotlin, SnakeYAML, JUnit, a JDK, Gradle itself or Rust components. Gradle downloads runtime dependencies from the consumer's Maven repositories. An offline bundle or container that includes those dependencies must carry their corresponding licenses and source-availability information as applicable.

Rust v0.1 code, binaries, dependency manifests and notices were removed from this revision together. Historical commits and artifacts retain their original license obligations; this notice applies to the JVM revision.
