# Repository rules

- The checker runtime is a Rust binary. Gradle `policyCheck` invokes the prebuilt binary and must not compile Rust or start a JVM analyzer.
- For every added or updated dependency, inspect the exact upstream version, full transitive graph, nested third-party notices, and commercial-use/distribution conditions. Follow `docs/OPEN_SOURCE_POLICY.md`.
- Use only reviewed licenses from `deny.toml`. If an intended use is incompatible, replace the dependency or implement the functionality independently. Do not bypass checks, add a broad exception, or remove attribution to make CI pass.
- Preserve upstream copyright/license/NOTICE text verbatim. Review `third_party/dependencies.json` and `third_party/components.json` with every dependency, toolchain, wrapper, or Action update. `--write` renders the notice index; it never approves a new dependency.
- Every binary distribution must include `LICENSE`, `THIRD_PARTY_NOTICES.md`, and `third_party/`. Use `scripts/package-release.py`; do not upload a bare binary.
- Validate changes with the relevant Rust tests, `python3 scripts/license-check.py`, and license enforcement tests. Keep policy specifications, supported analysis limits, and tests consistent.
- Company-specific restrictions, when supplied by the user, override the repository baseline. Do not describe this baseline as company approval.
