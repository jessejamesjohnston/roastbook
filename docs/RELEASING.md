# Releasing Roastbook

Roastbook is released as a signed APK on GitHub. Every release is built from a clean checkout of
its tag and signed on the maintainer's machine with the app's release key, by
`scripts/release.sh`. CI never sees the key and never builds a release; it builds and tests only.

## The release key

- One key signs every release, so each one installs as an update over the last. A lost key or
  password means no more updates to installed copies, so the keystore is backed up on a second
  machine and its password lives in a password manager, never in a file. The keystore and the
  key share that one password; `scripts/release.sh` asks for it once and uses it for both.
- Certificate SHA-256 (published in the README and in every release's notes so anyone can check
  an APK):
  `D8:79:AD:13:90:76:D9:92:D6:37:CF:A9:E3:A4:6C:84:2C:9C:E1:99:0A:AC:2A:B6:5B:0F:D7:6E:97:76:38:E1`
- Never commit a keystore (`*.jks` and `*.keystore` are gitignored) and never put the password in
  a file, `local.properties`, a command line or a log.

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
   plus that version.
2. Find your certificate's SHA-256. The script refuses an APK signed with any other certificate,
   and without this it expects the official one:

   ```sh
   keytool -list -v -keystore ~/keys/mykey.p12 | grep 'SHA256:'
   ```

3. Tag and build:

   ```sh
   git tag v0.4.0-mine
   ROASTBOOK_KEYSTORE_PATH=~/keys/mykey.p12 \
   ROASTBOOK_EXPECTED_CERT=<the SHA256 value from step 2> \
   scripts/release.sh v0.4.0-mine
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
5. **Tag.** Commit, then make a signed tag `vX.Y.Z` and push it. A tag starts no release build.
6. **Build.** `scripts/release.sh vX.Y.Z`. It clones the tag into a temporary directory, builds
   `:app:assembleRelease` with R8 there, and verifies the APK:
   - `apksigner verify`, the certificate SHA-256 equals the expected one, and the certificate is
     not a debug certificate;
   - the commit the Android Gradle Plugin records in `META-INF/version-control-info.textproto`
     is the tag's commit (with `--verify`, only that a commit is recorded);
   - 16 KB page sizes: `zipalign -c -P 16 -v 4` (build-tools 35 or newer), and every 64-bit
     `.so` has ELF LOAD alignment of at least `0x4000`.

   Only then does it write `dist/roastbook-X.Y.Z.apk`, `dist/SHA256SUMS` and
   `dist/roastbook-X.Y.Z-mapping.txt`. `scripts/release.sh --verify <apk>` runs the checks on any
   APK.

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
   notes, then publish:

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

## CI

`.github/workflows/android.yml` runs unit tests, the coverage gate, lint and a debug build on
every push to `main` and on pull requests. It has read-only permissions, pins every action to a
commit, holds no secrets and has no release job.
