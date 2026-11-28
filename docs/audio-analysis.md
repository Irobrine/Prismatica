# Sound analysis: sleep score, tags and audio-aware autoplay

How Prism reads a song's sound on the phone, what it does with it, and why it is built this way. Everything here runs
offline on the phone; nothing about a song or a listener leaves it.

## 1. Research and choices

### Libraries, models and runtimes considered

| Candidate | What it offers | Android | Size | Licence | Verdict |
| --- | --- | --- | --- | --- | --- |
| **Essentia** (C++) | RhythmExtractor2013, KeyExtractor, LoudnessEBUR128, DynamicComplexity, MFCC, Danceability, MusicExtractor | NDK build possible but brings FFTW/Eigen/TagLib (and TensorFlow for its models); no maintained AAR | several MB native per ABI | **AGPL-3.0** | Not used as a library. Its algorithms are the reference for Prism's own Kotlin descriptors (below). |
| **Essentia models** (discogs-effnet, MTG-Jamendo heads, msd-musicnn) | genre/style (400 Discogs styles), mood/theme, instruments | effnet has an ONNX build (18 MB); heads are TF `.pb` | 18 MB + heads | **CC BY-NC-SA 4.0** | Rejected: the NonCommercial term can't be shipped in a GPL app. |
| **MERT** (95M / 330M) | strong music embeddings | PyTorch only | 380 MB+ | **CC BY-NC 4.0** | Rejected: licence and size. |
| **LAION-CLAP** (HTSAT, Xenova ONNX) | zero-shot text↔audio tags, 512-d embedding | ONNX quantized | 34 MB audio tower (+127 MB text tower) | Apache-2.0 / CC0 | Kept as the candidate for a later "describe in words" model; ~100× YAMNet's compute per second. |
| **AST** (onnx-community) | AudioSet mAP 0.459 | ONNX | 55–90 MB | BSD-3 | Too heavy for a background job per song. |
| **PANNs** CNN14 / MobileNet | AudioSet 527 classes, 2048-d embedding | CNN14 ONNX is 327 MB; MobileNets only `.pth` | 20–330 MB | MIT | CNN14 too big; MobileNets need our own conversion and hosting. |
| **EfficientAT** mn04/mn10 | AudioSet mAP 0.43–0.47 at 1–5 M params | PyTorch only (convert ourselves) | 4–20 MB | MIT | Best accuracy per MB; the natural upgrade once Prism hosts a converted file. The tagger is a plug-in ([`AudioTagger`]) so it can replace YAMNet without touching the rest. |
| **YAMNet** (Google) | AudioSet 521 classes: ~50 music genres, 7 moods, ~60 instruments, singing/speech/rap, nature sounds; 1024-d embedding | **ONNX, ready to download**; runs on the ONNX Runtime Prism already uses for Sing | 16.1 MB | **Apache-2.0** (class names CC BY-SA 4.0) | **Chosen** for tags and embeddings. |
| **LiteRT** | TFLite runtime, NNAPI/GPU delegates | AAR 8 MB | — | Apache-2.0 | Not added: Prism already downloads ONNX Runtime (Sing), and a second runtime only for a 0.07 GMAC/frame CNN buys nothing. NNAPI is deprecated from Android 15; XNNPACK-class CPU kernels are enough. |
| **FAISS / hnswlib** | ANN search | NDK builds | MB native | MIT / Apache-2.0 | Not needed: a library of 100k songs is 100k dot products of 128-byte int8 rows, tens of ms in Kotlin. |

Sleep research the score is built on:

- Scarratt et al., *PLOS ONE* 2023 (e0278813), 225,626 tracks from 985 sleep playlists vs. general listening: sleep music is
  softer (loudness d = −1.25), lower energy (d = −1.46), more acoustic (d = 1.20), more instrumental (d = 1.10), less
  danceable (−0.64), slower (−0.47), less speechy (−0.35) — **but it clusters into six groups, three of them ordinary
  pop/R&B/lo-fi "radio tracks"**. Slow and instrumental is the typical case, not the definition.
- Scarratt et al., *PLOS ONE* 2025 (PMC12370070): the listener's own response (liking, comfort, "freeing the mind",
  valence/arousal) outweighed the musical features; among those, **event density, dynamic variation, brightness and
  pulse clarity** were significant but small. Relaxation literature it cites: 52–85 BPM, small dynamic changes, few
  percussive instruments, minimal vocals.

So the score (section 4) is built from many weak, explainable factors, is never decided by tempo or vocals alone, and
leans on the listener's own feedback as soon as there is any.

## 2. Architecture

```
            ┌──────────────── MixTempo whole-song scan (existing) ────────────────┐
 file/cache │ MediaExtractor → MediaCodec → Downmix ─┬─ mono ~11 kHz (tempo, key, │
 /stream ──►│                                        │   bands, waveform)          │
            │                                        ├─ LoudnessMeter, all channels│
            │                                        │   (integrated, short-term → │
            │                                        │   LRA, max momentary)       │
            │                                        ├─ sample peak + true peak    │
            │                                        │   (original rate, channels) │
            │                                        └─ TaggerTap: 16 kHz mono,    │
            │                                            only 8 windows × 4.8 s    │
            └───────────────┬─────────────────────────────────────────────────────┘
                            ▼
              analysis.Descriptors (Kotlin DSP)      analysis.AudioTagger (YAMNet / ONNX Runtime)
                            └──────────────┬──────────────────────┘
                                           ▼
                       analysis.SongAnalysis (versioned, with provenance)
                                           ▼
                Room: song_analysis · song_auto_tags · sleep_votes · song_signals
                                           ▼
       SleepScore (explainable) · AnalysisIndex (similarity) · Recommender/QueueSequencer · tag review
```

- **One decode.** Analysis rides on the scan Prism already makes of every song it normalizes, crossfades or DJs
  (`MixTempo`). The same decoder output feeds four representations at once; nothing is decoded or resampled twice.
- **Pre-normalization audio.** The scan reads the file/stream itself, before `NormalizerProcessor`'s gain, the EQ
  or any effect: loudness, LRA and peaks describe the recording, not what playback made of it. Playback's normalized
  representation is a *gain*, not stored audio, so there is nothing to reuse there; conversely the analysis' LUFS is what
  normalization uses.
- **Background, incremental, cancellable.** Songs are analysed when they are scanned anyway (playing / up next /
  "Measure songs now") and by `AnalysisWorker` (WorkManager, once a day while charging, battery not low, screen off and
  nothing playing). "Analyse now" in Settings is the listener's own walk: the same `MeasureSongs` machinery and sheet as
  "Measure songs now" (Library, Downloaded, On this phone, Liked songs, each with what's left), kept going by
  `AnalysisService` with a progress notification and a Stop. Every step goes through `MixTempo.ease()`
  (gives way to audio underruns, touches on slow phones, the background CPU budget, and the song being heard).
  Cancellation is the coroutine's: a stopped walk keeps what's done and resumes where it was.
- **Normalization comes with the analysis.** Analysis and the loudness figure are one decode: every analysis scan measures
  and saves integrated loudness, its version and the peak exactly as "Measure songs now" does, before the descriptors are
  stored. The walks ask for the peak explicitly and count a song as done only when the player's figure is current too
  (`MixTempo.normalizationReady`: measured with the current meter and its peak, or scanned and found to have nothing to
  measure), so a song is never analysed while the player would have to play it at 0 dB or on a stale figure.
- **Cache and provenance.** A row is keyed by song id and carries `algorithmVersion`, `taggerId`/`taggerVersion` and
  `analyzedAt`. A song is analysed again only when its descriptors' version or the tagger's changed. User decisions
  (tag reviews, sleep votes) live in separate tables and survive re-analysis.
- **A version's change reaches the songs heard.** A song whose stored analysis is from older descriptors (or an older
  tagger) is scanned again when it plays (`MixTempo.measure`'s `refresh`, asked by the player for the form it's hearing):
  one decode, once per song, not the whole library's. Until then the old analysis still shows; the Sound profile says it's
  from an earlier version and offers "Analyse again" for that song now. "Analyse now" and the daily job redo the rest.
  Both of a song's rows count: the recording's, and the song's own (a song can play as another recording's form — a version
  picked for it, a file matched to it — and that recording being current says nothing about the row the song's page shows).
  A scan asked for either analyses both, so a walk over songs whose form is already current still redoes them, and their
  own row is written with the form's. "Analyse now" also lists them directly — **Analysed with an earlier version**, every
  id whose analysis is out of date (`AnalysisStore.oldIds`) — so a version's change is redone in one tap, in whichever set
  the song happens to be in.
- **Modular.** `Descriptors` (pure Kotlin, JVM-testable), `AudioTagger` (interface; YAMNet is one implementation),
  `SleepScore` (pure function of features + tags + personal model), `Similarity`, `QueueSequencer` — each replaceable.

### Audio representation used by each algorithm

| Algorithm | Representation | Why |
| --- | --- | --- |
| Integrated loudness (BS.1770), short-term loudness → LRA (EBU Tech 3342), max momentary | every channel, K-weighted, at the decimated rate (~11 kHz) | channel power summed as played; K-weighting's shelf sits well under 5 kHz |
| Sample peak | every channel, **original rate**, pre-decimation | exact |
| True peak (BS.1770-4 style, 4× oversampled) | every channel, **original rate**, only around candidate maxima | inter-sample overs; cheap |
| Tempo, beat, edges, onsets, event density, pulse clarity, rhythmic regularity | mono ~11 kHz → 16 log bands, 10 ms frames (`VideoSync.bandFeatures`) | existing tuned scan |
| Key, chroma, tonal clarity | mono ~11 kHz, 0.74 s frames, whitened | existing `KeyDetector` |
| Spectral centroid / rolloff / flatness / flux, ZCR, MFCC(13), low-band ratio, brightness share (> 1.5 kHz) | mono ~11 kHz, 1024-sample Hann frames (93 ms) | timbre; measured over 0–5.5 kHz (a documented ceiling; the figures are relative between songs) |
| Percussive share (median-filter HPSS) | the 16 bands every 10 ms | 93 ms frames smeared drum hits into the harmonic part |
| Segment curves (10 s): loudness, brightness, onset rate | derived from the above | consistency, sudden changes, transitions |
| YAMNet tags + embedding | mono **16 kHz**, 8 windows × 4.8 s spread over 8–92 % of the song, resampled once from the decoder's rate with a windowed-sinc low-pass | the model's own input; windows instead of the whole song bound memory (2.5 MB) and compute |

## 3. Storage (Room, version 14)

- `song_analysis(songId PK, algorithmVersion, taggerId?, taggerVersion?, analyzedAt, featuresJson, embedding BLOB?, sleepScore?)`
  — features as versioned JSON (`SongFeatures`); embedding = YAMNet's mean 1024-d vector, L2-normalized, **int8**
  (1 KB/song). `sleepScore` is a cache of the default-weights score for SQL filtering.
- `song_auto_tags(songId, tag, category, confidence, source, status; PK songId+tag)` — machine tags, never mixed with
  the listener's own `song_tags`. `status`: auto (confident), review (uncertain), accepted, rejected. Re-analysis
  replaces only `auto`/`review` rows; accepted/rejected rows are the listener's and stay.
- `sleep_votes(songId PK, vote, at)` — "good for sleep" / "not for sleep".
- `song_signals(songId PK, queued, sleepSkips, sleepFinishes, updatedAt)` — behaviour play events don't carry: manual
  queue additions, and what happened to a song while the sleep timer ran.

MusicBrainz/metadata edits (`SongMetadata`) and user tags (`song_tags`) are never written by analysis.

## 4. Sleep score

`SleepScore.of(features, tags, weights, personal)` → `0..100` + the factors behind it. Each factor is a calmness in
0..1 with a weight; missing factors drop out and the rest renormalize (the result says how complete it is).

| Factor | From | Calm end |
| --- | --- | --- |
| Arousal / energy | loudness (LUFS), max momentary, spectral energy | quiet — **weight 0.6–1.4 by what the player's gain leaves**: 0.6 once Normalize has equalized the level, rising with the residual dB a peak-limited lift or a "Loud" target leaves |
| Tempo — *weighted by pulse clarity* | BPM (octave-folded toward 60–90) | ≤ 85 BPM; irrelevant when there's no clear beat |
| Beat strength | pulse clarity, percussive ratio | soft or no pulse |
| Rhythmic regularity | tempo stability across segments | steady (predictable) |
| Dynamics | LRA, dynamic complexity | small variation |
| Sudden changes (disruptive) | a rise of ≥ 6 LU within half a second, out of music and up to near the song's own level | none (also caps the score) |
| Brightness | spectral centroid, rolloff | dark |
| Harshness (disruptive) | spectral flatness | tonal or acoustic — distorted guitars and screams are noise-like even when their spectral share reads "dark" |
| Event density | onsets per second | sparse |
| Vocals | YAMNet singing / speech / rap | instrumental (soft weight; configurable) |
| Instrumentation | YAMNet drums, distorted guitar vs piano, strings, ambient, nature | soft instruments |
| Consistency | spread of segment loudness/brightness; loudest segment vs median; intro/outro | even throughout |

A track whose sudden-change factor is very low is capped (≤ 45) whatever else it scores: one crash wakes a sleeper.
Each factor's pull is `importance × applicability × confidence`: a tempo only counts as far as there's a beat to feel, and
the tagger's vocals, instruments and mood count as sure as its evidence (a clear voice or clearly none; nothing at all means
nothing). A figure a song doesn't have — no tagger, no beat — counts as **neutral, not as calm**, so what's unknown can't
raise a score: the base is pulled toward 50 by how much of the full weight the song's figures cover. That is why a song
without the tagging model tops out lower than a fully analysed one.

The score follows playback: with **Normalize on** (Prism's default, album mode included) a master's own loudness is
undone by the player, so ENERGY's pull drops from 1.4 to 0.6 — the figure stays as a proxy for a dense, compressed master,
but it can't decide a score over what's heard at equal level. Density, brightness, beat, dynamics and sudden changes carry
the arousal judgement instead. The toggle is part of the score's weights, so scores change on the next read; nothing is
re-analysed, because every figure is already stored. The Sound profile says so under the score.

Personalization: a song's own vote moves its score ±25; votes and sleep-timer behaviour (skipped vs. played through
while the timer ran) fit a small ridge-regularized logistic model on the factor values, which shifts every score by at
most ±15. Every adjustment is listed with the factors.

The sleep timer's **"Sleep score at least N"** works like its BPM limit: songs under it (or not yet analysed) are passed
over, autoplay only adds songs that pass, and Prism's own radio orders a bedtime queue calm-first with energy never
rising.

## 5. Recommendations and autoplay

- **Similarity** (`Similarity`): YAMNet embedding cosine (timbre/genre/instrumentation), descriptor distance
  (z-scored across the library: rhythm octave-free, energy, brightness, dynamics), tag cosine (genre/mood/instrument
  probabilities) and key compatibility — each optional, weighted by what both songs have.
- **Taste** (`AudioTaste`): long-term from `PlayLikelihood` (completion, recency, likes, time of day) plus an audio
  taste vector — embedding centroid of finished/liked/queued songs minus skipped ones; short-term from the session
  (what was finished or skipped in the last hour).
- **Sequencing** (`QueueSequencer`): a beam search over the candidate set that maximizes
  relevance + taste − transition cost (tempo ratio, key clash, energy/loudness jump, timbre jump) − repetition (artist
  within 3, album within 5) − redundancy (MMR against what's already chosen) + discovery bonus. It orders whole runs,
  not single picks; manual queue items are never moved (autoplay only appends after them, as before).
- No LLM ranking, nothing in the cloud.

## 6. Tags

YAMNet's 521 classes are mapped to Prism's vocabulary: genre and subgenre (parent links: heavy metal → rock,
techno → electronic …), mood, instrument, vocals, style, soundscape, plus acoustic/electronic from instruments and
descriptors. Frame scores are pooled (mean of the top quarter of frames) and calibrated per category into confidence;
above the category's *sure* line a tag is `auto`, between *maybe* and *sure* it is `review`, below it isn't kept. The
listener reviews uncertain ones (accept / reject) from a song's Sound profile or Settings; an accepted tag can be added
to their own tags, which never happens by itself.

Only what the model heard is a tag. An earlier build also offered "Instrumental", *inferred* from the voice classes
being quiet: every instrumental track got a made-up 99 %, so it filled the review list and told the listener nothing the
model had measured. It is no longer a tag (rows written then are taken back as the analysis loads); the voice evidence
still feeds the sleep score's vocals factor. The score and the tags are shown in three places: a song's Sound profile,
Settings → Sound analysis → Sleep scores (the library, calmest first), and Stats for nerds (the playing song's score,
read live). Each row of Sleep scores has a **preview** button: it plays the song from the app's own data source (a file,
a download, the stream cache or the stream, resolved as a play) at the level Normalize would give it, pausing the
listener's music and picking it up again when the bar closes — the queue is untouched. The bar has play/pause, a slider
over the whole song and **Skim**, which plays eight seconds at four points across it (near the start, a third, two
thirds and near the end), so a score can be checked against what the song does without hearing all of it.
Each row also has **High** / **Low** marks (the score is too high / too low), for tuning only: they change nothing about the score, the timer or
radio — they are a `scoreMark` column and a `marked-high`/`marked-low` flag in the export, so a batch of wrong scores can be
collected and shared. (The listener's votes, by contrast, do move scores.)
Sleep scores' "Export sleep scores" writes the whole analysed library as CSV (score, base, completeness,
every factor's calmness and weight, the figures behind them, tags, the catalog's genre beside the audio's (with a
`genre-mismatch` flag — a metal track filed as classical stays out of the score, but is worth seeing), and flags for
sounds that look energetic but score calm), handed to the share sheet — the data for tuning the weights on a real
library.

## 7. Validation

**Unit tests** (`app/src/test/java/com/prism/music/analysis/`, JVM):

- descriptors on signals with known answers: a soft chord against a 128 BPM drum loop (onsets, percussiveness, pulse,
  loudness, tempo octave-free), one crash in a quiet piece (sudden rise and its penalty), two levels 20 dB apart (LRA
  20 ± 3 LU), and an inter-sample peak (true peak 1.0 where every sample is 0.707);
- the sleep score's ordering and explanations: slow but hard and loud scores low, a beatless wash "at 140" stays calm,
  vocals can be left out, votes and the learned model move scores within their bounds;
- tag vocabulary (sure versus review lines, styles naming their genre, instrumental and acoustic), pooling, the
  tagger's windows (8 × 4.8 s at 16 kHz), and score/embedding packing;
- similarity, artist spacing and a bedtime run that winds down;
- the store over Room: re-analysis keeps the listener's accepted and rejected tags, votes shift and unshift the score,
  a video's analysis also files under its song; plus the v13 → v14 migration and the sleep timer's score gate.

**Real recordings** (`RealSongsAnalysisTest`, opt-in: `ANALYSIS_DUMPS` = folder of `<label>_<name>.f32`, mono 44.1 kHz
from ffmpeg; `YAMNET_ONNX` = the model file). The full path on 11 freely licensed recordings (Wikimedia Commons, the
Internet Archive's netlabels), run on desktop ONNX Runtime:

- Cost per song (desktop): 40–220 ms descriptors, ~80 ms YAMNet for 72 frames. Phones are roughly 5–10× slower on one
  core, so expect well under 2 s per song, after the decode Normalize already pays for.
- Sleep order (Normalize on): a soft jazz lick 74, an Irish lullaby 65, ambient 64, then noise, rock, techno and hip-hop
  at 30–56 — the same order as when each recording played at its own level. Loud tracks gain 4–6 points with the
  gain-accurate weight, quiet ones lose 1–3, and the ambient-to-hip-hop gap narrows from 40 to 34 points.

The real-recording run led to these calibration changes, now covered by tests:

- Onset detection got a mean + 2σ threshold with an absolute floor, and bands floored 35 dB under the music. Sustained
  textures had read 5–7 onsets/s; they now read 0.5–3.7. (A per-second local threshold was tried: it read 0.7 kicks a
  second in rock and 4 in ambient texture, so the whole song's own figures stayed.)
- A listener's heavy-metal track scored 59/100 with "Brightness 5 %, Busyness 2.5/s, Sudden 0/min" as its *helps*: three
  measurements that each miss aggression. Spectral share reads a distorted mix "dark" (most of its energy is low-mid), so
  the score gained **Harshness** from flatness (tonal music 0.001–0.05, a noise wall 0.10); "sudden" moved from 10 LU over
  3 s to **6 LU over 0.5 s** (a compressed master never jumps 10, and a chorus hit lands at once while an ambient swell
  takes seconds); and the metal recording in the validation set fell from 52 to 29. Analysis figures are versioned, so
  songs analysed by the old extractor are analysed again.
- Percussiveness moved to the 10 ms bands.
- Brightness moved to the share of energy above 1.5 kHz. Centroids of bass-heavy mixes all sat at 250–830 Hz.
- The sleep score became an arousal mean multiplied by disruption penalties. A steady, compressed club track had
  scored 40 because "even" and "no sudden changes" counted as calm.

Not validated here: inference speed, memory and battery on a phone. The run above is desktop only, and a phone run
needs the tagging model downloaded from Settings.

## 8. Resources

- Model download: 16.1 MB (YAMNet) + ONNX Runtime 11.6–15 MB if Sing hasn't fetched it. Nothing in the APK.
- Per song: one decode (already paid by Normalize); descriptors ≈ 0.1–0.3 s; YAMNet on 38 s of audio ≈ 0.5–1 s on one
  core; peak extra memory ≈ 3 MB (tagger windows) + the model session (~25 MB, created per walk, closed after).
- Storage: ~2–3 KB per song.
- Battery/thermal: one thread, background priority, scheduled while charging; inline scans share the existing scan's
  budget (`BackgroundBudget`).

### Calibration against a listener's marks (descriptors v3, score v2)

An export of ~270 songs with 24 High/Low marks showed four systematic errors; each fix is general, not fitted to those songs:

- **Sudden loud moments** fired mostly on *quiet* music: a rest between phrases read as near-silence, so the next note was
  a "+30 LU jolt" (a harp lullaby read 7/min, solo piano 2.3/min, loud rock 0). A rise is now measured against the louder
  of the last 0.5 s and the 2.5 s before it, and only rises landing within 6 LU of the song's loudness count, the biggest
  one included (`Descriptors.suddenRises`, VERSION 3, so songs are re-analysed). On the validation recordings the solo
  piano went from 2.3/min (+15 LU) to 0.3/min; a crash after a quiet stretch is still caught.
- **Energy** read a quiet master as calm although Normalize plays it at the target. It's now the level heard (after the
  player's gain) plus how squashed the master is (peak-to-loudness ratio: 6–9 dB walls of sound vs 14+ dB open
  recordings); without a peak figure, the master's own level as before.
- **Brightness** was the share above 1.5 kHz of the whole spectrum, so bass-boosted and distorted mixes read "dark": the
  songs marked too high were darker than those marked too low. It's now the share of what's above 150 Hz, weighed 0.6.
- **Tempo** half/double errors made slow-detected loud songs look calm and double-detected ballads frantic: a wider curve
  (80–160 BPM) and weight 0.7. **Instruments** now include YAMNet's style evidence (rock, metal, punk, grunge, EDM,
  techno… against lullaby, ambient, new age), at weight 1.0, since a modest reading is already evidence.

`SleepCalibrationTest` (opt-in, `SLEEP_CSV` = an export) re-scores an export with the current code and prints how the
marked songs move; the file stays on the listener's machine.
