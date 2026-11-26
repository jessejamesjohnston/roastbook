# Architecture

Single Gradle module, Kotlin, Jetpack Compose (Material 3), Hilt, Room,
CameraX, ML Kit (Text Recognition, Subject Segmentation, GenAI Prompt API),
WorkManager, DataStore, Coil.

The code lives under the Kotlin package `dev.roastbook.app`; the installed
application id is `com.merrimentlabs.roastbook`. The two are independent in
Android, and the package name stays as it is because WorkManager jobs and
alarms already scheduled on existing installs refer to classes by that name.

## Data

Room is the source of truth. Tables: `coffee` (one row per bag, all extracted
facts, the rating, nudges, provenance), `coffee_photo` (role, position,
relative paths of the original, the hero square and the thumb), `source`
(where the coffee came from; seeded picklist plus learned values).

Photos live under `files/photos/<coffeeId>/`. Originals are stored at 2048 px
on the long edge; the hero photo also gets a 1024 px square and a 256 px
thumb. Paths in the database are relative to `files/` so a backup is
portable.

Schema changes go through Room migrations (`RoastbookDatabase.MIGRATIONS`),
with the exported schema JSON under `app/schemas/` and a migration test that
builds the old database from that JSON.

## The extraction state machine

```
create()      DRAFT ──photos imported──▶ EXTRACTING
EXTRACTING    OCR every photo (2048 px)
              ├─ 0. reader chosen in Settings and downloaded → Nano skipped ↓
              ├─ 1. Nano (system model; images 1024 px + OCR text) → JSON → parse
              │     ├─ facts                  → LOGGED / RATED, source NANO
              │     ├─ poor (not the JSON asked for, or no roaster and no coffee)
              │     │   and the reader downloaded → the reader reads too; more fields wins
              │     │   (a tie goes to the reader when its first answer was valid JSON
              │     │   and Nano's was not)
              │     ├─ unparseable            → one strict retry, then ↓
              │     ├─ model downloading      → PARKED: stays EXTRACTING, retried at +3 min,
              │     │                           then +5 min; the third miss falls through ↓
              │     ├─ background blocked     → retried on the next foreground; third miss ↓
              │     ├─ usage limit (AICore meters each app: reads in a row, or battery)
              │     │                         → PARKED: retried at +1 min, then +2 min (longer
              │     │                           if AICore names a wait, up to 5 min); a longer
              │     │                           wait or the third refusal falls through ↓, the
              │     │                           card names which limit, and the device is not
              │     │                           marked as failing
              │     └─ unavailable / threw / timed out → ↓
              ├─ 2. on-device reader (Gemma 4 E2B, LiteRT-LM, CPU) if downloaded → LOCAL
              ├─ 3. user's Gemini key → same prompt → CLOUD
              ├─ 4. OCR text non-empty → rule-based guess, blank fields only → OCR
              └─ nothing readable → LOGGED, source NONE
rate()        LOGGED → RATED (score = mean of Aroma, Flavor, Finish)
```

Rules the code holds to:

- Every tier call is time-bounded (`ExtractionTimeouts`, text recognition of
  each photo included), and Nano's model download runs detached from the read
  that asked for it, so a download that never reports back cannot hold the
  queue. A read on the on-device reader counts its attempt before the model
  runs, so a bag that takes the process down still reaches the attempt cap.
  One limit: the on-device reader is native code in our process. Its deadline
  asks the runtime to stop (`cancelProcess()`) and then waits for the call to
  return, because the engine must not be closed or reused under it; loading
  the engine cannot be interrupted at all. Both returned promptly in emulator
  testing, but a native call that never returned would hold the queue.
- Each read records what every tier did in `extraction_trail`
  (`nano=unsupported;local=absent;cloud=off;guess=5`). The detail screen
  derives its "what happened" note and the "getting the reader ready" state
  from it (`WhatHappenedRules`).
- Every write from the extractor re-reads the row inside a transaction and
  touches only extraction-owned columns. A rating, reminder or hand edit made
  while a read was running survives.
- A field the user edited by hand is listed in `user_edited` and keeps the
  user's value on every later read, including Reread. The tier's suggestion
  for it is dropped and the provenance note lists it.
- The guess fills blank fields only and never touches a coffee a model
  already read. It never guesses the coffee's name.
- Every tier's reading passes `FieldCleanup` before it is saved (the parser
  applies it too, so quality checks see clean values): a value made only of
  placeholder words (`null`, `none`, `n/a`, `unknown`, `undefined`) is empty,
  quotes, commas, brackets and braces left at either end by a broken answer
  are trimmed (a quote, bracket or brace only when it has no partner), and a
  roast date with no digit at all is empty. Any other roast date is kept as
  written, whatever its style (`03NOV26`, `KW 45`): nothing parses it, it is
  shown and edited as text. A tasting note that repeats a process of more
  than one word is dropped (a one-word process such as "Honey" can be a
  note too). It only removes.
- `NetWeight` reads the label's net weight as one quantity: whole numbers,
  decimals, fractions ("1/2 lb", "½"), mixed numbers, pounds-and-ounces, and
  pairs in both unit systems ("12 oz (340 g)"), which must agree. Only a real
  unit token counts, so lot codes, prices and dates are never read as weights.
  Inside an amount, a letter that is only ever a misread digit (O, I, l, S, B,
  Z) is read as that digit. A model's bag size gives way to the net line only
  when the line reads unambiguously inside 50 to 2500 g and the pair agreed,
  or the model's own figure is outside that band (a gram figure whose "g" was
  read as a 9, such as 3409 for a 12 oz bag). A digit misread as another digit
  ("70 oz") can't be seen on a single unit, so a single unit never replaces a
  plausible figure. It never fills a blank or touches a hand edit; the
  label-text guess uses the same reading.
- A model's reading (Nano, the reader or a Gemini key) is garbled when its
  first answer was not the JSON asked for. The trail lists each field a
  garbled reading changed (`garbled=coffeeName,roastDate`), plus any listed
  field it repeats, and Reread keeps that list. A later reading that is not
  garbled replaces each listed field it has a value for. Unless it
  is a poor Nano reading (one naming neither the roaster nor the coffee), it
  also clears each listed field it has nothing for: a blank to fill in beats
  a confident wrong value. Fields the user edited are never touched. A value
  from an earlier clean reading stays unless a garbled reading changed it,
  which lists it like the rest. A reading that is garbled too, or the guess,
  keeps the listed fields it has nothing for, and they stay listed.
- `extraction_source` names the tier that produced the retained fields. A
  failed re-read keeps the previous source.
- The foreground scheduler runs only while the app is visible (Nano refuses
  background calls) and wakes for parked retries. When the app leaves the
  foreground with reads pending, `ExtractionWorker` continues the ones that
  do not need Nano (a Nano that works here is left for the foreground). Both
  runners take the same lock and re-check the row before reading it.
- Every branch has a unit test with fake tiers (`ExtractorTest`).

## The on-device reader

- **Model:** `gemma-4-E2B-it.litertlm` from `litert-community` on Hugging
  Face, pinned to a repository commit, 2,588,147,712 bytes, SHA-256
  `181938105e0eefd1...`. CPU backend for decoder and vision encoder: the GPU
  backend corrupts output on prompts past about 2k tokens (LiteRT-LM #3012).
- **Offered** on devices with at least 6.5 GiB reported total memory (every
  phone sold with 8 GB) and an arm64-v8a or x86_64 CPU. Settings shows it on
  every such device; where Nano works it carries a switch, off by default, to
  read with the reader instead (`prefer_reader`). Onboarding mentions it only
  where Nano is unavailable. A Nano failure sets a device-scoped flag in
  `no_backup/`, and Settings then says why it is offered.
- **Download:** a WorkManager foreground job (data sync), unmetered network
  unless the user allows mobile data. Writes `<name>.part`, resumes with an
  HTTP Range request, re-requests the published URL each time (the CDN
  redirect is signed and expires), verifies size and SHA-256, then renames.
  A `.verified` stamp avoids re-hashing on every check. Until the first byte
  arrives the download reads as starting: WorkManager reports a new job
  ENQUEUED (measured: 14 ms before RUNNING on an emulator), then RUNNING
  before any progress, so a first attempt with nothing on disk reads as
  starting for its first 2 seconds enqueued, and as waiting for a network
  only after that.
- **The offer on a coffee's page** (`ReaderNudge`, `ReaderOfferCard`): after
  Nano reads a bag poorly (not the JSON asked for, or neither roaster nor
  coffee) or times out, throws or finds nothing, and neither the reader nor a
  Gemini key then read it, the coffee's page offers the reader with its size
  and how it downloads. Only where the reader can run
  and is neither downloaded nor downloading, never twice on the same coffee,
  and not at all after two "Not now" answers to the offer (Not now on a
  failed download does not count toward the two).
- **Bags that wait for the reader:** Download on the card turns
  `prefer_reader` on, adds the coffee to `pending_rereads` (device-scoped
  DataStore under `no_backup/`) and starts the download. A bag Nano reads
  poorly while that download is on its way joins the list. The card follows
  the download on each waiting bag's page. When the model is ready,
  `ReaderRereads` empties the list in one write and reads each of those bags
  again, also after a restart if the download finished while the app was
  gone; fields the user edited keep their values.
- **Pause, Cancel, Delete, Not now:** Pause (in Settings or the
  notification) stops the job and keeps the partial file for a Range resume;
  the waiting bags and the switch stay. Cancel download (card or Settings)
  removes the partial file, and Delete reader (Settings) removes the model,
  the partial file and the runtime cache. Both empty `pending_rereads`, turn
  `prefer_reader` off and count the waiting bags as answered. WorkManager
  records a canceled job as CANCELLED, as it does a paused one, so Cancel and
  Delete store the ids of the jobs they stop and only other stopped jobs read
  as paused. On a failed download, the card offers Try again and Not now; Not
  now only takes that bag off the list and marks it answered, without
  counting toward the two "Not now" answers that stop the offer (the person
  already said yes once). The partial file, the switch and the
  failure in Settings stay, and Settings keeps Try again and, with part of
  the file on disk, Cancel download.
- **Storage:** `no_backup/reader/` (model, partial download, runtime cache).
  Neither backup tier includes it. Settings shows its size and deletes it.
  Once the engine has loaded the model, the folder holds about 1 GB more
  than the model (measured: 2.6 GB right after the download, 3.6 GB after
  reads), which is LiteRT-LM's cache (its `cacheDir` points there). Settings'
  line names everything beside the model, its stamp and a partial download
  as that cache.
- **Runtime:** one engine per process, loaded on first use, closed after a
  minute idle; calls are serialized, and a cancelled read stops the native
  call and waits for it before the engine is reused or closed.

## Hero image

Subject Segmentation finds the bag; the densest part of the mask becomes a
square crop; the palette of the segmented subject drives a per-row tint
through an OKLCH "keep the hue" rule (`TintRule`, unit-tested against the
design's reference colors). Failures fall back to a centered square, retried
on the next foreground up to three times.

## Backup

- **Tier 0 (Android Auto Backup):** `data_extraction_rules.xml` includes only
  `autobackup/` and `datastore/`. `autobackup/` holds a consistent copy of
  the database (`VACUUM INTO`, refreshed when the app goes to the background
  after a write) and a mirror of the thumbs. On a fresh install with no live
  database, the copy is adopted before Room opens.
- **Tier 1 (zip to a user-chosen folder):** `BackupManager` (a facade over
  `BackupWriter`, `BackupRestorer` and `AutoSnapshot`) writes
  `roastbook-<timestamp>.zip` through a `BackupSink` (Storage Access
  Framework tree in production, a directory in tests): the database copy,
  `photos/**`, and `manifest.json` with per-file SHA-256. Written to a temp
  name and renamed, so the finished archive appears under its final name
  only when complete (when a provider uploads is up to the provider). Keeps
  the newest seven. A daily WorkManager job runs it while a folder is set.
  Every archive is checked against the restore's limits before rotation.
  A folder that can no longer take backups is forgotten, which turns
  backups off; Settings and the History banner say so until a folder is
  chosen. Access gone is checked up front (Settings opening, the daily run
  starting). A folder that can't be read, or whose provider refuses the
  final name, is judged only after a backup to it fails: a provider that
  is briefly silent (mid-update) must not switch backups off. Any other failure keeps the folder and is shown as the last
  backup's failure until the next success.
- **Restore** verifies every hash and size, refuses paths outside
  `photos/<uuid>/<uuid>[.square|.thumb].jpg`, refuses a newer schema, opens
  the staged database through Room so older backups migrate, replaces rows
  in one transaction and swaps the photo directory. Restored rows may only
  name ids and photo paths of that same shape. A marker names the set-aside
  photo tree and the restored library, so a start after a kill either rolls
  back (the rows did not commit) or keeps the restored photos (they did).
  Restoring the same zip twice ends in the same state. The swap and the rows go
  in under the photo store's capture lock with a new library generation; a
  hero crop that started before compares the generation (and that its coffee
  and photo rows are still there) under the same lock before it writes, so it
  never writes into a restored or deleted coffee, even one with the same ids.

## Cloud tier (optional)

`GeminiClient` is a minimal REST client for `generateContent`: the key goes
in a header, never a URL or a log. `GeminiCloudTier` reuses the extraction
prompt and parser. `Enricher` asks a Google-Search-grounded model for the
roaster's homepage, the product page and the city, stored as suggestions.
It runs only from the Look up roaster button on Detail (`Enricher.lookUp`):
reads in either runner go through `ReadPass`, which never calls it, so the
lookup never runs without a tap (`TapOnlyLookupTest`). A cloud read, which
runs only with a key and when on-device reading fails, sends the photos and
the text recognized from them.
The key is AES-GCM encrypted under an Android Keystore key and stored in
`files/secrets/`, which neither backup tier includes.

## Reminders

Two nudges per coffee at most: the rate-it reminder chosen when the bag is
logged, and one second look a few days after the first rating. Both are
inexact alarms; both are consumed the moment they fire so an app restart
never re-arms them. Tapping either returns to the list and opens the rating
sheet.

## Large screens

`PaneRules.split` decides one pane or two from the window size and any
vertical fold reported by Jetpack WindowManager (through material3-adaptive's
`currentWindowAdaptiveInfo`): two panes at 840 dp and up, in a landscape
window of at least 600 x 480 dp, or at 600 dp and up beside a vertical fold,
where the split lands on the fold. The nav host keeps the picked coffee in
saved state. When the window crosses the line (rotation, fold, resize), it
moves the open coffee between the phone's Detail route and the right-hand
pane, so the same coffee stays on screen.

`DetailViewModel` takes its coffee id by assisted injection, so one page
serves both hosts. An open edit lives in `EditSessions`, an activity-scoped
ViewModel, rather than in the page: a page being replaced during a layout
move hands the edit over instead of saving or dropping it. Only the page that
owns the edit may commit it, and never during a configuration change. On a
phone, leaving a card saves it; on a large screen the edit sits in a dialog
with explicit Save and Discard. The edit is also kept in the activity's saved
state, so text typed into an open card survives Android stopping the app in the
background. If the coffee open in the right-hand pane disappears (a restore that
replaced the journal), the pane goes back to its empty state.

## Appearance

The theme choice is applied through `UiModeManager.setApplicationNightMode`,
so the system stores it per app and every `-night` resource, the window
background and the status bar follow it. The DataStore copy is re-applied at
start, which reconciles a restored backup. High contrast swaps in 7:1 color
schemes and drops the per-coffee tint. `ContrastTest` checks every text and
background pair in all four schemes and on the tint rule's output across the
hue circle. Languages: `AppLanguage.supported`, `res/xml/locales_config.xml`
and the `values-*` translations must agree (`LanguagesTest`).

## Update check

Roastbook is distributed as an APK on GitHub, so Settings, About has a
Check for updates row. `UpdateChecker` makes one unauthenticated GET to
`api.github.com/repos/jessejamesjohnston/roastbook/releases/latest` with a
fixed `User-Agent` and no cookies, account, device or version, compares the
tag with the installed `versionName` number by number (`AppVersion`), and,
when the release is newer, opens the release page in a Custom Tab. The page
address is built by the app, never taken from the response. The app never
downloads or installs an APK itself, so it needs no install permission.

The Check weekly switch below it is off by default. Turning it on arms a
weekly WorkManager job (`WeeklyUpdateCheck`, network required) that runs the
same check and posts one low-importance notification per new version;
turning it off cancels the job. With the switch off, the app contacts GitHub
only when the row is tapped.
