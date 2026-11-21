# Sleep suitability scores (1.9)

A song's **sleep suitability** is a 0–100 figure, higher meaning less likely to disturb someone falling asleep. It is
measured from the recording's own audio — never its genre, title, artist or anything online — and stored per recording
(the form that actually plays: a song, its music video, the version picked for it, or a file on the phone). The sleep
timer can then pass over songs below a chosen minimum, and over songs without a figure.

The design rule everywhere is **precision over recall**: a disruptive song scored high is the expensive mistake, so a
single bad section, a slam, a clipped peak or a rise into a loud passage caps the whole song even when the rest is calm.
A genuinely calm song scoring low is accepted as the price.

## Where the code is

| Piece | File |
| --- | --- |
| Feature extraction and the formula | `app/src/main/java/com/prism/music/data/sleep/SleepAnalysis.kt` |
| The stored figure, its parts and warnings | `data/sleep/SleepScore.kt` |
| The store (JSON per recording, version gate) | `data/sleep/SleepScores.kt` |
| Reading a recording's audio and analysing it | `data/sleep/SleepScanner.kt` |
| The walk over a set of songs | `data/MeasureSongs.kt` (shared with "Measure songs now") |
| Sleep timer rule and gate | `playback/SleepTimer.kt` (`SleepSuitability`, `SleepTimerEngine.blocksScore`) |
| Filtering playback and the queue walk | `playback/PlayerConnection.kt`, `playback/PlaybackService.kt` |
| UI | `ui/player/PlayerSheets.kt`, `ui/components/Selection.kt`, `ui/screens/settings/PlaybackPage.kt` |
| Keeping the walk alive out of sight | `playback/SleepScoreService.kt` |

## How a recording is judged

The decoded mono audio is read in ~46 ms Hann-windowed frames, at the decoder's own rate (44.1/48 kHz as a rule), as
it is decoded:
the samples never all sit in memory, only the per-frame measurements do. Each frame gives its level, its peak, spectral
centroid, spectral flatness, spectral flux, and the share of energy below 250 Hz and above 4 kHz. From those:

* **Sections.** Ten seconds each, so a calm intro cannot speak for a chorus. Every section is scored on its own.
* **Transients.** Onsets where the spectrum jumps *and* the level stands well above the preceding three seconds: kicks,
  slams, hits. Their density per minute and their strongest rise are both used.
* **Sudden events.** Runs at least 0.15 s long that are 8 dB above the passage before them; 12 dB is severe.
* **Crescendos.** A section whose level climbs within it (or rose into it) by 5–8 dB, with the spectral activity that
  goes with a build.
* **Abrupt changes.** Adjacent sections more than 8 dB apart (13 dB severe).
* **Clipping.** The share of samples at full scale (0.2% and 2% thresholds).
* **Rhythm.** Onset density, beat-strength from the onset envelope's autocorrelation at 30–200 BPM, and bass share.
* **Mood.** How dark and tonal the spectrum is: brightness (centroid), flatness (noisiness) and top-end share, as a
  proxy for gentle versus tense/dramatic.

### Components (0–100, higher is more suitable)

| Component | Weight | What lowers it |
| --- | --- | --- |
| `quietness` | 0.16 | The loudest sections' level (−32 dBFS scores 100, −10 dBFS 0) |
| `dynamics` | 0.20 | Level spread between sections, sudden events, crescendos, within-section pumping |
| `transients` | 0.22 | Strong hits per minute, the strongest hit's rise, peak-to-level (crest) |
| `rhythm` | 0.14 | Onsets per second, beat strength, bass share |
| `complexity` | 0.16 | Spectral flux, flatness, structural changes |
| `mood` | 0.12 | Brightness, flatness, top-end share |
| `model` | 0.20 of the end figure | A fixed-weight linear model over the same measurements, squashed to 0–100 |

The hand-written components are averaged 80/20 with the model. Every component below 55 then costs its own weight in
points, so a song can't be rescued by its quiet moments. Then:

1. `score = min(score, worstSection + 30)` — the worst ten seconds set a ceiling.
2. `score -= 0.45 × max(0, 60 − worstSection)`.
3. A cap by absolute level, before any event cap: the loudest sections at ≥ −8 dBFS → 35, ≥ −11 → 45, ≥ −14 → 58,
   ≥ −17 → 70. A steady loud wall is still too loud to fall asleep to.
4. A recording shorter than 30 s is capped at 55.
5. Hard caps: sudden event ≥ 12 dB → 45, ≥ 9 dB → 62; crescendo severe → 52, disruptive → 66; clipping → 45/60; loud
   passage (≥ −10 dBFS median) → 45/60; a section whose own score is under 15 → 55.
6. `score -= 0.12 × (100 − confidence)` (so a short clip lands under its 55 cap).

`confidence` is kept separate: it falls for short audio (below 60 s), a decode that stopped short of the known duration,
mostly silence, and a song with fewer than three sections. Silence, or a decode that produces no audible frames at all,
scores 0 with confidence 5 and an `UNSTABLE` warning: unreadable audio is never a lullaby.

### Warnings

`SleepWarning(kind, startMs, endMs, severity)` is stored with each figure: `LOUD`, `SUDDEN_LOUD`, `CRESCENDO`,
`ABRUPT_CHANGE`, `ENERGETIC`, `CLIPPING`, `UNSTABLE`, severity 1–3. They are what the score's caps are built from and
what the UI can point at.

### Versioning and recalculation

`SleepAnalysis.MODEL_VERSION` is stored in every figure. A figure from an older version is treated as missing: the
filter passes the song over (never trusting a stale number) and the next calculation rewrites it. Detection is
deterministic: the same samples, rate, version and timestamp produce the same figure, and the analysis does not depend
on how the samples were fed (tested with odd chunk sizes). A recording's audio is identified by its form id, so a picked
version, a music video or a file is scored as itself, not as the song's stream.

## Calibration

There is no public, permissively licensed dataset of listener sleep-suitability votes, so v1 is tuned against a
reference set of signals with known character, tested in `app/src/test/java/com/prism/music/SleepSuitabilityTest.kt`:

| Reference signal | Label | Result |
| --- | --- | --- |
| Steady quiet pad (three low tones, breathing, air) | suitable | 93 |
| Steady ambient hum, no onsets | suitable | 95 |
| Quiet with a loud hit every half second | not suitable | 59 |
| Loud, compressed four-to-the-floor beat | not suitable | 0 |
| Calm intro, then a loud chorus | not suitable | 0 |
| Forty-second crescendo into a loud passage | not suitable | 35 |
| Calm pad with one sudden slam | not suitable | 45 |
| Loud, steady three-tone wall (nothing sudden) | not suitable | 45 |
| 20-second clip of the quiet pad | uncertain | 50, confidence 60 |
| Digital silence | unjudgeable | 0, confidence 5 |

The test asserts the order and the thresholds (every "not suitable" at or below 60, every "suitable" at or above 60),
so a retune that lets a disruptive signal through fails the suite. The scale is deliberately shifted down: pop music
typically lands in the 30s–50s, ambient and sparse instrumental music in the 70s–90s. A minimum of 60 is a reasonable
starting filter; the number is the listener's to choose.

When real listener judgements exist, they replace this table: the same features can be fitted directly (the model
component's weights are the place), and the version bump recalculates every figure without touching the stored shape.

## Audio models investigated

The brief asked to combine DSP with audio-model predictions and to investigate CLAP2, EfficientAT and Essentia, checking
the *weights'* licences, not only the repositories'.

* **CLAP2 (LAION-AI/clap2).** Code MIT, models Apache-2.0 (README). Five sizes, 27M–340M parameters. The reference
  implementation is PyTorch/open_clip; there is no Android build, and the smallest audio encoder is still a transformer
  over a log-mel spectrogram — tens of millions of parameters and hundreds of MB of activations at fp32, before the text
  tower needed for zero-shot prompting. Viable only with an int8 ONNX export, a matching mel front-end and a few hundred
  ms to seconds per song on a mid-range phone. Not adopted for v1: the licensing is fine, but the size/latency and the
  conversion work are a project of their own, and its similarity scores are not calibrated probabilities.
* **EfficientAT (fschmid56/EfficientAT).** Code and released checkpoints MIT. Attractive on paper: `mn04_as` is 0.98M
  parameters and 0.11 G-MACs per 10 s (47.7 mAP for `dymn10_as`), i.e. well inside a phone's budget. But it is an
  AudioSet *tagging* model: its classes are acoustic events (speech, drums, music, applause), not sleep suitability, and
  it ships PyTorch checkpoints, not ONNX. It is the best candidate for a v2 model behind the `model` component, using
  its probabilities for speech/percussion/energy mapped through a tunable rule.
* **Essentia (MTG/essentia).** The library is **AGPL-3.0**, and its most-used pretrained models (MusiCNN/EffNet
  embeddings, classifiers) are released under non-commercial or otherwise restrictive terms. AGPL is incompatible with
  shipping Prism under its current licence without making the whole app AGPL, and the model licences fail the
  "permissive weights" requirement outright. Rejected.

**What ships.** A deterministic, bundled model: a fixed-weight linear scorer over the features above (its own 20% of the
figure). It needs no download, no inference runtime, no extra permissions and no licence obligations beyond the app's
own, and it runs in the same pass as the DSP. Its weights are clearly separated (`MODEL_CENTER`, `MODEL_SCALE`,
`MODEL_WEIGHTS` in `SleepAnalysis.kt`) so a trained model can replace them without changing the stored format.

**Benchmark.** `SleepAnalysisDeviceTest` (instrumentation) analyses three minutes of audio on the phone and
prints the time and heap cost; run it with
`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prism.music.SleepAnalysisDeviceTest`
(the app's in-app tests are the other `androidTest` classes). The pass is comfortably faster than real time — three
minutes of audio take a small fraction of the three minutes they play on the JVM (`SleepSuitabilityTest` asserts this
too; 147 ms for three minutes at 22.05 kHz on the development machine, about 0.08% of real time) — and the heap cost is the per-frame measurements (under a megabyte for a ten-minute song), not the audio.
Decoding dominates a real song's cost, and it is the same decode "Measure songs now" already pays.

## What v1 does not see

Honest limits, so the figure is not asked to do more than it can: there is no speech or vocal detector in v1 (a quiet
song with a spoken-word passage is judged by its levels and spectrum, not by recognising a voice); lyrics and their
meaning are not read; an intentionally quiet master of a loud, busy mix is judged by what its spectrum does, not by how
it was mastered; and only the first fifteen minutes of a very long recording are read (the confidence says so). These
are exactly the gaps a permissively licensed audio tagger would fill behind the model component, which is why its
weights live in one place.

## Using it

* **Settings → Playback → Sleep scores** (in "Stopping and pausing", beside the sleep timer's fade) picks a set
  (library, downloads, on this phone, liked) and walks it;
  progress, Stop and resume are the same as "Measure songs now", with a silent foreground notification while the screen
  is off.
* **Select many → Sleep scores** does the same for the picked songs.
* **Sleep timer → Minimum sleep score** (0 = off) draws the line. Songs below it, and songs with no figure yet, are
  passed over exactly as the BPM limit passes songs over: play order, no wrapping, and when nothing fits the music stops
  and says why. Nothing is ever removed from the queue, so edits made while the filter runs survive turning it off, and
  there is no snapshot to restore over them. The menu counts the two cases separately ("12 in the queue have no score
  yet", "8 are below this"), so an unmeasured song never looks like an unsuitable one.

The score is a heuristic, not a probability, and the app never presents it as one.
