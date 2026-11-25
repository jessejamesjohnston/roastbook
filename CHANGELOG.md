# Changelog

All notable changes to Roastbook are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow the app's
`versionName`. Versions 0.2.0 to 0.3.5 were private test builds and were not published.

## [Unreleased]

### Added

- After a poor reading by the device's built-in AI (an answer that was not the JSON asked for,
  one with neither roaster nor coffee, or a timeout), a coffee's page offers the downloadable
  reader in place, with its size, that it runs on the device and that it waits for Wi-Fi.
  Download makes the reader this device's choice and reads that bag again when it arrives;
  Not now is remembered for that bag, and after two the offer stops.
- Settings, On-device reader: shown on every device that can run the downloadable reader, not
  only where the built-in AI is missing. Where the device has its own AI, a switch (off by
  default) has the reader read bags instead, once it is downloaded. Both run on the device.

### Changed

- A built-in AI reading that was not the JSON asked for, or that names neither the roaster nor
  the coffee, is checked against the downloadable reader when it is on the device, and the reading with more fields is kept.

### Fixed

- `scripts/release.sh`: a key alias matches regardless of case; keytool runs in English and
  its listing is parsed without depending on its spacing; a keystore with no private key gets
  its own message; the password must also unlock the key, checked before the build; and
  `--verify` reports an APK modified after signing as "the signature does not verify" instead
  of "not the expected certificate".
- The rating sheet opens fully expanded with Save and Cancel pinned below the scores, so they
  are on screen in a short window (a desktop window that is not maximized) without dragging the
  sheet up first.
- A model answer that is almost JSON no longer leaks text into a field. When one value broke
  the object (seen on one device: a bare `null` followed by a stray quoted name), the fallback
  parser glued the two together and saved `null "Name` as the producer. It now reads each value
  as one token, so a bare `null` stays empty, a value cut off by the token limit is dropped
  instead of saved half-written, and keys that share a line with the opening brace or with
  each other are no longer skipped. Unescaped quotes inside a value, and names that begin with
  a word like "True" or "Null", are kept.

## [0.4.0] - 2026-11-24

The first public release.

### Added

- Photograph a bag (as many photos as you like) and Roastbook reads the label on the device:
  roaster, coffee, origin, region, producer, process, varietal, elevation, roast and tasting
  notes. Every field is a suggestion you can edit, and each coffee says how it was read.
- Reading falls back in order: the phone's built-in Gemini Nano, an optional open model you can
  download once (2.6 GB, for phones with 8 GB of memory or more), Gemini with your own API key,
  then a marked guess from the printed text.
- Rate each coffee on aroma, flavor, acidity, body and finish, with an overall score, notes,
  price and whether you would buy it again. A reminder to rate it, and one second look after
  your first rating.
- Search, sort and filter the journal; rows tinted with each bag's colors.
- Backups: Android's backup carries the journal, and a zip with full photos can go to a folder
  you choose, daily or on demand, with a verified restore.
- Two panes on tablets and foldables, a dark theme, a high-contrast mode and reduced motion.
- Settings, About: Check for updates asks GitHub for the latest release and opens its page
  when it is newer. A Check weekly switch (off by default) does the same once a week and posts
  one notification per new version. The app never downloads an update itself.
