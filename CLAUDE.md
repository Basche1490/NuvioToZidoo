# Notes for Claude

Nuvio To Zidoo is a standalone Android TV app (package `io.github.basche1490.nuviotozidoo`)
that Nuvio opens as its *external player*. It forwards playback to the Zidoo player
and reports back to Nuvio like MX Player. Nuvio itself is never modified. The owner
(Joel) is not an Android developer: explain changes in plain language.

## Rules

- Push only to this repo's `main`. Never push to or open PRs on NuvioMedia/NuvioTV
  unless Joel asks.
- Keep `signing/nuviotozidoo.keystore` and the application ID unchanged, or new APKs
  won't install over old ones.
- Pushing to `main` builds and publishes a release (`.github/workflows/build.yml`).
  Failed builds push their log tail to the `ci-logs` branch
  (`git fetch origin ci-logs && git show origin/ci-logs:last-failure.log`).
  A successful build creates a `v1.0.<run>` tag (`git ls-remote --tags origin`).

## The contract with Nuvio

Source of truth: NuvioTV `app/src/main/java/com/nuvio/tv/core/player/ExternalPlayerResultContract.kt`
and `ExternalPlaybackTracker.kt`. `scripts/check-nuvio.sh <NuvioTV checkout>` checks the
parts this app relies on; `.github/workflows/nuvio-check.yml` runs it weekly.

- In: `ACTION_VIEW` + stream URL, `title` ("Show - S01E05" for episodes, the name for
  films), `position` (Int ms) / `extra_position` (Long ms), `from_start`, `headers`
  (String[] "Name: value"), `skip_segments` (JSON `[{type,start,end}]`, seconds).
- Out: `position`/`duration` (Int ms), `extra_position`/`extra_duration` (Long ms),
  `end_by` = `playback_completion` (finished → watched + auto-next) or `user`.
- Nuvio's `ZidooPlayerMonitor` also polls the Zidoo API on boxes it detects as Zidoo
  (`ZidooPlayerLauncher.nuvioSeesZidoo()` mirrors its test). When it decides playback
  ended (after a pause, or first at the real end) it stops tracking and drops this
  app's later result. The "Wait for Nuvio before playing" setting avoids that by
  starting the Zidoo player after Nuvio's monitor gives up (~13 s).

## Code map

- `PlayActivity` — the external player: resume decision, countdown, launch, monitoring,
  credits → Up Next, result to Nuvio.
- `ZidooMonitor.kt` (`ZidooPlaybackMonitor`, `ResumeGuard`), `SkipPlanner.kt`,
  `PlaybackRules.kt` (resume, Up Next and result rules), `ZidooApi.kt`,
  `HeaderRelay.kt` — plain Kotlin with JVM unit tests in `app/src/test`.
- `ZidooPlayerLauncher` — the Zidoo player intent (as PlexToZidoo builds it).
- `SettingsActivity`, `Settings`, `ResumeStore`, `MonitorService` (keep-alive).

## Testing

`./gradlew :app:testDebugUnitTest :app:assembleRelease` (CI does this). Nothing here
can be tested against a real Zidoo from a cloud session; say what Joel should check
on the box.
