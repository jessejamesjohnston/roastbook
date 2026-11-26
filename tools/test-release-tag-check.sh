#!/usr/bin/env bash
# Proves the release-tag check in scripts/release.sh against throwaway keys in scratch repositories.
#
#   tools/test-release-tag-check.sh [path/to/release.sh]
#
# Each case runs the whole script on a scratch repository: the tag check, a throwaway keystore made
# here, a stub gradlew (committed in the scratch repository) that writes a stand-in APK recording
# the commit, and stub build tools. The stubs stand in for Gradle and the SDK only; the tags, the
# SSH and OpenPGP signatures, the allowed-signers files, git and ssh-keygen are all real. A case
# expected to be refused must stop with its own message and leave dist/ unwritten.
#
# Needs git, ssh-keygen, gpg, keytool and python3. Everything happens in a temporary directory with
# its own HOME, git config and GnuPG home, so your keys, keyring and git settings are never read,
# and it is deleted at the end. Exits non-zero if any case gives the wrong result.
set -euo pipefail

APP=roastbook
PREFIX=ROASTBOOK
VERSION=1.0.0
TAG="v$VERSION"
SCRIPT="${1:-$(dirname "$0")/../scripts/release.sh}"
[[ -f "$SCRIPT" ]] || { echo "no release script at $SCRIPT" >&2; exit 2; }
SCRIPT="$(cd "$(dirname "$SCRIPT")" && pwd)/$(basename "$SCRIPT")"
for tool in git ssh-keygen gpg gpgconf keytool python3; do
    command -v "$tool" >/dev/null || { echo "$tool not on PATH" >&2; exit 2; }
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/release-tag-check.XXXXXX")"
cleanup() {
    gpgconf --homedir "$WORK/gnupg" --kill gpg-agent 2>/dev/null || true
    rm -rf "$WORK"
}
trap cleanup EXIT

# A world of its own: nothing below can reach your keys, keyring, agent or git settings.
export HOME="$WORK/home" GNUPGHOME="$WORK/gnupg" XDG_CONFIG_HOME="$WORK/home/.config"
export GIT_CONFIG_GLOBAL="$WORK/home/.gitconfig" GIT_CONFIG_NOSYSTEM=1
unset SSH_AUTH_SOCK GIT_DIR GIT_WORK_TREE ANDROID_SDK_ROOT
for v in $(compgen -e | grep "^${PREFIX}_" || true); do unset "$v"; done
mkdir -p "$HOME/.ssh" "$HOME/.config/git" "$GNUPGHOME" "$WORK/bin" "$WORK/shim"
chmod 700 "$GNUPGHOME"

# Throwaway keys: the owner's SSH key, a second key the allowed-signers file also lists, a stranger's
# key it does not list, and an OpenPGP key.
for k in owner other stranger; do ssh-keygen -q -t ed25519 -N '' -C "$k" -f "$HOME/.ssh/$k"; done
fp() { ssh-keygen -lf "$HOME/.ssh/$1.pub" | awk '{print $2}'; }
OWNER_FP="$(fp owner)"; OTHER_FP="$(fp other)"
gpg --batch --quiet --passphrase '' --quick-gen-key 'Throwaway Signer <signer@example.com>' ed25519 sign never 2>/dev/null
GPG_KEY="$(gpg --batch --list-secret-keys --with-colons | awk -F: '$1=="fpr"{print $10; exit}')"

# The listed principal differs from the git email used to tag, as the owner's does.
PRINCIPAL=release-owner@example.org
echo "$PRINCIPAL $(cut -d' ' -f1,2 "$HOME/.ssh/owner.pub")" > "$HOME/.config/git/allowed_signers"
echo "other@example.org $(cut -d' ' -f1,2 "$HOME/.ssh/other.pub")" >> "$HOME/.config/git/allowed_signers"
# The same keys, each line with an options field.
echo "$PRINCIPAL namespaces=\"git\",valid-after=\"20200101\" $(cut -d' ' -f1,2 "$HOME/.ssh/owner.pub")" \
    > "$HOME/.config/git/allowed_signers_options"

# Throwaway release keystore, and stub build tools that report its certificate as the expected one.
STORE_PASS="throwaway-$RANDOM-$RANDOM"
keytool -genkeypair -keystore "$HOME/test.p12" -storetype PKCS12 -alias "$APP" -keyalg RSA \
    -keysize 2048 -validity 2 -dname "CN=Throwaway" -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
    >/dev/null 2>&1
CERT_HEX="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
# The colon form keytool prints, which the script takes.
CERT_COLONS="$(tr 'a-f' 'A-F' <<<"$CERT_HEX" | sed 's/../&:/g; s/:$//')"
SDK="$WORK/sdk"; BT="$SDK/build-tools/99.0.0"; mkdir -p "$BT"
cat > "$BT/apksigner" <<EOF
#!/bin/sh
echo "Verifies"
echo "Verified using v2 scheme (APK Signature Scheme v2): true"
echo "Signer #1 certificate DN: CN=Throwaway"
echo "Signer #1 certificate SHA-256 digest: $CERT_HEX"
EOF
printf '#!/bin/sh\nexit 0\n' > "$BT/zipalign"
printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/readelf"
# Stands in for gpg during a release run: any call is recorded, and the case fails.
printf '#!/bin/sh\necho called >> "%s"\nexit 1\n' "$WORK/gpg-called" > "$WORK/shim/gpg"
chmod +x "$BT/apksigner" "$BT/zipalign" "$WORK/bin/readelf" "$WORK/shim/gpg"

# The scratch repository: this release script, a versionName, and a stub gradlew that writes what
# :app:assembleRelease would (an APK recording the commit, and an R8 mapping). Given
# TEST_MOVE_TAG_TO, it first moves the tag in the source repository to that object, as someone
# moving the tag mid-build would.
REPO="$WORK/repo"
git init --quiet -b main "$REPO"
git -C "$REPO" config user.name "Throwaway Owner"
git -C "$REPO" config user.email "$PRINCIPAL"
git -C "$REPO" config gpg.format ssh
git -C "$REPO" config user.signingkey "$HOME/.ssh/owner"
git -C "$REPO" config gpg.ssh.allowedSignersFile "$HOME/.config/git/allowed_signers"
mkdir -p "$REPO/scripts" "$REPO/app"
cp "$SCRIPT" "$REPO/scripts/release.sh"
printf 'android {\n    defaultConfig {\n        versionName = "%s"\n    }\n}\n' "$VERSION" > "$REPO/app/build.gradle.kts"
cat > "$REPO/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${TEST_MOVE_TAG_TO:-}" ]]; then
    git -C "$TEST_SOURCE_REPO" update-ref "refs/tags/$TEST_TAG" "$TEST_MOVE_TAG_TO"
fi
echo built >> "$TEST_BUILD_LOG"
mkdir -p app/build/outputs/apk/release app/build/outputs/mapping/release
python3 - "$(git rev-parse HEAD)" <<'PY'
import sys, zipfile
with zipfile.ZipFile("app/build/outputs/apk/release/app-release.apk", "w") as z:
    z.writestr("META-INF/version-control-info.textproto",
               'repositories {\n  system: GIT\n  revision: "%s"\n}\n' % sys.argv[1])
PY
echo "com.merrimentlabs.roastbook.MainActivity -> com.merrimentlabs.roastbook.MainActivity:" \
    > app/build/outputs/mapping/release/mapping.txt
EOF
chmod +x "$REPO/gradlew" "$REPO/scripts/release.sh"
git -C "$REPO" add -A
git -C "$REPO" commit --quiet -m "Release $VERSION"
COMMIT_A="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" commit --quiet --allow-empty -m "A later commit"
COMMIT_B="$(git -C "$REPO" rev-parse HEAD)"

g() { git -C "$REPO" "$@"; }
retag() { g tag -d "$TAG" >/dev/null 2>&1 || true; }

FAILED=0
ROWS=()
# run_case <name> <pass|refuse> <text the output must contain> [env assignments...]
run_case() {
    local name="$1" want="$2" text="$3" out rc=0 got ok=1 line
    shift 3
    rm -rf "$REPO/dist" "$WORK/gpg-called" "$WORK/build.log"
    out="$(env PATH="$WORK/shim:$WORK/bin:$PATH" ANDROID_HOME="$SDK" \
        "${PREFIX}_KEYSTORE_PATH=$HOME/test.p12" "${PREFIX}_KEYSTORE_PASS=$STORE_PASS" \
        "${PREFIX}_EXPECTED_CERT=$CERT_COLONS" \
        TEST_SOURCE_REPO="$REPO" TEST_TAG="$TAG" TEST_BUILD_LOG="$WORK/build.log" \
        "$@" "$REPO/scripts/release.sh" "$TAG" 2>&1)" || rc=$?
    if (( rc == 0 )); then got=pass; else got=refuse; fi
    [[ "$got" == "$want" ]] || ok=0
    grep -qF -- "$text" <<<"$out" || ok=0
    if [[ "$want" == pass ]]; then
        [[ -f "$REPO/dist/$APP-$VERSION.apk" && -f "$REPO/dist/SHA256SUMS" ]] || ok=0
    else
        [[ ! -e "$REPO/dist" ]] || ok=0
    fi
    [[ ! -e "$WORK/gpg-called" ]] || ok=0
    if (( rc == 0 )); then
        line="$(grep -m1 '^release: tag .* is signed by' <<<"$out" || true)"
    else
        line="$(grep '^release: ' <<<"$out" | tail -1)"
    fi
    if (( ok )); then
        ROWS+=("ok    | $name | expected $want, got $got | ${line#release: }")
    else
        FAILED=1
        ROWS+=("WRONG | $name | expected $want, got $got | ${line#release: }")
        printf -- '--- output of "%s"\n%s\n---\n' "$name" "$out" >&2
    fi
}

# 1. Signed by the owner's key, which the file lists and the run pins.
retag; g tag -s "$TAG" -m "$APP $VERSION" "$COMMIT_A"
run_case "correctly signed (own-key pin)" pass "signed by $OWNER_FP, listed for $PRINCIPAL" "${PREFIX}_TAG_KEY=$OWNER_FP"
# 2. The same tag, with the default pin: the official release-tag key, which did not sign it.
run_case "correctly signed, default pin (not the official key)" refuse "not by the release-tag key"
# 3. The signer's git email differs from the principal the file lists for the key.
g config user.email owner@example.com
retag; g tag -s "$TAG" -m "$APP $VERSION" "$COMMIT_A"
run_case "git email differs from the listed principal" pass "listed for $PRINCIPAL" "${PREFIX}_TAG_KEY=$OWNER_FP"
g config user.email "$PRINCIPAL"
# 4. The allowed-signers file carries an options field on the key's line.
g config gpg.ssh.allowedSignersFile "$HOME/.config/git/allowed_signers_options"
run_case "signers file with an options field" pass "signed by $OWNER_FP, listed for $PRINCIPAL" "${PREFIX}_TAG_KEY=$OWNER_FP"
# 5. git config names the file with ~/, as people write it by hand.
g config gpg.ssh.allowedSignersFile "~/.config/git/allowed_signers"
run_case "signers file named with ~/" pass "signed by $OWNER_FP, listed for $PRINCIPAL" "${PREFIX}_TAG_KEY=$OWNER_FP"
g config gpg.ssh.allowedSignersFile "$HOME/.config/git/allowed_signers"
# 6. Lightweight.
retag; g tag "$TAG" "$COMMIT_A"
run_case "lightweight tag" refuse "is a lightweight tag" "${PREFIX}_TAG_KEY=$OWNER_FP"
# 7. Annotated, unsigned.
retag; g tag -a "$TAG" -m "$APP $VERSION" "$COMMIT_A"
run_case "annotated, unsigned" refuse "has no SSH signature" "${PREFIX}_TAG_KEY=$OWNER_FP"
# 8. OpenPGP-signed with a valid key in this keyring; refused before gpg is ever run.
retag; g -c gpg.format=openpgp -c user.signingkey="$GPG_KEY" tag -s "$TAG" -m "$APP $VERSION" "$COMMIT_A"
run_case "OpenPGP-signed (gpg never called)" refuse "carries an OpenPGP or X.509 signature" "${PREFIX}_TAG_KEY=$OWNER_FP"
# 9. Signed by a key the file does not list.
retag; g -c user.signingkey="$HOME/.ssh/stranger" tag -s "$TAG" -m "$APP $VERSION" "$COMMIT_A"
run_case "unlisted SSH key" refuse "does not verify against" "${PREFIX}_TAG_KEY=$OWNER_FP"
# 10. Signed by a key the file lists, but not the pinned one.
retag; g -c user.signingkey="$HOME/.ssh/other" tag -s "$TAG" -m "$APP $VERSION" "$COMMIT_A"
run_case "listed key that is not the pinned key" refuse "signed by $OTHER_FP (other@example.org), not by the release-tag key" "${PREFIX}_TAG_KEY=$OWNER_FP"
# 11. A correctly signed tag object made for another version, placed under this tag's name.
retag; g tag -s v0.9.0 -m "$APP 0.9.0"
g update-ref "refs/tags/$TAG" "$(g rev-parse v0.9.0)"
run_case "renamed tag object" refuse "was made for another name (v0.9.0)" "${PREFIX}_TAG_KEY=$OWNER_FP"
g tag -d v0.9.0 >/dev/null
# 12. Moved during the build to another correctly signed tag of the same name, on a later commit.
retag; g tag -s "$TAG" -m "$APP $VERSION" "$COMMIT_B"; MOVED="$(g rev-parse "$TAG")"
retag; g tag -s "$TAG" -m "$APP $VERSION" "$COMMIT_A"
run_case "tag moved after the first check" refuse "moved during the build" "${PREFIX}_TAG_KEY=$OWNER_FP" TEST_MOVE_TAG_TO="$MOVED"
[[ "$(cat "$WORK/build.log" 2>/dev/null)" == built ]] || { FAILED=1; ROWS+=("WRONG | tag moved after the first check | the stub build never ran |"); }
# 13. --check-tag runs the check alone.
retag; g tag -s "$TAG" -m "$APP $VERSION" "$COMMIT_A"
rc=0; out="$(env "${PREFIX}_TAG_KEY=$OWNER_FP" PATH="$WORK/shim:$WORK/bin:$PATH" ANDROID_HOME="$SDK" \
    "$REPO/scripts/release.sh" --check-tag "$TAG" 2>&1)" || rc=$?
if (( rc == 0 )) && grep -qF "passes the release-tag check (commit $COMMIT_A)" <<<"$out"; then
    ROWS+=("ok    | --check-tag on the correctly signed tag | expected pass, got pass | $(tail -1 <<<"$out" | sed 's/^release: //')")
else
    FAILED=1; ROWS+=("WRONG | --check-tag on the correctly signed tag | exit $rc | $(tail -1 <<<"$out")")
fi

echo "Release-tag check, $(basename "$SCRIPT") ($SCRIPT):"
printf '%s\n' "${ROWS[@]}"
if (( FAILED )); then echo "FAILED: a case gave the wrong result"; exit 1; fi
echo "All ${#ROWS[@]} cases gave the expected result."
