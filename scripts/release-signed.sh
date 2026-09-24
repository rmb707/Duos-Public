#!/usr/bin/env bash
# Builds a signed Folio release: dist/Folio-<version>/ with the APK, SHA256SUMS.txt and the signing certificate.
# Needs FOLIO_RELEASE_STORE_FILE (outside the repo), FOLIO_RELEASE_STORE_PASSWORD, FOLIO_RELEASE_KEY_ALIAS and
# FOLIO_RELEASE_KEY_PASSWORD in the environment. GitHub attaches the source code to every release on its own.
set -euo pipefail

repository_root=$(cd "$(dirname "$0")/.." && pwd -P)
version=$(sed -n 's/^val folioVersion = "\(.*\)"$/\1/p' "$repository_root/app/build.gradle.kts")
if [[ -z "$version" ]]; then
    echo "Could not read folioVersion from app/build.gradle.kts." >&2
    exit 1
fi
output_dir=${1:-"$repository_root/dist/Folio-$version"}

for variable_name in FOLIO_RELEASE_STORE_FILE FOLIO_RELEASE_STORE_PASSWORD FOLIO_RELEASE_KEY_ALIAS FOLIO_RELEASE_KEY_PASSWORD; do
    if [[ -z "${!variable_name:-}" ]]; then
        echo "Missing required release signing variable: $variable_name" >&2
        exit 1
    fi
done
if [[ ! -f "$FOLIO_RELEASE_STORE_FILE" ]]; then
    echo "FOLIO_RELEASE_STORE_FILE does not point to a file." >&2
    exit 1
fi
store_directory=$(cd "$(dirname "$FOLIO_RELEASE_STORE_FILE")" && pwd -P)
store_file="$store_directory/$(basename "$FOLIO_RELEASE_STORE_FILE")"
case "$store_file" in
    "$repository_root"/*)
        echo "The release keystore must be stored outside the repository." >&2
        exit 1
        ;;
esac
if [[ "$output_dir" != /* ]]; then
    output_dir="$PWD/$output_dir"
fi
if [[ -e "$output_dir" ]]; then
    echo "Refusing to replace existing release output: $output_dir" >&2
    exit 1
fi

# apksigner ships with the Android SDK build tools; use the newest installed version.
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$sdk_dir" && -f "$repository_root/local.properties" ]]; then
    sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$repository_root/local.properties")
fi
apksigner=$(ls -d "$sdk_dir"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
if [[ -z "$apksigner" ]]; then
    echo "apksigner not found. Set ANDROID_HOME to your Android SDK." >&2
    exit 1
fi

"$repository_root/scripts/gradle.sh" :app:assembleRelease
apk_source="$repository_root/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$apk_source" ]]; then
    echo "Signed release APK was not produced at the expected path." >&2
    exit 1
fi

output_parent=$(dirname "$output_dir")
mkdir -p "$output_parent"
staging_dir=$(mktemp -d "$output_parent/.folio-release.XXXXXX")
cleanup() { rm -rf "$staging_dir"; }
trap cleanup EXIT

package_dir="$staging_dir/$(basename "$output_dir")"
mkdir -p "$package_dir"
apk_name="Folio-$version.apk"
cp -p "$apk_source" "$package_dir/$apk_name"

# Fails if the APK isn't properly signed; the certificate digest lets people check updates come from the same key.
"$apksigner" verify --print-certs "$package_dir/$apk_name" > "$package_dir/signing-certificate.txt"

if command -v sha256sum >/dev/null 2>&1; then
    (cd "$package_dir" && sha256sum "$apk_name" > SHA256SUMS.txt)
else
    (cd "$package_dir" && shasum -a 256 "$apk_name" > SHA256SUMS.txt)
fi

mv "$package_dir" "$output_dir"
trap - EXIT
rm -rf "$staging_dir"
echo "Signed release created at $output_dir"
grep "SHA-256" "$output_dir/signing-certificate.txt" || true
