# Prismatica 1.1.2

`versionName` **1.1.2**, `versionCode` **1004**.

This release makes search in other scripts work throughout the Library, lets fast swipes finish without the distance, starts Shuffle without a video lookup, keeps tab changes in step with the bottom bar, and plays a music video at the same volume as its song.

## Search in other scripts

- Library → Songs, Albums, Artists, Playlists and History now read the phone's Latin spellings of names in Japanese, Korean, Cyrillic, Greek and other scripts, as Search → On this phone and Local files already did. "tori no uta" and "torinouta" both find 鳥の歌.
- The same spellings work on collection pages (albums, playlists, Liked songs and Downloads), in Uploads, and in the phone's and account's History.
- The spellings come from the on-phone romanizers, so they need no network; the whole-library search also accepts run-together spellings such as "acdc" for "AC/DC".

## The player and its panels

- A fast flick of the mini player, the player, the queue or the lyrics finishes opening or closing without the finger having to reach the usual tenth of the screen.
- The queue's title pull and the immersive player's close swipe finish on a quick downward flick too.

## Shuffle

- A shuffled start no longer waits on a music video's separate-song lookup. The random song plays at once, including with “Songs, not videos” set to everywhere.
- Large libraries keep the existing fast start: the chosen song and a few after it play first, and the rest is filled behind them.

## Tabs

- Tapping the Library tab while the Library is already showing no longer replays the changing-screens animation.
- With “Slide between screens”, the direction now follows the bottom bar: a tab on the right comes in from the right, a tab on the left from the left, mirrored in right-to-left languages. Back and pages opened from a list keep their usual direction.

## Volume

- A music video and its song are normalized by the audio actually heard: the video's own loudness while watching, the song's otherwise.
- Switching between a song and its video moves to that form's gain instead of keeping the other form's, so their volume matches. Until the video's figure is known, the song's stands in rather than starting from an unknown level.

## Verification

Targeted unit tests are green:

```
gradlew.bat :app:testDebugUnitTest --tests com.prism.music.NormalizerSongStartTest --tests com.prism.music.NormalizeGapsTest --tests com.prism.music.LikedMergeTest --tests com.prism.music.ShuffleStartTest --tests com.prism.music.PlayerMorphTest --tests com.prism.music.LibraryFindTest --tests com.prism.music.LibrarySearchTest --tests com.prism.music.HistorySearchTest --tests com.prism.music.PageSlidesTest --tests com.prism.music.SortingKeyTest --tests com.prism.music.UploadsHandWrittenFixturesTest --tests com.prism.music.TabSwapFadeTest
```

The complete unit suite ran 2,576 tests with the same four unrelated failures already documented for this branch/environment (`AppSettingsSizeTest`, `BlendExchangeTest`'s loopback listener, a `LookStoreTest` flake that passes alone, and `SettingsKitAdoptionTest`'s library-view sheet lookup). None touch the changed search, navigation, playback or normalization code.

No on-device listening test was available in the build environment.

## Release metadata

- `app/build.gradle.kts`: `versionCode = 1004`, `versionName = "1.1.2"`.
- Changelog: `CHANGELOG.md`, section 1.1.2.
- Artifact: `app/build/outputs/apk/release/app-release.apk` (31,660,957 bytes), signed with the key from `keystore.properties`; published as `Prismatica.apk`.
