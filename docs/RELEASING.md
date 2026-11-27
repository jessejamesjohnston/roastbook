# Releasing Roastbook

Roastbook is released as a signed APK on GitHub. Every release is built from a clean checkout of
its tag and signed on the maintainer's machine with the app's release key, by
`scripts/release.sh`. CI never sees the key and never builds a release; it builds and tests only.

## The release key

- One key signs every release, so each one installs as an update over the last. A lost key or
  password means no more updates to installed copies, so the keystore is backed up on a second
  machine and its password lives in a password manager, never in a file. The keystore and the
  key share that one password; `scripts/release.sh` asks for it once and uses it for both.
  Before it fetches or builds anything, it opens the keystore with that password, looks for the
  key (the alias is matched without regard to case, as keystores do) and unlocks the key with
  the same password, so a wrong password or alias, or a key whose password differs, stops it at
  once without printing the password.
- Certificate SHA-256 (published in the README and in every release's notes so anyone can check
  an APK):
  `D8:79:AD:13:90:76:D9:92:D6:37:CF:A9:E3:A4:6C:84:2C:9C:E1:99:0A:AC:2A:B6:5B:0F:D7:6E:97:76:38:E1`
  This is the form `keytool` and `scripts/release.sh --verify` print. `apksigner verify
  --print-certs` prints the same value in lowercase without colons:
  `d879ad139076d992d637cfa9e3a46c842c9ce1990aac2ab65b0fd76e977638e1`
- Never commit a keystore (`*.jks`, `*.keystore`, `*.p12` and `*.pfx` are gitignored) and never
  put the password in a file, `local.properties`, a command line or a log.

## The release-tag key

Release tags are signed with the maintainer's SSH key, and `scripts/release.sh` builds only a tag
signed by it. The script pins the key by its fingerprint (what `ssh-keygen -lf <key>.pub`
prints), never by an email address:
`SHA256:sxKlr/5ruHyIbd2mZcZCEW4L0hVedAq35LioP5GuUBE`

The maintainer's git setup, once per machine (the key's public half, listed in an allowed-signers
file under any principal: it need not be the email git commits with, because the script finds the
signer by key):

```sh
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/<signing key>.pub
git config --global gpg.ssh.allowedSignersFile ~/.ssh/allowed_signers
```

`scripts/release.sh --check-tag vX.Y.Z` runs the tag check alone, with no keystore and no build,
and prints the key and the principal that signed. It is the check to run on a tag before
building, and on an old tag to see that it still passes.

How Gradle gets the key: `app/build.gradle.kts` reads `roastbook.keystore.{path,storePassword,
keyAlias,keyPassword}` from `local.properties`, then Gradle properties, then the environment as
`ROASTBOOK_KEYSTORE_PATH`, `ROASTBOOK_KEYSTORE_STOREPASSWORD`, `ROASTBOOK_KEYSTORE_KEYALIAS` and
`ROASTBOOK_KEYSTORE_KEYPASSWORD`. Without them a release build stops at `preReleaseBuild` on
purpose. `scripts/release.sh` uses the environment, so nothing is written to disk.
`-PallowDebugSigning=true` allows a debug-signed R8 build for local testing; such a build must
never be distributed.

## Signing your own build

You can build and sign Roastbook with your own key. Android will not install it over an official
release (the signatures differ), so back up in Settings first, uninstall, install yours, and
restore.

1. Set `versionName` in `app/build.gradle.kts` to your version and commit. The tag must be `v`
   plus that version: for the tag `v0.4.1-mine` in step 4, `versionName` is `0.4.1-mine`.
2. Let git check your tag's signature. The script builds only a tag with an SSH signature, by
   the key you name in step 4, that the file git's `gpg.ssh.allowedSignersFile` setting names
   lists. An unsigned tag, or one with an OpenPGP or X.509 signature, is refused. The name at the
   start of the line is any principal you like (it need not be your git email), and the line may
   carry options such as `namespaces="git"` between the name and the key, as ssh-keygen allows:

   ```sh
   mkdir -p ~/.config/git
   echo "you@example.com $(cat ~/.ssh/id_ed25519.pub)" >> ~/.config/git/allowed_signers
   git config gpg.ssh.allowedSignersFile ~/.config/git/allowed_signers
   git config gpg.format ssh
   git config user.signingkey ~/.ssh/id_ed25519.pub
   ```

3. Find your certificate's SHA-256. The script refuses an APK signed with any other certificate,
   and without this it expects the official one:

   ```sh
   keytool -list -v -keystore ~/keys/mykey.p12 | grep 'SHA256:'
   ```

4. Tag, check the tag with your key pinned, and build:

   ```sh
   git tag -s v0.4.1-mine -m "Roastbook 0.4.1-mine"
   export ROASTBOOK_TAG_KEY="$(ssh-keygen -lf ~/.ssh/id_ed25519.pub | awk '{print $2}')"
   scripts/release.sh --check-tag v0.4.1-mine
   ROASTBOOK_KEYSTORE_PATH=~/keys/mykey.p12 \
   ROASTBOOK_EXPECTED_CERT=<the SHA256 value from step 3> \
   scripts/release.sh v0.4.1-mine
   ```

The script asks for the password. Without a terminal, set `ROASTBOOK_KEYSTORE_PASS` in the
environment instead. It uses that one password for the keystore and the key, so your key password
must equal your store password (keytool makes PKCS12 keystores that way). A keystore with one key
is used whatever the key's alias; if yours holds several, name one with
`ROASTBOOK_KEYSTORE_KEYALIAS`.

## Every release

1. **Version.** Raise `versionCode` by one and set `versionName` in `app/build.gradle.kts`. An
   installed copy refuses an update whose `versionCode` is not higher.
2. **Notices.** If any dependency changed, run `python3 scripts/third_party_notices.py` after a
   build to regenerate `app/src/main/res/raw/third_party_notices.txt`, and add any new component
   or license to the `licenses` list in `strings.xml` and to `res/raw/licenses.txt`.
   `LicensesTest` checks the basics.
3. **Changelog.** Write the release's section in `CHANGELOG.md`.
4. **Green.** `./gradlew testDebugUnitTest createDebugUnitTestCoverageReport lintDebug` and
   `python3 scripts/coverage.py --min 95`.
5. **Tag.** Commit, then make a signed tag `vX.Y.Z` (`git tag -s vX.Y.Z -m "Roastbook X.Y.Z"`),
   check it with `scripts/release.sh --check-tag vX.Y.Z`, and push it. A tag starts no release
   build.
6. **Build.** `ROASTBOOK_KEYSTORE_PATH=<keystore> scripts/release.sh vX.Y.Z`. It first checks the
   tag, before it reads the keystore:
   - the tag is a tag object (not a lightweight tag) made for this tag's own name, so a signed
     tag for another version cannot be moved under this name. It is always looked up as
     `refs/tags/<tag>`, and a branch with the same name is refused;
   - it carries an SSH signature. An OpenPGP or X.509 signature is refused before git sees it,
     since git would check one against whatever keyring is at hand;
   - `git verify-tag` accepts the signature against the allowed-signers file git's
     `gpg.ssh.allowedSignersFile` names, with ssh-keygen forced as the verifier (a
     `gpg.ssh.program` in any git config is ignored). git has ssh-keygen find the principal
     the file lists for the signing key and verify it, so options on the key's line are read as ssh-keygen
     reads them, and the signer is found by key, not by git's `user.email`;
   - the signing key's fingerprint is the release-tag key above (`ROASTBOOK_TAG_KEY` replaces it
     for your own builds).

   It prints the key and the principal that signed. It then fetches the tag's commit into a
   temporary directory, builds `:app:assembleRelease` with R8 there, and verifies the APK:
   - `apksigner verify` passes (an APK modified after signing fails here), the certificate
     SHA-256 equals the expected one, and the certificate is not a debug certificate;
   - the commit the Android Gradle Plugin records in `META-INF/version-control-info.textproto`
     is the tag's commit (with `--verify`, only that a commit is recorded);
   - 16 KB page sizes: `zipalign -c -P 16 -v 4` (build-tools 35 or newer), and every 64-bit
     `.so` has ELF LOAD alignment of at least `0x4000`.

   Last, it runs the tag check again and requires the same tag object, so a tag moved or
   replaced during the build is refused. Only then does it write `dist/roastbook-X.Y.Z.apk`,
   `dist/SHA256SUMS` and `dist/roastbook-X.Y.Z-mapping.txt`. `scripts/release.sh --verify <apk>` runs the checks on any
   APK. Both need `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) with build-tools 35 or newer, and
   `unzip`. The ELF check needs `readelf` (binutils, or `llvm-readelf` on the `PATH` or in the
   SDK's NDK): a build stops without it, and `--verify` skips that one check and says so in its
   last line.

   The mapping file is R8's map from the release build's shortened class and method names back
   to the source names. A crash report from a release install is unreadable without it, and
   every build renames differently, so only the mapping from the build you shipped will do. Keep
   it with your copy of the release (`dist/` is gitignored), and retrace a stack trace with
   `retrace` from the SDK's command-line tools:
   `retrace dist/roastbook-X.Y.Z-mapping.txt stacktrace.txt`. It is not a release asset: it
   would only help someone read the obfuscated code.
7. **Walk the release build.** Install the APK on an emulator with no debug build of Roastbook
   (a tablet image with 16 KB pages exercises the most) and walk: intro, add a bag, rating,
   search, Settings, Back up now, Restore from that zip, About, Open-source notices, Check for
   updates. R8 runs in release builds only, so this walk is what catches a class it stripped
   (ML Kit's component registrars were stripped once; `proguard-rules.pro` keeps them now).
   Watch logcat for `ComponentDiscovery`, `FATAL EXCEPTION`, `ClassNotFound` and
   `NoSuchMethod`.
8. **Publish.** Create the release as a draft from the tag, attach the APK and `SHA256SUMS`
   (never the mapping file), paste the changelog section and the certificate SHA-256 into the
   notes, then publish. Give the certificate in both forms, the colon form above and the
   lowercase form `apksigner verify --print-certs` prints, and name
   `scripts/release.sh --verify roastbook-X.Y.Z.apk` as the easy check:

   ```sh
   gh release create vX.Y.Z dist/roastbook-X.Y.Z.apk dist/SHA256SUMS --draft \
     --title "Roastbook X.Y.Z" --notes-file <notes.md>
   gh release edit vX.Y.Z --draft=false
   ```

   The in-app update check reads GitHub's latest release, so a published release is what
   existing users will be told about. Pre-releases and drafts are never offered.

## Phones that run a debug build

A debug build is signed with the build machine's debug key, and Android only installs an update
signed with the same key, so the first release install on such a phone fails with a signature
mismatch. The way through: Settings, Back up now (to a folder you can reach), uninstall, install
the release APK, Settings, Restore from that zip.

## Testing the tag check

`tools/test-release-tag-check.sh` runs `scripts/release.sh` on scratch repositories with
throwaway SSH and OpenPGP keys, a throwaway keystore and a stub build, and prints one line per
case: a correctly signed tag passes (also when the signer's git email differs from the principal
the file lists, when the key's line carries an options field, and when git config names the file
with `~/`); the same tag under the default pin, a lightweight tag, an unsigned tag, an
OpenPGP-signed tag (gpg is never run), a key the file does not list, a listed key that is not the
pinned one, a signed tag object made for another name, a tag moved during the build, a tag
signed by an unlisted key while git config points `gpg.ssh.program` at a verifier that calls
everything good, and a branch named like the tag (refused before any build) are each
refused with their own message and leave `dist/` unwritten. It needs git, ssh-keygen, gpg,
keytool and python3, works in a temporary directory with its own HOME (your keys, keyring and git
settings are never read), and exits non-zero if any case gives the wrong result. The Gradle gate
does not run it; run it after any change to `scripts/release.sh`:

```sh
tools/test-release-tag-check.sh
```

## CI

`.github/workflows/android.yml` runs unit tests, the coverage gate, lint and a debug build on
every push to `main` and on pull requests. It has read-only permissions, pins every action to a
commit, holds no secrets and has no release job.
