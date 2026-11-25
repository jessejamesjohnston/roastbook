#!/usr/bin/env bash
# Build and sign a Roastbook release APK from a clean checkout of a tag, then verify it.
#
#   scripts/release.sh v0.4.0            build, sign and verify
#   scripts/release.sh --verify <apk>    verify an APK only
#
# You supply the key, through the environment:
#   ROASTBOOK_KEYSTORE_PATH      the keystore file (PKCS12 or JKS). Required to build.
#   ROASTBOOK_KEYSTORE_KEYALIAS  the key's alias. Optional when the keystore holds one key (it is
#                                used whatever its alias), or a key named roastbook.
#   ROASTBOOK_KEYSTORE_PASS      the password, used for the store and the key. If unset, the
#                                script asks for it (so it needs a terminal).
#   ROASTBOOK_EXPECTED_CERT      the SHA-256 of the certificate the APK must be signed with.
#                                Default: the certificate of the published Roastbook releases.
#                                Set it to your own certificate when you sign with your own key.
#
# The password and the alias are checked against the keystore, and the password must unlock the
# key, before anything is cloned or built, so a wrong one fails at once. The tag is cloned into a
# temporary directory and built there, so uncommitted changes and untracked files in this checkout
# never reach the release, and the commit recorded inside the APK is the tag's. The password
# reaches keytool and Gradle only through the environment: it is never printed,
# never put on a command line and never written to disk, and Gradle runs without its daemon or
# configuration cache so it does not outlive this run.
#
# Output in dist/: roastbook-<version>.apk, SHA256SUMS and roastbook-<version>-mapping.txt (R8's
# map from obfuscated names back to the source names; kept to read crash stack traces from this
# build, never published). Nothing lands there unless the build succeeds and every check passes.
set -euo pipefail

OFFICIAL_CERT="D8:79:AD:13:90:76:D9:92:D6:37:CF:A9:E3:A4:6C:84:2C:9C:E1:99:0A:AC:2A:B6:5B:0F:D7:6E:97:76:38:E1"
EXPECTED_CERT="${ROASTBOOK_EXPECTED_CERT:-$OFFICIAL_CERT}"

die() { echo "release: $*" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$SDK" ]] || die "set ANDROID_HOME to your Android SDK"
# zipalign -P (16 KB page alignment) needs build-tools 35 or newer; take the newest installed.
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
for tool in apksigner zipalign; do [[ -x "$BUILD_TOOLS/$tool" ]] || die "no $tool in $BUILD_TOOLS"; done
command -v readelf >/dev/null || die "readelf not on PATH (binutils)"

# Every check a release APK must pass. Prints what it checked; returns non-zero on any failure.
verify() {
    local apk="$1" fail=0 out rc sha dn
    [[ -f "$apk" ]] || die "no APK at $apk"

    echo "== Signature"
    # One apksigner run: its exit status says whether the signature verifies at all (a modified
    # APK does not), and its output names the signing certificate.
    rc=0
    out="$("$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$apk" 2>&1)" || rc=$?
    if (( rc != 0 )); then
        echo "  the signature does not verify (the APK was modified after signing, or is not signed)" >&2
        sed -n 's/^ERROR: /  apksigner: /p' <<<"$out" | head -3 >&2
        return 1
    fi
    grep -E "^Verified using|^Verifies" <<<"$out" || true
    sha=$(sed -n 's/^.*[Ss]igner.* certificate SHA-256 digest: //p' <<<"$out" | head -1 | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')
    dn=$(sed -n 's/^.*[Ss]igner.* certificate DN: //p' <<<"$out" | head -1)
    echo "Certificate SHA-256: $sha"
    echo "Expected:            $EXPECTED_CERT"
    [[ "$sha" == "$EXPECTED_CERT" ]] || { echo "  not the expected certificate" >&2; fail=1; }
    # Android's debug keys all carry this subject; a debug-signed APK must never be published.
    [[ "$dn" != *"CN=Android Debug"* ]] || { echo "  signed with a debug key" >&2; fail=1; }

    # The Android Gradle Plugin records the commit a release was built from; the build path below
    # also checks that it is the tag's.
    echo "== Source commit"
    local vcs rev
    vcs=$(unzip -p "$apk" META-INF/version-control-info.textproto 2>/dev/null || true)
    rev=$(sed -n 's/^ *revision: "\([0-9a-f]*\)".*/\1/p' <<<"$vcs" | head -1)
    if [[ -n "$rev" ]]; then echo "Recorded commit: $rev"; else echo "  no commit recorded (META-INF/version-control-info.textproto)" >&2; fail=1; fi

    echo "== 16 KB page size: zip alignment of stored libraries"
    if "$BUILD_TOOLS/zipalign" -c -P 16 -v 4 "$apk" >/dev/null; then echo "zipalign -c -P 16: ok"; else echo "  zipalign -P 16 check failed" >&2; fail=1; fi

    # The requirement covers 64-bit code. The 32-bit OCR libraries ML Kit ships are 0x1000 aligned,
    # which is allowed.
    echo "== 16 KB page size: ELF LOAD alignment of 64-bit libraries"
    local tmp so align n=0
    tmp=$(mktemp -d)
    unzip -q -o "$apk" 'lib/arm64-v8a/*' 'lib/x86_64/*' -d "$tmp" 2>/dev/null || true
    while IFS= read -r so; do
        n=$((n + 1))
        align=$(readelf -lW "$so" | awk '$1=="LOAD"{print $NF}' | sort -u | tr '\n' ' ')
        for a in $align; do
            if (( a < 0x4000 )); then echo "  ${so#"$tmp"/}: LOAD align $a" >&2; fail=1; fi
        done
    done < <(find "$tmp/lib" -name '*.so' 2>/dev/null)
    rm -rf "$tmp"
    echo "$n 64-bit libraries checked"

    return "$fail"
}

if [[ "${1:-}" == "--verify" ]]; then
    verify "${2:-}" || die "verification FAILED"
    echo "release: verified"
    exit 0
fi

TAG="${1:-}"
[[ -n "$TAG" ]] || { echo "usage: scripts/release.sh <tag> | --verify <apk>" >&2; exit 2; }

REPO="$(cd "$(dirname "$0")/.." && pwd)"
git -C "$REPO" rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "no tag $TAG in $REPO"

STORE="${ROASTBOOK_KEYSTORE_PATH:-}"
[[ -n "$STORE" && -f "$STORE" ]] || die "set ROASTBOOK_KEYSTORE_PATH to your keystore file"
STORE="$(cd "$(dirname "$STORE")" && pwd)/$(basename "$STORE")"

if [[ -z "${ROASTBOOK_KEYSTORE_PASS:-}" ]]; then
    [[ -t 0 ]] || die "set ROASTBOOK_KEYSTORE_PASS or run this in a terminal"
    read -rsp "Keystore password: " ROASTBOOK_KEYSTORE_PASS; echo >&2
fi
[[ -n "$ROASTBOOK_KEYSTORE_PASS" ]] || die "empty keystore password"
# Fail before Gradle if the password or alias is wrong. keytool reads the password from the
# environment, and runs in English so its listing can be parsed the same way everywhere. Only
# Java's language is set: the locale's character set is left alone, because keytool decodes the
# password from the environment with it, and a password with non-ASCII characters must survive.
export ROASTBOOK_KEYSTORE_PASS
keytool_en() { keytool -J-Duser.language=en -J-Duser.country=US "$@"; }
KEYS="$(keytool_en -list -keystore "$STORE" -storepass:env ROASTBOOK_KEYSTORE_PASS 2>/dev/null)" \
    || die "the keystore or its password did not check out"
# "alias, date, PrivateKeyEntry," per key. Keystores store aliases in lowercase.
ALIASES="$(sed -n 's/^\([^,]*\),.*PrivateKeyEntry.*/\1/p' <<<"$KEYS")"
[[ -n "$ALIASES" ]] || die "no private key in the keystore"
if [[ -n "${ROASTBOOK_KEYSTORE_KEYALIAS:-}" ]]; then
    ALIAS="$ROASTBOOK_KEYSTORE_KEYALIAS"
    # Keystore lookups ignore case, so MyKey names the key listed as mykey.
    grep -qixF "$ALIAS" <<<"$ALIASES" || die "no key named '$ALIAS' in the keystore (it holds: $(paste -sd, - <<<"$ALIASES"))"
elif [[ "$(grep -c . <<<"$ALIASES")" == 1 ]]; then
    ALIAS="$ALIASES"
elif grep -qixF roastbook <<<"$ALIASES"; then
    ALIAS=roastbook
else
    die "the keystore holds several keys ($(paste -sd, - <<<"$ALIASES")); set ROASTBOOK_KEYSTORE_KEYALIAS to one"
fi
# The build uses the same password for the key. Unlocking the key proves it (the request
# keytool writes is thrown away).
keytool_en -certreq -keystore "$STORE" -alias "$ALIAS" -storepass:env ROASTBOOK_KEYSTORE_PASS \
    -keypass:env ROASTBOOK_KEYSTORE_PASS >/dev/null 2>&1 \
    || die "the password does not unlock the key '$ALIAS' (the key's password must equal the keystore's)"
echo "release: signing with key '$ALIAS'"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone --quiet --no-local --depth 1 --branch "$TAG" "file://$REPO" "$WORK/src"
cd "$WORK/src"

VERSION="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
[[ "$TAG" == "v$VERSION" ]] || die "tag $TAG does not match versionName $VERSION (expected v$VERSION)"

export ROASTBOOK_KEYSTORE_PATH="$STORE"
export ROASTBOOK_KEYSTORE_KEYALIAS="$ALIAS"
export ROASTBOOK_KEYSTORE_STOREPASSWORD="$ROASTBOOK_KEYSTORE_PASS"
export ROASTBOOK_KEYSTORE_KEYPASSWORD="$ROASTBOOK_KEYSTORE_PASS"
unset ROASTBOOK_KEYSTORE_PASS

echo "release: building $TAG ($(git rev-parse --short HEAD)) with R8"
./gradlew --no-daemon --no-configuration-cache --no-build-cache --console=plain -q :app:assembleRelease
unset ROASTBOOK_KEYSTORE_STOREPASSWORD ROASTBOOK_KEYSTORE_KEYPASSWORD

BUILT_APK=app/build/outputs/apk/release/app-release.apk
BUILT_MAPPING=app/build/outputs/mapping/release/mapping.txt
verify "$BUILT_APK" || die "verification FAILED; dist/ left as it was"
# The commit inside the APK must be the tag's, so anyone can check out the exact source.
COMMIT="$(git rev-parse HEAD)"
VCS_INFO="$(unzip -p "$BUILT_APK" META-INF/version-control-info.textproto 2>/dev/null || true)"
grep -q "revision: \"$COMMIT\"" <<<"$VCS_INFO" \
    || die "the APK does not record the tag's commit $COMMIT; dist/ left as it was"
# Each R8 build renames differently, so only this build's mapping can retrace its stack traces.
[[ -s "$BUILT_MAPPING" ]] || die "R8 wrote no mapping at $BUILT_MAPPING; dist/ left as it was"

mkdir -p "$REPO/dist"
APK="roastbook-$VERSION.apk"
MAPPING="roastbook-$VERSION-mapping.txt"
cp "$BUILT_APK" "$REPO/dist/$APK"
cp "$BUILT_MAPPING" "$REPO/dist/$MAPPING"
cd "$REPO/dist"
if command -v sha256sum >/dev/null; then sha256sum "$APK" > SHA256SUMS; else shasum -a 256 "$APK" > SHA256SUMS; fi
cat SHA256SUMS
ls -l "$APK" | awk '{printf "%s  %.1f MB\n", $NF, $5/1048576}'
echo "release: wrote dist/$APK, dist/SHA256SUMS and dist/$MAPPING (keep the mapping; do not publish it)."
echo "release: certificate SHA-256: $EXPECTED_CERT"
