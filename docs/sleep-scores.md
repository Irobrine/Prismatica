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

### Features and the score (1.9.2)

The first formula stacked six penalties and five hard caps on the same evidence, which collapsed ordinary mastered music
to 0. The current model is one bounded logistic:

```
score = 10 + 85 * sigmoid(bias - 1.6 * SUM(weight[i] * feature[i]) + 0.01 * (confidence - 100))
```

Each feature is a measured 0–1 disturbance value, and each is charged **once**. The floor of 10 and ceiling of 95 keep
the score useful for ranking; precision comes from where the listener sets the filter, not from crushing scores to zero.

| Feature (0–1, higher = more disturbing) | Default weight | From |
| --- | --- | --- |
| `level` | 0.25 | Absolute level, **zero in normalized mode** (Normalize makes it equal for every recording) |
| `peaks` | 0.90 | Crest factor and true-peak headroom |
| `transients` | 1.60 | Strong hits per minute and how far they rise |
| `onsets` | 1.05 | Onset density and beat strength |
| `activity` | 0.85 | Spectral flux and flatness |
| `changes` | 1.10 | Abrupt changes, sudden events, level spread |
| `brightness` | 0.40 | Spectral centroid and high-frequency share |
| `worstSection` | 1.10 | The single most disturbing ten-second section |

Volume is deliberately one of the smallest terms, and disappears entirely in normalized mode: what tends to wake a
listener is a hit, a build or a busy section, not the integrated level of a Normalize-played recording.
The unit tests check a library-like set of twelve signals: the figures spread from 24 to 74 with a median near 53, rather
than collapsing to 0; a deliberately loud, percussive beat scores 11, a calm pad 70, and a single severe slam in a calm
song 36. A 12 dB change in volume alone moves the figure by less than a point.

Two more safeguards remain: silence (or a decode with no audible frames) has no evidence and scores 0 with confidence 5
and an `UNSTABLE` warning; and `confidence` — short audio, a partial decode, mostly silence, few sections — is kept out
of the features and only nudges the logit slightly. The old per-component figures are still stored as diagnostics
(higher being more suitable), and `model` in them is the final score.

**Calibration.** The default weights are transparent, not guesses presented as truths. The scores menu lets the listener
mark each song *Suitable for sleep*, *Borderline* or *Unsuitable*; once there are at least 8 labels with two of each end,
a deterministic logistic fit runs over the stored features (no re-decoding), weights stay non-negative so more
disturbance can never raise a score, and the library is recalculated with the fitted model. The figure records how many
judgments were used. A label is per song, and labels and weights travel in backups with the scores.

### Warnings

`SleepWarning(kind, startMs, endMs, severity)` is stored with each figure: `LOUD`, `SUDDEN_LOUD`, `CRESCENDO`,
`ABRUPT_CHANGE`, `ENERGETIC`, `CLIPPING`, `UNSTABLE`, severity 1–3. They are what the score's caps are built from and
what the UI can point at.

### Versioning and recalculation

`SleepAnalysis.MODEL_VERSION` is stored in every figure. A figure from an older version is treated as missing: the
filter passes the song over (never trusting a stale number) and the next calculation rewrites it. Detection is
deterministic: the same samples, rate, gain, version and timestamp produce the same figure, and the analysis does not
depend on how the samples were fed (tested with odd chunk sizes). A recording's audio is identified by its form id, so a
picked version, a music video or a file is scored as itself, not as the song's stream; and each audio mode keeps its
own figure, so switching modes never throws away the other's work.

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
(the app's in-app tests are the other `androidTest` classes). Three separate tests cover the older 22.05 kHz signal, a
real 44.1 kHz-rate analysis with the feed and scoring stages timed apart, and a whole WAV decoded and scored end to end.
The pass is comfortably faster than real time (`SleepSuitabilityTest` asserts this on the JVM too: 107 ms for three
minutes at 22.05 kHz, about 0.06% of real time).

Measured on the development phone (vivo V2413, Android 16, debug build):

| Signal | Before the speed work | Now |
| --- | --- | --- |
| 3 min at 22.05 kHz, analysis only | 1032 ms (174× real time), heap +46 MB | **960 ms (187× real time), heap +10 MB** |
| 3 min at 44.1 kHz | — | **1933 ms frames/FFT + 20 ms scoring = 1953 ms (92× real time)** |
| 60 s WAV, decoded and scored end to end | 779 ms | **499 ms** |

The scoring itself is only ~1% of a 44.1 kHz pass; the frame loop and its FFT are the cost. The lower heap comes from
sizing the frame arrays from the file's known duration and from computing the little medians without an array per frame.
The numbers are printed by the test, so a change that makes scoring a library take hours shows up as a failing benchmark.

**Speed work (1.9.1).** Four changes made the analysis faster without changing what it measures, so scoring stays
deterministic for a given version (`MODEL_VERSION` was bumped to 3 for the changed model):

1. **A real-input FFT** gets the same N-point magnitudes from one N/2-point complex FFT (`RealSpectrum.kt`);
   `RealSpectrumTest` checks them bin for bin against the old N-point transform.
2. **Decode and analysis overlap**: the codec fills a small bounded queue on the IO pool while the FFT runs on the CPU
   pool (`SleepScanner.decode`). Pieces stay in order and each frame's arithmetic is untouched.
3. **One pass, both figures**: in normalized mode the loudness/peak are made sure of first, then one decode and one set
   of spectra produce both the plain and the normalized figure (`SleepAnalysis.analyzeBoth`); a mode toggle needs no
   second walk. The gain is only a shift of the measured levels, so everything computed from ratios is shared.
4. **Two recordings at a time** on phones that can spare the cores (the same gate as the measurement walk, and never
   while that walk is using them). Each scan owns its buffers, so the work is independent and the progress still counts
   whole songs.

What remains is mostly the per-frame spectral loop (a magnitude, log and band sum for every FFT bin) and the codec;
approximating either would change the measurements, so that is not done. Loading the stored figures is warmed off the
playback thread at startup, and the settings row and the timer's queue counts follow the active mode's own count.

## What v1 does not see

Honest limits, so the figure is not asked to do more than it can: there is no speech or vocal detector in v1 (a quiet
song with a spoken-word passage is judged by its levels and spectrum, not by recognising a voice); lyrics and their
meaning are not read; an intentionally quiet master of a loud, busy mix is judged by what its spectrum does, not by how
it was mastered; normalized mode applies the gain but not the runtime limiter's transient taming, so hits stay as
prominent as in the raw sound (conservative, never the other way); and only the first fifteen minutes of a very long
recording are read (the confidence says so). These are exactly the gaps a permissively licensed audio tagger would fill
behind the model component, which is why its weights live in one place.

## Using it

* **Settings → Playback → Sleep scores** (in the Sleep group, above the audio-mode toggle) opens the songs' scores:
  a searchable list with each song's figure in the active mode. Songs without a figure say "Not calculated" instead of
  showing a misleading zero, and the three icons on a row are your judgments — mark a handful you know and the model is
  fitted to your ears. Tapping the row opens this list even while a walk is running; the list's **Calculate** button
  opens the set picker (library, downloads, on this phone, liked), and **Stop** / **Continue** control a walk in flight
  there, with its progress and a silent foreground notification as in "Measure songs now".
* **Select many → Sleep scores** starts the same walk for the picked songs.
* **Settings → Playback → Sleep → Use normalized audio** scores the sound as Normalize plays it: the recording's
  already-measured loudness and peak give exactly the gain playback's Normalize applies (its target, lift room and cut),
  and the analysis shifts its measured levels by that gain. One decode and one set of spectra produce both figures, so
  the plain one is already there if the mode is turned off. Every ratio — dynamics, hits, spectral change, brightness —
  is unchanged by a gain, so only the absolute-loudness verdict moves, which is precisely what normalization changes. A
  recording not measured yet is measured first (the same scan Normalize uses). Plain and normalized figures are stored
  separately for each recording: while the mode is on only normalized figures count, and they override the plain ones
  without destroying them; while it is off only plain figures count, so the row's "N scored", the sets a walk has left
  and the timer's queue counts all follow the mode, a running walk stops and starts over with the new mode's missing
  songs, and what is playing is rechecked at once. Changing the Normalize target changes the gain, so normalized figures
  are dropped and calculated again for the new target (their count and a running walk follow, as with the mode). The
  toggle is an ordinary setting: it travels in backups and "Reset this page" puts it back off.
* **Sleep timer → Minimum sleep score** (0 = off) draws the line. Songs below it, and songs with no figure yet, are
  passed over exactly as the BPM limit passes songs over: play order, no wrapping, and when nothing fits the music stops
  and says why. Nothing is ever removed from the queue, so edits made while the filter runs survive turning it off, and
  there is no snapshot to restore over them; turning the timer off also resumes the pause either filter caused, while a
  pause the listener made (or the deadline's) stays. The menu counts the two cases separately ("12 in the queue have no
  score yet", "8 are below this"), so an unmeasured song never looks like an unsuitable one.

The score is a heuristic, not a probability, and the app never presents it as one.
