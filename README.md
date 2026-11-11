# Minim Fermata

On-device storage, backup and recommendations for [Minim](https://github.com/UniquelyGaurab/minim), the YouTube Music client for Android. No server, no account: your whole library lives on your phone as an append-only event log, backs up to a folder you choose, merges across your phones without conflicts, and learns your taste and nobody else's.

A fermata is the music symbol that says "hold this note longer".

**Status: early.** This is the first commit: the core (events, merge, library projection, backup format, a simple decayed taste score and the confidence blend) is in place and tested. The Android SQLite store works; the backup worker is a stub. Minim does not use Fermata yet. See [DESIGN.md](DESIGN.md) for the full plan and build order.

## Principles

- **Only you.** Every signal comes from your own phones. No collaborative filtering, ever.
- **No server, no account.** Your library lives on your phone and in a folder you pick.
- **Never worse than YouTube Music.** With no history, Fermata hands back YouTube's order untouched. Its say grows as it learns you.
- **Every pick has a reason** a person can read.
- **You can see and correct it.** "I'm over this" and "that was just a phase" are first-class inputs.
- **Old phones first.** Android 9 (API 28) and up, small math, small files.
- **Your memory survives.** Daily backups to a folder you own survive an uninstall. Secrets such as your Gemini API key are never written to a backup.

## How it works

- Everything is an **event**: likes, playlist edits, plays, skips, tapes, taste edits. Each event has a unique id (`<device id>:<counter>`) and its context: time, hour, day of week, session, previous track and source.
- The **library** (liked songs and artists, playlists in order, history, stats, "not interested") is a projection rebuilt from the log.
- **Merging** two phones is a union of their logs, deduplicated by id. Conflicts (a like on one phone, an unlike on the other) resolve last-writer-wins by timestamp, with the device id as a deterministic tiebreak, so every phone ends up with the same library.
- **Backups** are `.minim` files: a zip with `manifest.json` and the log as gzipped JSON Lines, written to a folder picked with the Storage Access Framework (no storage permission). Point two phones at the same synced folder (Google Drive, Nextcloud, Dropbox, Syncthing) and they merge each other's events.
- **Recommendations** blend the order YouTube gave with Fermata's taste score, weighted by how confident Fermata is. At zero confidence the input order comes back untouched.

## Modules

| Module | What it holds | Android? |
|---|---|---|
| `log` | `Event`, `EventId`, `EventStore`, `InMemoryEventStore`, `EventLog.merge()` | No |
| `library` | `LibraryProjection`: likes, playlists with order, play history, stats, not interested | No |
| `recs` | `TasteModel` (decayed track and artist scores, confidence), `Ranker` (confidence blend, reasons) | No |
| `backup` | The `.minim` format: `BackupArchive`, `Manifest`, JSON Lines `EventCodec` | No |
| `core` | The `Fermata` facade: `record`, `library`, `rank`, `taste`, `export`, `import`, `merge` | No |
| `android` | `SqliteEventStore`, SAF backup helpers and `BackupWorker` (stub), minSdk 28 | Yes |
| `sample` | A tiny JVM walk-through | No |

The pure modules have zero Android imports and target JVM 17 bytecode. An app that only wants recommendations can depend on `recs` alone and feed it its own events.

```kotlin
val fermata = Fermata(store = SqliteEventStore(context), deviceId = savedDeviceId)

fermata.record(EventBody.Like(Subject.Track(track)))
fermata.record(EventBody.Play(track, listenedMs = 182_000), source = Source.RADIO)

val library = fermata.library()            // likes, playlists, history, stats
val picks = fermata.rank(radioCandidates)  // Pick(track, score, reason)

fermata.export(outputStream)               // a .minim backup
fermata.import(inputStream)                // merge a backup in (safe to repeat)
```

Create the device id once per install with `DeviceIds.random()` and keep it.

## Using it in Minim

**During development**, check out both repos side by side and add to Minim's `settings.gradle.kts`:

```kotlin
includeBuild("../minim-fermata")
```

Then depend on it in `app/build.gradle.kts` by its published coordinates; Gradle substitutes the local projects automatically:

```kotlin
implementation("com.github.UniquelyGaurab.minim-fermata:android:main-SNAPSHOT")
```

Fermata uses the same toolchain as Minim (Gradle 8.5, AGP 8.2.2, Kotlin 1.9.22, JDK 17), so the two builds combine without plugin version clashes.

**Later**, once releases are tagged, pull it from [JitPack](https://jitpack.io):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
implementation("com.github.UniquelyGaurab.minim-fermata:android:<tag>")
```

## Building

Requires JDK 17.

```sh
./gradlew jvmTest -Pfermata.skipAndroid=true   # pure JVM modules, no Android SDK needed
./gradlew build                                # everything, needs an Android SDK (platform 34)
./gradlew :sample:run -Pfermata.skipAndroid=true
```

For the Android module, point `ANDROID_HOME` (or `sdk.dir` in `local.properties`) at an SDK with platform 34. Its tests run on the JVM with Robolectric.

## Licence

Apache License 2.0, see [LICENSE](LICENSE). Minim itself is GPL-3.0; Fermata is Apache-2.0 so other apps can use it too.
