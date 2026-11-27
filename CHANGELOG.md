# Changelog

All notable changes to Roastbook are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow the app's
`versionName`. Versions 0.2.0 to 0.3.5 were private test builds and were not published.

## [0.4.1] - Unreleased

### Added

- A coffee's photos open full window from its page: tap the bag photo or any photo in the strip.
  Swipe, the side buttons or the arrow keys move between them; pinch, double tap, the scroll wheel
  or + and - zoom; Back, Escape or Close return to the page.

### Changed

- On a Chromebook or Googlebook, links that leave the app (About, a roaster's site) open in your
  browser as a normal tab. Phones and tablets keep the in-app browser tab, or use the browser when
  none is available.
- When the built-in AI needs a break after reading several bags in a row, or Android pauses it to
  save battery, the bag keeps reading: it says which, and when it will try again (after a minute,
  then two, never longer than five). With the on-device reader downloaded, the reader reads the
  bag at once instead, and the page says so. Your Gemini key is never used just because the
  built-in AI is resting. It no longer reports the built-in AI as failing.

- When the built-in AI isn't ready yet (its first download, or just after a system update),
  Roastbook keeps trying for about 15 minutes instead of 11 before reading the label text, and
  says Android is getting it ready rather than that it is downloading.

- When a reader leaves a field blank that the label text names (country, process, varietal,
  elevation and the like), Roastbook fills it with its guess from the label text and says which
  fields are guesses. It never replaces a value the reader or you set.

- The rating sheet says why Save is off until aroma, flavor, and finish are rated ("Rate all three
  to save" where the full sentence won't fit), and with large text on a narrow phone its buttons
  keep their words on one line.

### Fixed

- On a Chromebook or Googlebook Roastbook opens as a normal resizable window (720 by 640 dp to
  start, down to 320 dp), like other apps, instead of full screen.
- The intro can be stepped back through: a Back button on every page after the first, and the
  system Back or Escape go back one page. Settings, About has "Show the intro again".
- Before Android asks for notifications (a reminder, the reader's download) or the camera, the
  reason now comes as a proper dialog with a title, Continue, and Not now, which asks nothing.
- The "Show only" sheet's date row reads like its other rows, and every date choice, "Any time"
  included, is a chip that shows when it is chosen.
- A bag size that can't be right (often a misread gram figure, such as 3409 g for a 12 oz bag)
  gives way to the net weight printed on the label, when the label states it clearly. A bag size
  you typed is never changed.
- A tasting note that repeats a process of more than one word (such as "Lychee carbonic
  maceration") is dropped.
- A reading from your own Gemini key that wasn't the JSON asked for no longer clears values an
  earlier reading filled, as with the other readers.
- Dates, times and numbers inside the app's text (the restore question, the journal and coffee
  page dates, retry times, counts, scores and download sizes) are English, like the rest of the
  app, whatever the device's language: English digits, and "coffee" or "coffees" chosen by English
  rules (a French device no longer reads "0 coffee").
- With very large text on a narrow phone, the journal's title and coffee names no longer break in
  the middle of a word.
- A one-line note on a coffee's page, such as "Read by the on-device reader", is no taller than its
  line.

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
