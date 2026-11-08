# Development rules

- The checker is a Gradle custom task implemented in Groovy. Run syntax analysis in a classloader-isolated Gradle Worker within the Gradle JVM. Do not add native executables or invoke an external analyzer process.
- Keep the YAML rule contract and Kotlin fixture expectations consistent. Kotlin PSI is used only for parsing and declared-type indexing, not full compiler type resolution.
- When adding or updating a dependency, inspect the exact upstream version, transitive dependencies, embedded code/data, and conditions for commercial use, modification and redistribution. Follow `docs/OPEN_SOURCE_POLICY.md`.
- If the intended company use is incompatible with those conditions, replace the library or implement the functionality independently. Company-specific restrictions supplied by the user take precedence.
- Preserve upstream copyright, license and applicable NOTICE text. Update `third_party/dependencies.json`, `third_party/components.json` and `THIRD_PARTY_NOTICES.md` when the relevant components change. Include the notices with binary distributions.
- License diligence is a responsibility of developing this tool. Do not turn it into additional product features, Gradle tasks or dedicated enforcement tooling unless the user requests that work.
- Run tests appropriate to code changes and keep rule specifications and documented analysis limits consistent with the implementation.
