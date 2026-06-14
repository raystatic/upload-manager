# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-06-14

First public pre-release. Feature-complete; API may still change before 1.0.

### Added
- Durable, resumable uploads: Room-backed queue, atomic session-URI persistence,
  resume from the last confirmed byte across process death and reboots.
- WorkManager scheduling with priority tiers (expedited P0 → charging-gated P4).
- Two-tier retry: WorkManager fast backoff → parked tier (connectivity/charging/
  daily-sweep re-dispatch) → 7-day TTL.
- `pause` / `resume` / `cancel` / `retry`, and a `Flow` of progress + lifecycle
  events (`observe`, `observeAll`).
- Source-file staging (snapshot + in-pass SHA-256) with restart-on-change for
  non-staged sources; `SOURCE_GONE` for deleted sources.
- Per-uid content-hash deduplication (content-addressed paths, `DEDUP_HIT`),
  degrading gracefully when Firestore is unavailable.
- Opt-in Firestore mirroring via `SyncPolicy` (off by default).
- Adaptive concurrency (battery/thermal/network aware) with a resizable gate.
- Optional `UploadMetrics` observability sink.
- Jetpack Compose sample app with a built-in CUJ runner, and an instrumented
  test suite that runs on an emulator against the Firebase Emulator Suite.

[Unreleased]: https://github.com/raystatic/upload-manager/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/raystatic/upload-manager/releases/tag/v0.1.0
