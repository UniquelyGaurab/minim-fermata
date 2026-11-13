# Minim Fermata

A local-first listening history and recommendations engine for Android music apps, with no backend. Fermata keeps one listener's whole library (likes, playlists, play history, stats, "not interested") as an append-only event log on the phone, backs it up to a folder the user picks, merges several phones through that folder without conflicts, and learns that one listener's taste and nobody else's. No server, no account.

A fermata is the music symbol that says "hold this note longer".

## Used in Minim

[Minim](https://github.com/UniquelyGaurab/minim), the YouTube Music client for Android, builds Fermata from source as a git submodule (`fermata/`). Minim 6.0.0 was the first release built on it: since then Minim's library and listening history are recorded through Fermata, and its backups, restore and multi-phone merge are Fermata's. Minim 6.5.0 added Fermata's recommendations and the lean, append-only backups (format 2). Minim's main branch pins Fermata at [`b61134d`](https://github.com/UniquelyGaurab/minim-fermata/commit/b61134df49f902e6d1466cab4d0fd79d5e1b957c).

In Minim, Fermata:

- keeps the log inside Minim's own database (`SqliteEventStore.inDatabase`), so an event and the rows the screens read commit in one SQLite transaction;
- reorders the YouTube radio queue and the Discover mixes for the listener, and fills the On repeat, Forgotten favourites and "Because you like…" shelves;
- records "Not interested" and its Undo, which reach the other phones too;
- backs up when the app goes to the background, once a day while charging, and on Back up now, and merges the other phones' backups when the app opens.

## Principles

- **Only you.** Every signal comes from your own phones. No collaborative filtering, ever.
- **No server, no account.** Your library lives on your phone and in a folder you pick.
- **Never worse than YouTube Music.** With too little history, Fermata hands back YouTube's order untouched. Its say grows as it learns you.
- **Every pick has a reason** a person can read.
- **Old phones first.** Android 9 (API 28) and up, small math, small files.
- **Your memory survives.** Backups go to a folder you own and survive an uninstall. Secrets such as a Gemini API key are never written to a backup.

## How it works

- **Everything is an event:** plays, skips, songs queued, likes and unlikes, "not interested" and "interested", playlist edits, tapes, taste edits and history clears. Each has a unique id (`<device id>:<counter>`) and its context: time, hour, day of week, session, previous track and source.
- **The library** (liked songs and artists, playlists in order, history, stats, "not interested") is a projection rebuilt from the log.
- **Merging** two phones is a union of their logs by event id, so repeating a merge adds nothing. Conflicts (a like on one phone, an unlike on the other) resolve last-writer-wins by timestamp, with the device id as tiebreak, so every phone ends up with the same library.
- **Clearing history** records a `history_clear` event that every phone honours on merge, restore and import. Likes, playlists and "not interested" stay.
- **Recommendations** come from a taste model kept in memory and updated as each event is recorded, so a request never scans the log.

## Recommendations

The app brings candidates (Minim uses YouTube's radio and mixes); Fermata reorders them for this listener. The final order blends YouTube's order with Fermata's taste score, weighted by how confident Fermata is.

- **Radio** (`recs.radio`): leaves out songs marked "not interested" (the song or its artist), songs skipped early twice in a row (until heard past the early point again, or 60 days pass) and songs heard in the last 3 hours, then brings recent ones back at the end if fewer than 10 are left. Each dropped song comes with its reason.
- **Shelves:** `rank` (any list, without the recent-play filter), `onRepeat` (3 or more plays in the last 7 days), `forgottenFavourites` (liked, or played 5 or more times, and not heard for 30 days) and `becauseYouLike` (top artists, each with the listener's own best songs of theirs as seeds).
- **Skips:** a skip before 30 seconds, or before 20% of a song whose length is known (whichever is later), is an early skip and counts against that song, not as a short play. Three early skips of one artist within 14 days lower the artist a little.
- **"Not interested" in a song** lowers that song only, not its artist.
- **Undo is exact:** an unlike or "interested" takes back what is left of the original like or "not interested" after time decay, so the score returns to where it would be without them.
- **Cold start:** with fewer than 30 counted plays, or fewer than 10 different songs, every request returns null, which means "keep your current order".
- **Never blocks:** the model is built once per process by `recs.warmUp()` on a background thread. Until then, requests return null.

Every number above is a default in `RecsConfig`. Context by hour, co-play links, fatigue, eras and explore are planned in [DESIGN.md](DESIGN.md) but not built yet.

## Backups and syncing phones

**Format 2** (since Minim 6.2.0's test build, shipped in 6.5.0). Each phone keeps a chain of files it writes once and never changes:

- `minim-<device>-g<gen>-base.fermata`: the whole log when that generation started.
- `minim-<device>-g<gen>-p<seq>.fermata`: a piece holding only the events logged since the file before it.

Each file is an 8-byte magic and format number, a readable JSON header (version vectors, counts, clear point, restore point, CRC-32), then a columnar body: a song table, one copy of each string, time deltas, all compressed once with DEFLATE. It is lossless: every history decodes back to exactly the same events.

- **Backups only add what's new.** After one new play, a backup writes one piece of about 1 KB.
- **Combining:** at 32 pieces, or once the pieces add up to half the base (and at least 64 KB), the next backup writes a new base. Old files are deleted only after the new base is written and checked, so a kill at any step leaves a backup that restores. The tests kill it at every create, write, rename and delete.
- **Daily restore point:** each chain keeps a "previous" point that moves on at most once a day. After a clear history, no file keeps the cleared history.
- **Cheap merge on open:** a merge reads only the files after the point it merged last, and only their headers when nothing is new. A half-synced or missing piece stops that phone's merge there, and the next merge carries on.

Measured by `LeanBackupsTest.bench` on generated listening histories, on a desktop computer:

| History | Format 1 | Format 2 |
|---|---|---|
| 5,228 events | 286 KB (54.7 bytes an event) | 102 KB (19.5 bytes an event) |
| 10,023 events | 587 KB (58.6 bytes an event) | 183 KB (18.3 bytes an event) |
| 50,036 events | 3.41 MB (68.2 bytes an event) | 853 KB (17.0 bytes an event) |

The backup after one new play wrote 1,024 to 1,031 bytes at every size, in a few milliseconds; format 1 rewrote the whole history each time (238 KB to 2.96 MB for these three histories). A merge that finds one new piece took a few milliseconds, and 0.2 ms when nothing was new. Run it yourself with `FERMATA_BENCH=1` (see Building).

**Several phones.** Point every phone at the same folder: one synced by a folder-sync app such as Syncthing, or a cloud provider offered by the Android folder picker (Google Drive, Nextcloud, Dropbox). The folder is picked with the Storage Access Framework, so no storage permission is needed. Each phone writes and deletes only its own files, so a sync app never sees two phones write one file, and each phone merges the others' events it has not seen. Because merging is conflict-free, it doesn't matter which phone syncs first or how often.

**Event exchange.** `EventExchange` answers "which events is the other phone missing" (`state()`, `missingFor(peer)`, `pack`/`unpack`, `receive`). It is meant for a direct phone-to-phone sync over Wi-Fi later. Nothing calls it yet.

The full format and every step are in [DESIGN.md](DESIGN.md), "Backups and sync, without a server".

## Modules

| Module | What it holds | Android? |
|---|---|---|
| `log` | `Event`, `EventBody`, `EventId`, `DeviceIds`, `EventStore`, `InMemoryEventStore`, `EventLog` merge | No |
| `library` | `LibraryProjector`: likes, playlists in order, play history, stats, not interested | No |
| `recs` | `TasteModel`, `Recommender` (radio, shelves, drops with reasons), `Ranker`, `RecsConfig` | No |
| `backup` | Format 2 (`LogFile`, `CompactCodec`, `BackupNames`) and format 1 (`BackupArchive`, `Manifest`, `EventCodec`) | No |
| `core` | The `Fermata` facade, `FermataRecs` (`fermata.recs`), the Minim importer, and `sync/`: `FolderBackups` (backup, restore and merge over any folder, including `FileBackupFolder`) and `EventExchange` | No |
| `android` | `SqliteEventStore`, `MinimDatabaseReader`, `FermataBackups` (SAF folder, WorkManager jobs, status, restore, merge, restore offer), `FermataHolder`; minSdk 28 | Yes |
| `sample` | A tiny JVM walk-through | No |

The pure modules have no Android imports and target JVM 17. An app that only wants recommendations can depend on `recs` (which pulls in `log`) and feed it its own events.

## API

```kotlin
// Create the device id once per install with DeviceIds.random() and keep it outside backups.
val fermata = Fermata(store = SqliteEventStore(context), deviceId = savedDeviceId)

// Record what happens
fermata.record(EventBody.Play(track, listenedMs = 182_000), source = Source.RADIO)
fermata.record(EventBody.Skip(track, positionMs = 6_000), source = Source.RADIO)
fermata.record(EventBody.Like(Subject.Track(track)))
fermata.record(EventBody.NotInterested(Subject.Track(track)))
fermata.record(EventBody.Interested(Subject.Track(track)))   // undo
val library: LibraryState = fermata.library()                 // likes, playlists, history, stats

// Recommendations: warm up off the main thread, then requests never block
fermata.recs.warmUp()
val radio: Reranked? = fermata.recs.radio(youtubeRadio)       // null: not ready or cold start
val shelf: Reranked? = fermata.recs.rank(mixTracks)
val onRepeat: List<Pick>? = fermata.recs.onRepeat(limit = 20)
val forgotten: List<Pick>? = fermata.recs.forgottenFavourites(limit = 20)
val because: List<ArtistPick>? = fermata.recs.becauseYouLike(artists = 3, seedsPerArtist = 5)
// Pick(track, score, reason), Reranked(picks, dropped), Dropped(track, reason)

// Clear listening history (likes and playlists stay)
fermata.clearHistory()

// Backups to a folder (android module). Blocking calls: run them off the main thread.
val backups = FermataBackups(context, fermata, appVersion = BuildConfig.VERSION_NAME)
backups.setFolder(treeUri)               // from ACTION_OPEN_DOCUMENT_TREE; schedules the daily job
backups.enqueueBackupOnBackground()      // app went to the background: back up if changed
backups.enqueueMergeOnOpen()             // app came to the foreground: merge other phones
backups.backUpNow()                      // BackupResult
backups.merge()                          // MergeResult
backups.listBackups()                    // List<BackupInfo>, every phone's, newest first
backups.restore(info)                    // RestoreResult, all or nothing
backups.status()                         // BackupStatus: last backup, last error, last merge
// The Application implements FermataHolder so the background jobs can reach Fermata.

// Phone to phone (not used by Minim yet)
val exchange = EventExchange(fermata)
val missing = exchange.missingFor(peerState)   // peerState: the other phone's exchange.state()
```

On the JVM, `FolderBackups(fermata, InMemoryBackupStateStore())` does the same backups over a `FileBackupFolder(dir)`. [DESIGN.md](DESIGN.md), "The API", has the rest: the Minim importer, transactions, the applier and the listeners.

## Using it as a submodule

This is how Minim uses it. Add it once:

```sh
git submodule add https://github.com/UniquelyGaurab/minim-fermata.git fermata
```

After cloning the app:

```sh
git submodule update --init
```

In the app's `settings.gradle.kts`:

```kotlin
includeBuild("fermata")
```

Then depend on it by its coordinates; Gradle swaps in the local projects:

```kotlin
implementation("com.github.UniquelyGaurab.minim-fermata:android:0.2.0-SNAPSHOT")
```

Fermata uses the same toolchain as Minim (Gradle 8.5, AGP 8.2.2, Kotlin 1.9.22, JDK 17), so the two builds combine without plugin version clashes. There are no tagged releases yet; the repo has a `jitpack.yml` for when there are.

## Building and testing

Requires JDK 17.

```sh
./gradlew jvmTest -Pfermata.skipAndroid=true    # pure JVM modules, no Android SDK needed
./gradlew build                                 # everything, needs an Android SDK (platform 34)
./gradlew :sample:run -Pfermata.skipAndroid=true
FERMATA_BENCH=1 ./gradlew :core:test --tests '*LeanBackupsTest.bench' -Pfermata.skipAndroid=true -i
```

For the Android module, point `ANDROID_HOME` (or `sdk.dir` in `local.properties`) at an SDK with platform 34. Its tests run on the JVM with Robolectric. GitHub Actions runs the JVM tests and the Android module's tests on every push to main.

## Compatibility

- Android 9 (API 28) and newer; the pure modules run on any JVM 17 or newer.
- **Format 2 is one-way.** Fermata still lists, restores and merges format-1 `.minim` backups (`-latest.minim` and `-previous.minim`) for good, but no longer writes them. Fermata before `b61134d` (Minim 6.0.0 and the 6.1 test builds) can't read format 2, so every phone sharing a folder needs to update.
- Newer files are refused with a clear message rather than misread. Event types and fields a version doesn't know are kept as they are.
- `Fermata.export` and `import` still read and write a single format-1 `.minim` stream, for tools and tests.

## More

- [DESIGN.md](DESIGN.md): the full design, both backup formats, the recommendation rules and open questions.
- [CHANGELOG.md](CHANGELOG.md): what changed (changes before format 2 are in Minim's CHANGELOG).

## Licence

Apache License 2.0, see [LICENSE](LICENSE). Minim itself is GPL-3.0; Fermata is Apache-2.0 so other apps can use it too.
