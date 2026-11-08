#!/usr/bin/env bash
set -euo pipefail

# Official upstream release, pinned by version and SHA-256. Linux CI only.
tool_dir="${RUNNER_TEMP:?}/cargo-deny-0.20.2"
mkdir -p "$tool_dir"
archive="$tool_dir/release.tar.gz"
curl --fail --location --retry 2 --max-time 120 \
  https://github.com/EmbarkStudios/cargo-deny/releases/download/0.20.2/cargo-deny-0.20.2-x86_64-unknown-linux-musl.tar.gz \
  --output "$archive"
printf '%s  %s\n' '9f12ed4c49936e09b48bf862b595cde2fe64fcbd9d74dfacac6131ca824c8d5f' "$archive" | sha256sum --check
tar -xzf "$archive" -C "$tool_dir" --strip-components=1 \
  cargo-deny-0.20.2-x86_64-unknown-linux-musl/cargo-deny
printf '%s\n' "$tool_dir" >> "${GITHUB_PATH:?}"
