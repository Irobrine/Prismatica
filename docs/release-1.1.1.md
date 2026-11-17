# Prismatica 1.1.1

`versionName` **1.1.1**, `versionCode` **1003**.

This release finishes two fixes: backups made by the branch this line was refactored from (1.8.5-archaic) restore again, and Shuffle starts playback without waiting on a large library's whole queue.

## Backups from 1.8.5-archaic and older

### What was wrong

A backup made by 1.7.8–1.8.6 (including 1.8.5-archaic) with "Settings" ticked carried the settings store's own file at `files/datastore/prism_settings.preferences_pb`. That file also holds the YouTube Music sign-in. The restoration path made assumptions that only held for what this line wrote since 1.0.0 (and for staging produced by its own `restore`):

1. `applyPending` treated every staged file it would not restore as proof that the file was not a Prism backup, and dropped the **entire** restore. A staged restore that still held the settings store (for example one staged by an older version, or one left when an update came between the restore and the next launch), `shared_prefs/lastfm.xml`, `shared_prefs/crashlog.xml`, crash logs or migration marks therefore never applied.
2. For a whole (pre-1.7.8) backup, `oldPaths` moved aside this phone's Last.fm session, crash log, diagnostics and settings store even when the backup had no replacement for them, then deleted them at the end of the apply.

The `crashlog` preference was also classified as Settings; it is this phone's crash log, not a setting, and is now excluded from backups. The `lastfm` preference (a session key sealed to the phone that made it) is excluded too, and a restored phone reconnects to Last.fm instead.

### What changed

- `Backup.applyPending` skips files this version deliberately never restores (settings store, crash/diagnostic logs, migration marks, caches) and only refuses the restore for an entry it cannot classify. Unknown entries are still refused whole.
- A non-whole (part) backup never brings the settings store's own file; this is deleted from the staging before the checks, matching the 1.8.7 security fix (#498).
- A whole backup may still bring the store (the only form settings had before 1.7.8), and only moves this phone's store aside when the backup actually contains one.
- `oldPaths` skips never-travel preferences and `files/` entries, so nothing the backup will not restore is deleted.
- The restore pipeline is split at the restart (`Backup.restore` → `Backup.stage` + restart) so tests can inspect and apply the staged result.
- A password-protected backup is now sealed a chunk at a time (1 MB per GCM chunk). Android's `CipherOutputStream` otherwise keeps the whole encrypted file in memory; a library's backup could fail to write on a phone with the storage but not the memory. Files sealed the old way (one GCM seal over the whole zip) still open.

### Compatibility and data preservation

- Database schema 11 (1.8.5-archaic) opens through 11 → 12 → 13; songs, likes and play history are kept. The actual migrations are exercised by the fixture.
- The 1.8.5 library file (`files/library/prism.json`), tags (`files/library/tags.json`), settings values (`settings.json`), shared preferences and the database are restored. The backup maker's sign-in is never taken from the store file.
- Current-version backups (format 2, with the integrity list) still restore.
- A cut-short, altered or foreign file is refused whole and leaves the phone's data untouched.

### Fixture limitation

No listener's own 1.8.5-archaic backup was available in the repository or the build environment. `LegacyBackupTest` builds a faithful fixture from the historical branch (`Prismatica-1.8.5-archaic` / tag `v1.8.5-prismatica.2`): its schema 11 exported by Room, its manifest layout (format 2, no integrity list), its settings as tagged values, its prefs and the files 1.8.5 backed up. If a real backup becomes available, it should be added as a test resource without changing the assertions.

## Shuffle with a large library

### What was wrong

Library → Songs → Shuffle called `PlayerConnection.playQueue`, which built **every** song's `MediaItem` (song JSON, artwork URI, version text) on the main thread before `setMediaItems`. With 800 songs that is a visible wait before the first sound; with larger libraries it grows linearly. The fast-start window that existed for long lists was disabled for shuffled queues.

### What changed

- A shuffled list larger than 400 songs now starts on the random song and the next four; those items are built on the calling thread, playback starts, and the rest is built off the main thread and scattered behind the current song in the shuffle order.
- The window is shuffled with `HistoryShuffleOrder` so the chosen song stays first and every later song is in a fresh random order. The rest is added in batches of 400, and each batch is scattered after the current song (not after songs already heard).
- Rapid shuffles cancel the previous fill; a token check before each batch keeps an older fill from adding its songs to a newer queue.
- Smart Shuffle's picks and Autoplay wait while a queue is filling (`QueueState.filling`), then run once the whole queue is there.
- `HistoryShuffleOrder.scattered` now interleaves a batch in one O(queue) pass instead of shifting the list once per added song, so very large libraries don't pay O(songs × batch).
- A small library (≤ 400) still builds its queue wholly as before, preserving the exact previous behavior.

### Measurements

`ShuffleStartTest` drives a real ExoPlayer under Robolectric and records:
- 800 songs: **5 items built in ~0 ms** before playback; the tap returns in ~2 ms; the full 800 arrive behind it.
- 5,000 songs: **5 items built**, the tap returns in ~4 ms.
- For comparison, building the whole 800-song list takes ~3.5–5 ms on the test JVM. On a phone the difference is larger (the JVM test is warm and fast); the important invariant is the constant 5-item window, which the test asserts.

The same test checks the final play order is a permutation of all songs, the chosen song is first, small lists are unchanged, shuffle while playing and six rapid taps keep only the last queue, and the queue never contains duplicates.

`PlayerConnection.lastQueueStart` exposes the last start's song count, items built before play and elapsed milliseconds for future regression checks. When playback diagnostics are on, the same line is written to the diagnostics log.

## Verification

Run with JDK 21 (Robolectric 4.14.1 cannot instrument JDK 25 class files):

```
gradlew.bat testDebugUnitTest --tests com.prism.music.LegacyBackupTest --tests com.prism.music.ShuffleStartTest --tests com.prism.music.BackupPartsTest --tests com.prism.music.BackupCryptoTest --tests com.prism.music.MigrationTest --tests com.prism.music.HistoryShuffleOrderTest --tests com.prism.music.VideoPreviousTest --tests com.prism.music.DjShuffleTest
```

That set is green. The complete unit suite also ran: **2,571 tests, 11 skipped**. Three unrelated tests fail on this branch/environment and fail the same way when run alone, without these changes' tests:

- `AppSettingsSizeTest` — `AppSettings`' constructors are already at 246 JVM slots (limit 245); no setting was added here.
- `BlendExchangeTest.theScannersCodeComesBack` — the loopback listener's port isn't published in this environment.
- `SettingsKitAdoptionTest.drawnLibraryViewSheet` — the library-view sheet's "View" row isn't found; unrelated to backup/playback.

Everything touching backups, migrations, shuffle, queues, playback and video previous/next passes.

Then `assembleRelease` and `bundleRelease`, and `lintVitalRelease` (green).

## Release metadata

- `app/build.gradle.kts`: `versionCode = 1003`, `versionName = "1.1.1"`.
- Changelog: `CHANGELOG.md`, section 1.1.1.
- Artifacts: `app/build/outputs/apk/release/app-release.apk` (31,644,573 bytes) and `app/build/outputs/bundle/release/app-release.aab` (29,431,029 bytes), signed with the key from `keystore.properties`.
- Verified: `aapt dump badging` reports `package: name='com.prismatica.music' versionCode='1003' versionName='1.1.1'`; `app/build/outputs/apk/release/output-metadata.json` and the merged release manifest report the same; the AAB is built from the same release variant.
