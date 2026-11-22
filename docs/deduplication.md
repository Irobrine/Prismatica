# Configurable duplicate-song hiding

Everything lives in `app/src/main/java/com/prism/music/data/dedup/`, with thin hooks in the existing screens.
Hiding is **off by default**; when off, every list, queue and player path is the code that shipped before.

## How detection works

`DedupFingerprinter` decodes an on-phone file with the existing `AudioScratch` / `PcmDecoder` / `Resampler` chain to
mono 16 kHz, then uses the app's existing offline `ShazamSignature` generator. `Landmarks` turns its spectral peaks into
compact (frequency pair, time gap, start time) words; `AudioFingerprint` persists them. `Matcher` votes on the time
offset between shared landmarks — the alignment with the most votes is the match. That makes it start-independent, so
a music video's intro/outro or dialogue only removes landmarks rather than shifting them, while a live take, a remix,
a cover or a different master shares too few landmarks to qualify.

Thresholds are in `Matcher` and are deliberately **not** an assumed 80%: a pair needs both a minimum number of
independent votes and a minimum fraction of the smaller fingerprint. Votes in between are *suggestions*, shown for a
person to confirm; below that a pair is unrelated.

`Grouping` only joins two components automatically when every cross pair has a direct high-confidence (or manual)
statement, which stops a weak A~B, B~C chain from swallowing unrelated tracks. Every statement keeps its provenance
(`AUTO` / `CONFIRMED` / `MANUAL` / `SEPARATED`); a separation overrides everything, a manual statement overrides the
thresholds. An existing "Play a different version" pick (`SongVersions`) is treated as a manual statement too.

## Choices and persistence

`Representative` ranks: a pinned version, an existing manual version preference, audio over video, then format /
bitrate / sample rate / bit depth, with video as a last resort. A pin is a preference: if the pinned version is not in
a collection (or its file is gone) the next best version plays, and the pin comes back when it is available again.
MediaStore bit depth is not exposed by Android, so that component is 0 until a file-header reader is added.

State is in its own Room database `prism_dedup.db` (`DedupDatabase`), so the library database's migration chain and
exported schemas are untouched. Fingerprints cache file size/mtime; changed files are marked stale and reprocessed,
new files stay visible with an "index out of date" hint, and failed files stay visible and unindexed. Only AUTO rows
are replaced by a rescan — confirmations, manual edits, separations, pins and exclusions persist. The database is
included in the `HISTORY` backup part.

## Scanning and UI

`DedupScanner` is manual-only (nothing fingerprints at startup or when the toggle flips), has progress/running/stop
state, and continues out of sight under `DedupScanService` — the same foreground `dataSync` service and wake-lock
pattern as the song-measuring walk. Local files and finished downloads are the inputs.

Collections show one expandable row per group with a tinted representative, a version count, and alternatives that
can be confirmed, separated, merged, moved, pinned or excluded; a Compare action plays two suggested versions. Each
of the five scopes (Library Songs, Library Albums, Playlists, Local files, Downloads) has a quick toggle in its list
controls and a detailed On / Off / Inherit setting in Settings → Home & Library → Duplicate songs. Collection queues
carry one version per group; tapping a specific version still plays that exact file, and if a playing version becomes
hidden the player swaps in the representative at the same position.

## Known limitations

- Bit depth is not available from MediaStore, so quality ranking uses format, bitrate and sample rate only.
- Comparing every cached pair is quadratic; the scan is a deliberate background operation with no work at startup.
- MediaStore ids are not durable across a reinstall; file keys are content URIs plus size/mtime, and pairwise
  relationships use stable song/form ids.
