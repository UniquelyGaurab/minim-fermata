# Minim Fermata: design plan

Minim Fermata is Minim's on-device storage, backup and recommendation engine. It keeps one listener's whole library (likes, playlists, history, stats, tapes) as an append-only event log on the phone, backs it up to a folder the user owns, merges the logs of several phones without a server, and learns that one listener and nobody else. No servers, no accounts, no "people like you" data. YouTube Music (through NewPipe) finds candidate songs; Fermata decides which of them fit *you*, explains why, and keeps its memory on your phone, including through an uninstall.

A fermata is the music symbol that says "hold this note longer". The engine's job is to hold on to what you love, for as long as you love it.

- **Repo:** `minim-fermata` (separate from `minim`, linked during development with `includeBuild`)
- **Package:** `com.minim.fermata`
- **Licence:** Apache-2.0, so other apps can use it (Minim itself stays GPL-3.0)
- **Runs on:** Android 9 (API 28) and newer, plus any JVM 17+ for tests and tools

## What changed since the first draft

The first draft made Fermata a recommendation engine next to Minim's library and Firebase sync. Fermata now **replaces Firebase completely**:

- The whole library becomes events in the same log as the listening signals. Minim's tables become fast views built from the log.
- Every event has a unique id, so merging two phones is a union of logs. Conflicts resolve last-writer-wins, deterministically. No server.
- Backups and multi-phone sync both go through a folder the user picks with the Storage Access Framework. No account.
- Removing Firebase also removes the built-in Gemini route (Firebase AI Logic + App Check). Bring-your-own AI Studio key stays, and the key is never written to a backup.
- The code is split into modules (log, library, recs, backup) so another app can use only the recommendation side.

## Principles

1. **Only you.** Every signal comes from your own phones. No collaborative filtering, ever.
2. **No server, no account.** Your library lives on your phone and in a folder you choose.
3. **Never worse than YTM.** With no history, Fermata hands back YouTube's order untouched. Its say grows as it learns you.
4. **Every pick has a reason** a person can read: "You replayed this 4 times last Tuesday night."
5. **You can see and correct it.** "I'm over this artist" and "that was just a phase" are first-class inputs.
6. **Old phones first.** Small math, small files, heavy work only while charging.
7. **Your memory survives.** Daily backups to a folder you own, restored after a reinstall.

## Repo layout

```
minim-fermata/
  log/       pure Kotlin/JVM: events, ids, EventStore, InMemoryEventStore, merge
  library/   pure Kotlin/JVM: the library projection (likes, playlists, history, not interested, stats)
  recs/      pure Kotlin/JVM: taste model, confidence, ranking, reasons (depends on log only)
  backup/    pure Kotlin/JVM: the .minim format (manifest + JSON Lines), encode/decode
  core/      pure Kotlin/JVM: the Fermata facade tying the four together
  android/   Android library (minSdk 28): SqliteEventStore, SAF backup worker
  sample/    tiny JVM demo
```

- `log`, `library`, `recs`, `backup` and `core` have **zero Android imports** and run on any JVM.
- An app that only wants recommendations depends on `recs` (which pulls in `log`) and feeds it its own events.
- **While building:** Minim's `settings.gradle.kts` uses `includeBuild("../minim-fermata")`, so both repos are edited side by side with nothing to publish.
- **Later:** tag releases and pull them into Minim through JitPack.

## The event log (source of truth)

An append-only list of what happened. Never edited, only added to (and compacted when old). Everything else, the library screens and the taste model, is a projection rebuilt from it.

### Events

| Event | Key fields |
|---|---|
| `play` | track (id, title, artist, duration, thumbnail), listened ms |
| `skip` | track, position in ms |
| `queued` | track queued by hand (a strong "I want this") |
| `like` / `unlike` | track or artist |
| `not_interested` / `interested` | track or artist (`interested` undoes it) |
| `playlist_create` | playlist id, name |
| `playlist_rename` | playlist id, name |
| `playlist_delete` | playlist id |
| `playlist_add` | playlist id, track, optional index |
| `playlist_remove` | playlist id, track id |
| `playlist_move` | playlist id, track id, new index |
| `tape_play` | tape (video id, title, channel...), listened ms, speed |
| `taste_edit` | over it / just a phase / reset, on a track or an artist |

A replay is not its own event: it is a `play` whose previous plays in the same session include the same track, which the model can see.

Every event carries its context: **timestamp, hour, day of week, session id, previous track id and source** (radio, search, library, playlist, discover, tape, queue, import). Session order is what lets Fermata learn "after A, you usually keep B".

An event type the running version does not know (written by a newer version on another phone) is kept verbatim and passed on, never dropped.

### Event ids

Every event has a unique id: **`<device id>:<counter>`**. The device id is 16 random hex characters made once per install (never derived from hardware or an account); the counter starts at 1 and goes up by one per event on that device. Two devices can never write the same id.

History imported from Minim's old tables or from the Firestore copy uses the pseudo-device `legacy` with a counter hashed from a stable key (for example `play|<old row id>` or `like|<track id>`). Two phones importing the same synced history produce the same ids, so a later merge does not count anything twice.

### Merging and conflicts

- **Merge = union of logs, deduplicated by id.** It is commutative, associative and idempotent: phones converge whatever order they exchange events in, and receiving the same events twice changes nothing.
- **Canonical order:** timestamp, then device id, then counter. Every projection replays events in this order.
- **Conflicts resolve last-writer-wins** because of that order: if one phone likes a song and another unlikes it, the later one wins; on an exact timestamp tie, the event from the device id that sorts last wins. Every phone computes the same result.
- **Clock rule:** a new event's timestamp is never earlier than the newest event already in the local log (+1 ms). So an action taken after seeing another phone's event always sorts after it, even if this phone's clock is behind. Hour and day of week still come from the real wall clock.
- **Version vectors:** the highest counter per device tells a peer what is new (`heads` / `after`), so a sync sends only the missing events.

### Library projection

The library is a fold over the log in canonical order:

- **Liked tracks and artists**, newest first, with the time of the like still in effect
- **Playlists** in creation order, each with its tracks in order. A track appears in a playlist at most once (as in Minim today); an index past the end appends, a move is clamped to the list; a deleted playlist id stays deleted; an add seen before its playlist's create keeps the name from the create when it arrives
- **Play history** of counted plays (30 s heard, or half a shorter song), with source and session
- **Stats:** play counts and time heard per track; tape history with speed
- **Not interested** tracks and artists
- **Track metadata:** the newest non-empty title, artist, duration and thumbnail seen per track

In Minim the existing tables (`tracks`, `playlists`, `playlist_tracks`, `plays`, `not_interested`, `listen_time`, `tape_plays`, `tape_listen_time`) become these projections: kept up to date as each event is recorded, and rebuilt from the log after a merge brings in older events or after a schema change.

### Where it lives in Minim

- Its own database file, `fermata.db`, next to Minim's library database. One row per event, the event stored as its JSON Lines text, with the id as primary key (`INSERT OR IGNORE` makes re-imports harmless) and an index on (timestamp, device, counter) for the canonical order.
- The JVM modules only know the `EventStore` interface. The `android` module implements it on SQLite; `log` ships an in-memory store for tests and tools.

### Size on an old phone

- An event is about 60 to 200 bytes of JSON. A heavy listener makes around 150 a day, so roughly **3 to 10 MB a year** before compression (backups are gzipped).
- Library events (likes, playlist edits) are never compacted: they are the library. Raw listening events are kept for 12 months; older ones are rolled up into monthly summaries per track and artist, so long-term taste (and rediscovery) still works.
- The taste model stays under 1 MB: top 5,000 tracks, top 1,000 artists, top 20 links per track.

## The taste model (derived, rebuildable)

Built from the log and updated a little after every event. When the algorithm changes, Fermata replays the log and rebuilds the model from scratch. Nobody loses their history to an update.

- **Track and artist scores** with time decay (recent listening counts more) — in the first commit
- **Confidence** (how much Fermata trusts itself yet) — in the first commit
- **Co-play links** between songs heard in the same sessions
- **Context weights** by hour and day of week
- **Fatigue** per track (how worn out a song is right now)
- **Eras** (current and past phases, by artist and sound)
- **Explore settings** (how adventurous you are, learned over time)

## Backups and sync, without a server

App-private storage is wiped when the app is uninstalled, so backups go to a folder the user picks.

### Where

- The Storage Access Framework folder picker (`ACTION_OPEN_DOCUMENT_TREE`). The user picks a folder once, for example `Documents/Minim`, and Minim keeps a persistable permission to write there.
- Works on Android 9 through current versions, needs **no storage permission**, and the files **survive an uninstall**.
- The picker also offers cloud providers (Google Drive, Nextcloud, Dropbox) and any local folder, including a Syncthing folder.
- Plain `Downloads` files are not a safe bet: on Android 11 and newer, a reinstalled app can't read the files its old install wrote there.

### What

One file per day, `minim-backup-2026-11-11.minim`, which is a zip holding:

- `manifest.json`: format version, created time, device id, Fermata and app versions, event count, the version vector (highest counter per device), and a SHA-256 checksum per file
- `fermata-events.jsonl.gz`: the full event log as gzipped JSON Lines, one event per line

Since the whole library is events, the log is the whole backup. A model snapshot (`fermata-model.json.gz`) may be added later as an optional speed-up; readers ignore files they do not know, and refuse a format newer than they understand with a clear message.

One line looks like this:

```json
{"id":"3f9a0c5e1b7d2468:42","ts":1794405000000,"hour":19,"dow":3,"session":"3f9a0c5e1b7d2468.40","prev":"dQw4w9WgXcQ","source":"radio","type":"play","data":{"track":{"id":"kJQP7kiw5Fk","title":"Despacito","artist":"Luis Fonsi","durationMs":229000},"listenedMs":182000}}
```

JSON Lines rather than a raw database copy, because:

- Android 9 ships SQLite 3.22, which has no `VACUUM INTO` for safe live copies
- It's readable and open: anyone can inspect their own history, or build on it
- Restoring works across schema versions

**Never in the backup:** the Gemini API key or anything else secret. The format has no place for settings at all in version 1; if settings are added later, secrets stay out.

### When

- WorkManager, once a day, only while charging with battery not low
- Written to a temp file, then renamed, so a half-written backup never replaces a good one
- Keeps the last **7 daily** backups and deletes older ones
- A "Back up now" button in Settings → Storage, with the last backup time shown

### Restore

1. Fresh install, empty library: the first screen offers "Restore from a backup".
2. The user picks the same folder. Minim lists the backups it finds, newest first.
3. It checks the manifest and checksums and merges the events into the empty log. The new install gets a new device id, so its own events can never clash with the old install's.
4. The library and the taste model are rebuilt from the log.

Because restore is a merge, restoring into a library that already has history is safe too: nothing is replaced, nothing is counted twice.

### Several phones

Point every phone at the same folder (a Google Drive, Nextcloud or Dropbox folder through the SAF picker, or a Syncthing folder). Each phone writes its own daily backup and, when it runs, reads the other phones' newest backups and merges any events it has not seen (the manifest's version vector says at a glance whether there is anything new). Because merging is conflict-free, it does not matter which phone syncs first or how often.

Later, maybe: a direct phone-to-phone transfer by QR code + local Wi-Fi, exchanging only the missing events.

## Leaving Firebase

Today Minim uses Firebase for optional Google sign-in sync (Firestore) and for the built-in Gemini route (Firebase AI Logic + App Check). Fermata replaces the sync; the built-in Gemini route goes away with Firebase, and the bring-your-own AI Studio key stays.

- **Bridge release:** pulls the user's Firestore data down into the local Fermata log (as `legacy` events with deterministic ids), writes a backup, signs the user out and deletes the cloud copy.
- **Next release:** removes Firebase entirely (dependencies, google-services plugin, sign-in UI).

### Importing Minim's old tables

When Fermata is first wired into Minim, the existing tables are imported as historical events with `legacy` ids:

- `plays(id, track_id, played_at, listened_ms)` → `play` events (key `play|<id>`), metadata from `tracks`
- `tracks.is_liked / liked_at` → `like` events at `liked_at`
- `playlists` + `playlist_tracks` → `playlist_create` + `playlist_add` in `position` order. Minim's playlist names are unique, so the imported playlist id is derived from the name, and two phones importing the same playlist agree on it
- `not_interested` → `not_interested` events at `added_at`
- `tape_plays` → `tape_play` events
- `listen_time` and `tape_listen_time` (hourly totals) are recomputed from plays where the plays cover the same period; older totals that have no plays behind them are kept as a one-off summary (to be designed in step 4)

## How it learns

- **Taste:** completed plays, replays, likes and songs queued by hand push scores up. Early skips and "not interested" push them down. Everything fades slowly with time.
- **Context:** the same song scores differently at 1am and 9am, learned from when you actually play it.
- **Co-play:** songs you keep playing back to back get linked, so a radio queue can follow your own habits.
- **Eras:** a burst of plays from an artist or sound starts an era. Fermata leans into the current one and remembers past ones.
- **Fatigue:** heavy repeats with falling completion rest a song. It comes back after a few weeks.
- **Rediscovery:** songs you loved months ago and stopped playing get brought back now and then.
- **Explore vs exploit:** Thompson sampling mixes in new songs (about 1 in 5 to start) and learns how adventurous you are from how you react.

## How Minim uses it

1. YouTube provides candidates: radio, mixes, related songs.
2. Fermata scores each one for this listener and this moment.
3. Final order blends YouTube's order with Fermata's, weighted by confidence:
   `final = (1 - c) × youtubePosition + c × taste`, both scaled 0 to 1 over the candidates. At c = 0 YouTube's order comes back untouched; ties keep YouTube's order.

- **Confidence** starts at 0 and climbs with history (about 0.3 after a week of normal listening, 0.8 after a couple of months), capped at 0.85 so YouTube always keeps some say.
- **Uses:** reranking the radio queue, choosing and ordering Discover shelves, and new shelves like "Back in rotation" (rediscovery) and "Your current era".
- **Reasons** show on long-press or in the song menu: "Because you", plus one line.

## The API

```kotlin
val fermata = Fermata(store = SqliteEventStore(context), deviceId = savedDeviceId)

// The library
fermata.record(EventBody.Like(Subject.Track(track)))
fermata.record(EventBody.PlaylistAdd(playlistId, track))
val library: LibraryState = fermata.library()   // likes, playlists, history, stats

// Listening (session and previous track are filled in when not given)
fermata.record(EventBody.Play(track, listenedMs = 182_000), source = Source.RADIO)

// Recommendations
val picks: List<Pick> = fermata.rank(candidates)
// Pick(track, score, reason = "You've played this 4 times")
val taste: TasteView = fermata.taste()           // for the "your taste" screen
fermata.edit(TasteEditKind.OVER_IT, Subject.Artist("Artist"))

// Backup, restore, sync
fermata.export(out)                              // a .minim backup
fermata.import(input)                            // merge a backup in
fermata.merge(fermata2.eventsAfter(fermata.heads()))

// One-off import of old data
fermata.importLegacy(EventBody.Play(track, 90_000), playedAt, legacyKey = "play|$rowId")
```

## Build order

1. **Minim prep release:** start logging skips, session id, previous track and source in Minim, so richer history is already waiting when Fermata ships.
2. **Fermata core:** events, ids, merge, library projection, backup format, decayed taste scores and confidence, with JVM tests. *(First commit.)*
3. **Fermata Android module:** SQLite event store, SAF daily backups and restore. *(SQLite store and a backup worker stub in the first commit.)*
4. **Integrate into Minim:** Minim's tables become projections; the old tables are imported as historical events.
5. **Bridge release off Firebase:** pull Firestore down into the log, back up, sign out, delete the cloud copy.
6. **Remove Firebase** entirely.
7. **Recommendations:** context, co-play, fatigue, eras, rediscovery, explore, reasons and the taste screen; rerank the radio queue behind a setting. Then tag v1.0 and publish through JitPack.

## Open questions

- Should tapes feed the same taste model, or keep a separate one?
- Should backups have an optional password (they hold listening history in a folder other apps, or a cloud provider, might read)?
- How long should a phone keep reading another phone's backups after it stops writing new ones (a lost or retired phone)?
- Do settings (theme, audio quality, and so on) belong in the log or in a separate, secret-free settings file?
