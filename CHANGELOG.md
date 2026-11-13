# Changelog

All notable changes to Quiet Archive are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[semantic versioning](https://semver.org/).

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

[1.1.0]: https://github.com/ssuvamm/quiet-archive/releases/tag/v1.1.0
[1.0.0]: https://github.com/ssuvamm/quiet-archive/releases/tag/v1.0.0
