# Changelog

All notable changes to Quiet Archive are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[semantic versioning](https://semver.org/).

## [1.2.2] - 2026-11-19

### Changed
- Saved-book titles, authors, covers and metadata are cached on device. The
  SAVED tab now shows real rows immediately after launch; network refreshes
  happen quietly in the background at most once a day.
- Book covers are cached on disk too, so opening the library cold no longer
  re-downloads every thumbnail.

## [1.2.1] - 2026-11-19

### Fixed
- Downloads no longer get stuck at 403 or endlessly cycling through mirrors.
  The site's DDoS-Guard blocks the native HTTP client on every `/slow_download/`
  page, so the app now resolves that page (including its waitlist) through the
  same in-app browser session that search uses, then hands only the final file
  link to the downloader. Two consecutive books downloaded successfully on a
  physical phone with this build.
- Automatic retries are capped so a failed download stops cleanly and shows a
  useful error instead of hammering the site every two seconds.

## [1.2.0] - 2026-11-18

### Added
- Built-in reader for EPUB (table of contents, chapter navigation, images),
  TXT/Markdown, FB2, HTML and PDF books, with three page themes, adjustable
  text size and reading position that survives closing the app.
- Listen to a book through the phone's text-to-speech engine, or export the
  whole book to a WAV audiobook in Downloads (the local, on-device idea behind
  OpenAudioBookify) — no account or cloud service involved.
- Audio player for downloaded audiobooks: resume point, 15-second skips,
  playback speed from 0.8x to 2x, and a sleep timer.
- Downloads can be cancelled at any time, with a CANCEL action both on the
  book page and in the download banner.
- Failed downloads now try the book page's other servers automatically before
  giving up; RETRY in the error banner starts the whole chain again.

### Fixed
- The SAVE button and the library now follow bookmark state immediately —
  the book page no longer claims SAVED when a book is not saved, and SAVE /
  SAVED update on the first tap.
- OPEN / DOWNLOAD buttons react to the live library and download state instead
  of freezing at whatever they showed when the page opened.
- The library and saved lists show loading placeholders instead of looking
  empty on launch; saved bookmarks resolve through the fast HTTP path first.
- Pressing BACK from a reader, book page or list now returns to the previous
  screen; at the top level it sends the app to the background instead of
  quitting and relaunching it.

## [1.0.0] - 2026-11-11

### Added
- Search against the live Anna's Archive with relevance sorting, 50 results per
  page, infinite scroll and preemptive loading of the next page.
- Result cards with title, authors, year, format, language, size and publisher.
- Book pages with cover, expandable description, a details table and the site's
  download list (fast / slow / external).
- Internal downloader that resolves the Anna's Archive waitlist and saves the
  file to a folder picked with the system document picker.
- Library with downloaded books (format, size, date, read/unread) and a separate
  tab for bookmarks.
- Settings for theme, storage folder, base URL and an in-app sign-in browser
  that shares its session cookies with the native core.
- Monochrome light and dark themes, no colour, no gradients.

## [1.1.0] - 2026-11-13

### Added
- In-app human verification for Anna's Archive's browser check: when the site's
  DDoS-Guard escalates to a manual captcha, the session web view is surfaced
  full-screen so the check can be completed in the app, then hidden again the
  moment clearance is detected. Cookies persist for the rest of the session, so
  the check is at most once per launch.

### Fixed
- Search no longer hangs indefinitely behind the site's browser check; the
  surfaced check has its own 5-minute window and the search as a whole a
  10-minute budget, both reported through the existing error banner with retry.

## [1.1.1] - 2026-11-18

### Fixed
- Downloads blocked by the site's bot protection (HTTP 403 to the native
  client) now recover on their own: the app walks the book page through its
  browser session — surfacing the manual check to the user if the site
  escalates — re-syncs the session cookies and re-issues the download once.
- Downloads no longer get stuck at "saving" when the same book is downloaded
  twice in one app session (delete + re-download used to hang forever).
- Downloads without a chosen folder now land in the phone's public Downloads
  folder (visible in any file manager) instead of app-private storage.

[1.1.0]: https://github.com/ssuvamm/quiet-archive/releases/tag/v1.1.0
[1.0.0]: https://github.com/ssuvamm/quiet-archive/releases/tag/v1.0.0
