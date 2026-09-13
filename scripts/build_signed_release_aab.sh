#!/usr/bin/env bash
set -euo pipefail

# This script reads local signing values outside the repository. It must never
# print or copy those values into Git, Gradle properties, or build logs.
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly KEY_DIR="/home/ubuntu/koleety_android_release_keys"
readonly ARTIFACT_DIR="/home/ubuntu/koleety_android_release_artifacts"

credential_file="$(find "$KEY_DIR" -maxdepth 1 -type f \( -iname '*credential*' -o -iname '*secret*' -o -iname '*.env' -o -iname '*.txt' \) -print -quit)"
if [[ -z "$credential_file" ]]; then
  echo "Signing credential metadata is unavailable." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$credential_file"
set +a

if [[ -z "${KEYSTORE_PATH:-}" || -z "${KEYSTORE_PASSWORD:-}" || -z "${KEY_ALIAS:-}" || -z "${KEY_PASSWORD:-}" ]]; then
  echo "Signing inputs are incomplete." >&2
  exit 1
fi

mkdir -p "$ARTIFACT_DIR"
cd "$ROOT_DIR"
./gradlew clean bundleRelease --stacktrace

readonly SOURCE_AAB="app/build/outputs/bundle/release/app-release.aab"
readonly TARGET_AAB="$ARTIFACT_DIR/koleety-ai-app-1.0.27-identity-release.aab"
test -s "$SOURCE_AAB"
cp "$SOURCE_AAB" "$TARGET_AAB"
sha256sum "$TARGET_AAB" > "$TARGET_AAB.sha256"
echo "Signed AAB created for local inspection."
