# Security

## Reporting a problem

Please report security problems privately, not in a public issue: use GitHub's
[private vulnerability reporting](../../security/advisories/new) for this repository, or write
to hello@merrimentlabs.com. Say what you found, how to reproduce it, and which version
(Settings, About) you tested. You will get an answer as soon as I can manage; this is a
one-person project.

## What is in scope

Roastbook has no server and no account, so its surface is the app on your device and the few
requests it makes:

- The Gemini API key store (`cloud/SecretStore.kt`): the key is encrypted under an Android
  Keystore key, never logged, and excluded from both backup tiers.
- Backups and restore (`data/backup/`): a crafted backup zip should never write outside the
  app's photo folder, replace data without passing verification, or crash the app.
- The optional model download (`reader/ModelDownloader.kt`): pinned to a commit and a SHA-256.
- Links the app opens (`util/WebLinks.kt`): only http and https pages.
- The update check (`update/`): one request to GitHub's public API; the app never downloads or
  installs an APK.
- Exported components. In the merged release manifest:
  - `MainActivity` is exported because the launcher must start it. It reads one extra, the id
    of a coffee to rate, which its own reminder notification sends, and drops every other extra
    before any screen reads the intent. Another app can at most ask it to open the rating sheet
    for an id it would have to guess; an unknown id opens nothing.
  - Roastbook's own receivers (the reminder receiver and the boot receiver) are declared
    `exported="false"`.
  - Three components that AndroidX libraries add are exported, each behind a permission that
    only the system (and, for `DUMP`, `adb`) holds: WorkManager's `SystemJobService` requires
    `android.permission.BIND_JOB_SERVICE`, and WorkManager's `DiagnosticsReceiver` and the
    profile installer's `ProfileInstallReceiver` require `android.permission.DUMP`.
  - No other component, from Roastbook or a library (ML Kit and Play services included), is
    exported.

Google's ML Kit and Play services are outside this project's control; report problems in them
to Google.

## Supported versions

Only the latest release gets fixes.
