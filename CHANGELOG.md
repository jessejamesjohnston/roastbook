# Changelog

All notable changes to Roastbook are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow the app's
`versionName`. Versions 0.2.0 to 0.3.5 were private test builds and were not published.

## [Unreleased]

### Fixed

- A model answer that is almost JSON no longer leaks text into a field. When one value broke
  the object (seen on one device: a bare `null` followed by a stray quoted name), the fallback
  parser glued the two together and saved `null "Name` as the producer. It now reads each value
  as one token, so a bare `null` stays empty, a value cut off by the token limit is dropped
  instead of saved half-written, and keys that share a line with the opening brace or with
  each other are no longer skipped.

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
