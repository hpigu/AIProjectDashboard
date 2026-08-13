# Changelog

User-facing changes follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and [Semantic Versioning](https://semver.org/).

## [Unreleased]

No unreleased user-facing changes.

## [3.2.1] — 2026-08-13

### Fixed

- Made Windows release startup and update checks reliable with paths containing
  spaces or non-ASCII characters.
- Fixed checksum parsing across PowerShell editions, locales, BOM-encoded files,
  and folded release manifests.
- Isolated release smoke tests from inherited board environment variables.
- Improved updater diagnostics and rollback test coverage.

## [3.2.0] — 2026-08-12

### Added

- Added an installable Codex plugin and Git-based plugin marketplace metadata.
- Added English and Traditional Chinese UI localization.
- Added scheduled H2 backups with independent retention buckets for startup,
  shutdown, and scheduled snapshots.
- Added bounded, asynchronous SSE delivery with per-client queues and a connection
  limit.
- Added dependency and task-detail read APIs.
- Added cross-platform release packages: JAR, macOS archive, Linux archive, and a
  Windows x64 ZIP with a bundled Java 21 runtime.
- Added explicit, rollback-safe stable-release updates and platform smoke tests.

### Changed

- Upgraded to Spring Boot 4.1 and Spring AI 2.0.
- Consolidated macOS/Linux service commands under `bin/board`.
- Hardened readiness checks, release permissions, and local-only defaults.

### Fixed

- Prevented slow SSE clients from blocking MCP write calls.
- Aligned QA role instructions with the worker tool allowlist.
- Fixed cross-platform installer, launcher, checksum, and rollback edge cases.

## [3.1.0] — 2026-08-06

### Added

- Added optional desktop notifications for BLOCKED tasks.
- Added task titles to status-change events.
- Added build version and commit information to health and diagnostics responses.

### Fixed

- Closed SSE connections during graceful shutdown.
- Sourced the MCP server version from the build instead of static configuration.
- Fixed Windows PowerShell 5.1 Java detection, stop handling, and UTF-8 log output.

## [3.0.0] — 2026-08-05

This version was not tagged and has no release assets.

### Added

- Added cross-platform service, backup, and restore scripts.
- Added first-run onboarding, CI, release automation, and security documentation.
- Added loopback Host/Origin protection and offline frontend assets.

### Fixed

- Fixed backup retention ordering, Java detection with `JAVA_TOOL_OPTIONS`, paths
  containing spaces, and terminal hangup handling.

[Unreleased]: https://github.com/hpigu/AIProjectDashboard/compare/v3.2.1...HEAD
[3.2.1]: https://github.com/hpigu/AIProjectDashboard/compare/v3.2.0...v3.2.1
[3.2.0]: https://github.com/hpigu/AIProjectDashboard/compare/v3.1.0...v3.2.0
[3.1.0]: https://github.com/hpigu/AIProjectDashboard/releases/tag/v3.1.0
[3.0.0]: https://github.com/hpigu/AIProjectDashboard/pull/1
