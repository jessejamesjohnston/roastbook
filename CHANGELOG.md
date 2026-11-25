# Changelog

All notable changes to Roastbook are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow the app's
`versionName`. Versions 0.2.0 to 0.3.5 were private test builds and were not published.

## [Unreleased]

## [0.4.0] - 2026-11-25

The first public release.

### Added

- Photograph a bag (as many photos as you like) and Roastbook reads the label on the device:
  roaster, coffee, origin, region, producer, process, varietal, elevation, roast and tasting
  notes. Every field is a suggestion you can edit, and each coffee says how it was read.
  Placeholder words and stray JSON punctuation in a model's answer are never saved as a value,
  and a roast date without a single digit is left blank (any other is kept as printed).
- Reading falls back in order: the phone's built-in Gemini Nano, the on-device reader (an open
  model you can download once, 2.6 GB, for phones with 8 GB of memory or more), Gemini with your
  own API key, then a marked guess from the printed text. Both the built-in AI and the on-device
  reader run entirely on the device.
- Settings, On-device reader: shown on every device that can run the reader, whether or not it
  has a built-in AI. Where it does, a switch (off by default) has the reader read bags in place
  of the built-in AI, once it is downloaded. Canceling the download or deleting the reader turns
  the switch off. The download says it is starting over a moving bar until the first bytes
  arrive, then shows a bar that fills as they do. Once the reader has run, its folder also holds
  the runtime's cache (about 1 GB), and Settings says how much of the size on disk that is;
  Delete removes it with the model.
- When the built-in AI reads a bag poorly (an answer that is not the JSON asked for, or one that
  names neither the roaster nor the coffee) and the on-device reader is downloaded, the reader
  reads it too, and the reading with more fields is kept. On a tie, the reader's reading is kept
  when its first answer was valid JSON and the built-in AI's was not.
- After a poor reading by the built-in AI, or one that times out, a coffee's page offers the
  on-device reader in place, with its size, that it runs on the device, and whether it downloads
  over Wi-Fi only or also over mobile data. Download makes the reader this device's choice and
  reads that bag again when it arrives, along with any other bag the built-in AI reads poorly
  meanwhile. Until then the card follows the download (progress, paused, waiting for Wi-Fi, or
  why it failed) and can cancel it while it runs, waits, or is paused. When it failed, the card
  offers Try again, or Not now, which stops that bag waiting and leaves the partial download
  and the switch for Settings to retry or cancel. Not now on the offer is remembered for that
  bag, and after two of those the offer stops. When the reader's first answer is valid JSON,
  its reading replaces the fields the built-in AI filled or changed from an answer that wasn't
  the JSON asked for, or leaves them blank where it has nothing. Fields you edited stay, and so
  does a value from an earlier clean reading that the broken answer left as it was.
- Rate each coffee on aroma, flavor, acidity, body, and finish, with an overall score, notes,
  price, and whether you would buy it again. A rating saves once aroma, flavor, and finish are
  set. A reminder to rate it, and one second look after your first rating.
- Search, sort and filter the journal; rows tinted with each bag's colors.
- Backups: Android's backup carries the journal, and a zip with full photos can go to a folder
  you choose, daily or on demand, with a verified restore.
- Two panes on tablets and foldables, a dark theme, a high-contrast mode and reduced motion.
- Settings, About: Check for updates asks GitHub for the latest release and opens its page
  when it is newer. A Check weekly switch (off by default) does the same once a week and posts
  one notification per new version. The app never downloads an update itself.
