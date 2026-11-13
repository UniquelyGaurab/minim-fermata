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
- Minim 5.9.0 (the prep release) shipped the `fermata_events` table, and Fermata now imports it: those rows keep their own ids, and a play that is in both that table and the old `plays` table counts once (see [Importing Minim's old tables](#importing-minims-old-tables)).

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
  core/      pure Kotlin/JVM: the Fermata facade tying the four together; the Minim importer;
             sync/ = backups, restore and multi-phone merge over any folder (FolderBackups)
  android/   Android library (minSdk 28): SqliteEventStore, MinimDatabaseReader, and FermataBackups
             (SAF folder, daily WorkManager job, settings-free state in SharedPreferences)
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

Every event carries its context: **timestamp, hour, day of week, session id, previous track id and source** (radio, search, library, playlist, discover, tape, queue, import, unknown). Session order is what lets Fermata learn "after A, you usually keep B".

**Session ids are opaque strings.** Fermata only compares them for equality and never parses them. When Fermata keeps the session itself it makes ids like `3f9a0c5e1b7d2468.40` (device id plus the counter of the event that opened the session), but Minim 5.9.0 wrote random UUIDs, and those are imported as they are. A new session starts after 30 minutes or more of nothing playing, the same rule 5.9.0 used.

A skip is its own event next to the play: when you move on from a song, the log gets a `play` with the time heard (possibly a few seconds) and a `skip` with where you left it. The skip has the same previous track as the play it ends. A `play` with 0 ms heard is valid. It tells Fermata the track was there, but it teaches the taste model nothing.

An event type the running version does not know (written by a newer version on another phone) is kept verbatim and passed on, never dropped.

### Event ids

Every event has a unique id: **`<device id>:<counter>`**. The device id is 16 random hex characters made once per install (never derived from hardware or an account); the counter starts at 1 and goes up by one per event on that device. Two devices can never write the same id.

Rows from Minim 5.9.0's `fermata_events` table are already real events: each becomes **`<device_id>:<row id>`**. The row id is the table's `AUTOINCREMENT` key, so it is never reused, and the device id is the 16-character id 5.9.0 stored on every row. A database restored onto a new phone keeps the old phone's id on its old rows, and the new phone's rows get the new id.

History imported from Minim's old tables or from the Firestore copy uses the pseudo-device `legacy` with a counter hashed from a stable key (for example `play|<event_id>`, `tape|<video id>|<played at>` or `like|<track id>`). The keys use values that are the same on every phone. A play's `event_id` is the sync id Minim gives each play, and Firestore carries it too. A row id would not work, because it differs from phone to phone. Two phones importing the same synced history produce the same ids, so a later merge does not count anything twice.

**Ids an app knows are taken.** `reserveCountersThrough(n)` makes sure `record` never hands out an id of this phone at or below `n`, and never moves the counter back. Minim 5.10.0 calls it when it opens its database, with the highest 5.9.0 `fermata_events` row id (and the kept copy's log after a rollback): a like recorded before the one-time import would otherwise take `<device>:<row id>` of an old play, and the import would then drop that play as already seen.

**Replaced events.** An event can name an older event it stands in for (`replaces` in its context, `"replaces"` in JSON Lines). Both stay in the log, so merges are still a plain union. Projections and the taste model skip the replaced one (`EventLog.effective`). That is how a 5.9.0 play and the legacy copy of the same play count once, on every phone and in whatever order they meet.

### Merging and conflicts

- **Merge = union of logs, deduplicated by id.** It is commutative, associative and idempotent: phones converge whatever order they exchange events in, and receiving the same events twice changes nothing.
- **Canonical order:** timestamp, then device id, then counter. Every projection replays events in this order.
- **Conflicts resolve last-writer-wins** because of that order: if one phone likes a song and another unlikes it, the later one wins; on an exact timestamp tie, the event from the device id that sorts last wins. Every phone computes the same result.
- **Clock rule:** a new event's timestamp is never earlier than the newest event already in the local log (+1 ms). So an action taken after seeing another phone's event always sorts after it, even if this phone's clock is behind. Hour and day of week still come from the real wall clock. Imported history (5.9.0 rows, old tables, the Firestore copy) keeps the plain phone-clock times it was written with, which is fine for history. **Once Minim writes through Fermata, every new event must go through `Fermata.record` so it follows this rule.** Minim must not stamp events itself with `System.currentTimeMillis()` the way 5.9.0 did. *(Minim 5.10.0 review fix: likes, "not interested" and playlist edits had kept the phone's clock for the time the screens' tables store, so after a two-way merge a phone whose clock ran ahead disagreed with the other about which change was newest. They now take the time from the recorded event, and a state the tables still need recorded again is no longer skipped because this phone wrote the same state before.)*
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
- **Minim 5.10.0 (branch `fermata-integration`) keeps the log inside its own library database instead** (`SqliteEventStore.inDatabase`), so an event and the screens' tables it changes are written in one SQLite transaction: a kill leaves both or neither. Minim takes `Fermata.writeLock` for the whole transaction, records events just before it commits, and calls `rolledBack()` if it rolls back.
- **Merges, imports and restores are both-or-neither too.** `Fermata.setApplier` sets the code that writes a merge, import or restore into the app's own tables. It runs inside Fermata's lock and inside the store transaction that adds the events (`EventStore.inTransaction`, a real SQLite transaction in `SqliteEventStore`), after they are added. If it throws, the merge, import or restore throws and nothing of it is kept, so the next one brings the same events again. Listeners still run after the commit, for screens to reload.
- **Rollback-safe copy:** 5.10.0 copies the 5.9.1 database (`minim_library.db`) to `minim_library_510.db` on first launch, checks it (`PRAGMA integrity_check`) and only ever writes the copy, so going back to 5.9.1 still works. If 5.9.1 is used after the copy and 5.10.0 is installed again, the old copy is kept as `minim_library_510_previous.db`, the newer 5.9.1 file is copied again, the old copy's log goes into the new log *before* the import (so this phone's counter continues past every id it already used), the import then records what 5.9.1 changed, and everything is folded into the tables.
- **The import records library state at the time it changed (Minim 5.10.0 second review).** Likes, "not interested" and playlists imported from the 5.9.1 tables go into the log at the time each change was made (`recordAt`), the same time the screens compare, not at the time of the upgrade. Stamped at the upgrade, an old 5.9.1 like sorted after a newer unlike made on another phone: after the first two-way merge both logs said "liked" while the unliking phone's screen said "not liked", until a later merge re-recorded it. Playlists are recorded as the header at its own time and the songs grouped by their own times. Plays and listening time are unchanged.
- **Low storage on the first upgrade (Minim):** if the copy can't be made, 5.10.0 opens the 5.9.1 file itself, shows a one-time note that going back to an older Minim isn't safe until there's space, and tries the copy again on every start.
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
- **Recommendation aggregates** (Minim 6.1): per track, when it was last heard, its newest counted plays, its early-skip streak and a long-memory "loved" score; per artist, counted plays and recent early skips; which tracks belong to which artist. All updated per event, so a request never scans the log.

## Backups and sync, without a server

App-private storage is wiped when the app is uninstalled, so backups go to a folder the user picks.

### Where

- The Storage Access Framework folder picker (`ACTION_OPEN_DOCUMENT_TREE`). The user picks a folder once, for example `Documents/Minim`, and Minim keeps a persistable permission to write there.
- Works on Android 9 through current versions, needs **no storage permission**, and the files **survive an uninstall**.
- The picker also offers cloud providers (Google Drive, Nextcloud, Dropbox) and any local folder, including a Syncthing folder.
- Plain `Downloads` files are not a safe bet: on Android 11 and newer, a reinstalled app can't read the files its old install wrote there.

### What

Since Minim 6.2 each phone keeps a **chain** of files it writes once and never changes (format 2, below). Before 6.2 it kept one pair, `minim-<device>-latest.minim` and `minim-<device>-previous.minim`, each the whole log (format 1, further below). Nothing writes format 1 any more, but it is read, restored and merged for good: a phone still on 6.1.1 or older keeps writing it, and a phone that updates keeps its own pair until its chain has a restore point of its own, then deletes it. A phone only ever writes and deletes its own files, so phones sharing a synced folder (Syncthing and the like) never write the same file. (The first Android stub named files by day, `minim-backup-2026-11-11.minim`; those are still listed and can be restored, but are never written or deleted.)

**Phones on 6.1.1 or older can't read format 2.** The new names don't match theirs, so they neither list, restore nor merge a 6.2 phone's files. There is no transition copy in format 1: update every phone that shares a folder.

### Backup files, format 2 (Minim 6.2)

Files, for a phone with device id `<device>`:

- `minim-<device>-g<gen>-base.fermata`: the whole log when generation `<gen>` started (at least 3 digits: `g001`).
- `minim-<device>-g<gen>-p<seq>.fermata`: a **piece**, the events of real devices logged after piece `<seq> - 1` (or the base), from `p0001`. The backup after one new play writes one piece of about a kilobyte.
- `minim-<device>-<time>-<nonce>.tmp`: a file being written. Readers ignore it; the phone that wrote it deletes it on its next run.

Each file:

```
"FERMATA" 0x02   8 bytes: magic and format number
header length    4 bytes, big-endian
header           UTF-8 JSON, readable in any text viewer
body             codec c1: the events, DEFLATE-compressed once
```

The header: `format`, `kind` (`base` or `piece`), `deviceId`, `gen`, `seq`, `createdAt`, `fermataVersion`, `appVersion`, `codec` (`c1`), `events` (in this file), `chainCount` (in the chain up to here), `from` (the version vector a piece starts after; empty for a base), `heads` (the version vector after it), `legacyCount` and `legacyDigest` (legacy events in the chain, which have no counter, so the vector can't show them), `clearedBefore` (the log's clear point), `baseCreatedAt`, `restorePoint` (`{gen, seq, at}`, below), and `bodyBytes` and `bodyCrc32`. Everything "is this new?" needs is in the header, so a merge with nothing new reads only headers.

The body, codec `c1` (`CompactCodec`), is lossless over the JSON-lines events, every body type, unknown ones (Minim's own writes) included; nothing is summarised or dropped. Each event is walked as its JSON tree and every value goes to a column named by its kind and key, so like values sit together. Each distinct string is written once and is a small number after that; text Minim joins with U+001F (song info inside its own writes) is split first so its parts share those strings. Every `track` object goes into a song table once and events point to it. Event times are deltas from the event before, counters are deltas per phone, and numbers that look like times are stored relative to the event's own time. The columns are concatenated and DEFLATE-compressed once.

Checks on reading: the magic; a format newer than this reader (refused with a clear message, `TOO_NEW`); the header's size and fields; the body's length and CRC-32 (a file cut short or damaged); that the body decodes to exactly `events` events; that a piece holds only device events past `from`; and that the name matches the header's device, gen and seq. A chain is read only as a whole: each piece's `from` must equal the `heads` before it, `chainCount` must add up, and `clearedBefore` and `legacyDigest` must not change.

Sizes, on a generated year of listening (`LeanBackupsTest.bench`):

| History | Format 1 | Format 2 | Format 2, with 6.2's listening saves |
|---|---|---|---|
| 5,228 events | 286 KB (54.7 B/event) | 102 KB (19.5 B/event) | 93 KB (2,057 events) |
| 10,023 events | 587 KB (58.6 B/event) | 183 KB (18.3 B/event) | 168 KB (3,855 events) |
| 50,036 events | 3.41 MB (68.2 B/event) | 853 KB (17.0 B/event) | 796 KB (18,930 events) |

The last column is the same listening with Minim 6.2's listening-time saves (once a minute instead of every 5 s): 61% fewer events and about 9% fewer bytes, since the 5-second saves already compressed well. The backup after one new play writes a piece of about 1,025 bytes at every size, in 3 to 10 ms on a desktop; 6.1.1 rewrote the whole history each time (238 KB to 2.96 MB for the same three histories). A merge on open that finds one new piece takes 1 to 5 ms, and 0.2 ms when nothing is new.

**The chain, step by step** (`FolderBackups.backUp`, one at a time, as before):

1. Delete this phone's own leftover temp files.
2. Find this phone's chain: the newest generation with a base, up to its last file that reads.
3. Under the log's lock, read the version vector, event count, clear point, and the device events past the chain's `heads`.
4. Nothing new: write nothing. Otherwise write a **piece** when the chain continues (same clear point, and only device events added since: the count and vector agree), no stray file of this generation lies past its tip, and combining is not due. Else write a **new base** holding the whole log, generation `max(gen in the folder, last gen written) + 1`, so a name is never used twice. Combining is due at 32 pieces, or when the pieces add up to half the base and at least 64 KB. Merged-in legacy events, a damaged file of this phone's chain and a clear also start a new base.
5. Write the temp file (synced before close), read it back in full and check it against the header and event count. On any failure delete it and stop; the old files are untouched (`VERIFY_FAILED`).
6. Rename it to its final name. The rename is the commit. A provider that answers with another name is a failure (`RENAME_FAILED`) and the temp file is removed.
7. Only now delete what nothing points to: other generations, pieces past the tip, files past the restore point in its generation, and (once the chain has a restore point) the format-1 pair from before 6.2. A kill here leaves extra files that the next run deletes.

So a kill at any step leaves the chain as it was or as it is after, and both restore. `LeanBackupsTest` kills a piece, a combine and the backup after a clear at every create, write, rename and delete.

**The restore point.** Each header names a point in the chain to restore to: the "previous" backup. It moves on at most once a day, to the chain as it stood before that backup, and the files it needs are kept, across a combine too (the old generation's files up to it stay until it moves on). After a clear history (`replacePrevious`, or a clear merged from another phone, seen as a new clear point) the next file is a new base that is its own restore point, and every older file of this phone is deleted, so no file keeps the cleared history. When it last moved is kept in `previous_rotated_at` as before, with the same clock rules.

**Restore** of a format-2 backup reads the base and every piece up to the chosen point, checks them all, then restores all or nothing. A missing or damaged piece means that point can't be restored (`NOT_FOUND`, `DAMAGED`), nothing changes, and the list offers the newest point before the gap.

**Merge** of another phone's chain uses its newest generation whose base reads (an older one while a new base is still syncing). Per phone, `fermata_backup` keeps the chain point merged last (`chain:<device>` = `gen:seq`). A merge on open starts after it, reads each new file's header, and reads the body only when the header's `heads` (and for a base its legacy digest) hold something this phone lacks. A missing, half-synced or damaged piece stops that phone's merge there and is reported; the next merge carries on from it. If no generation of a phone reads, its format-1 files are used, if any.

### Event exchange (for phone-to-phone sync, Minim 6.3)

`EventExchange(fermata)`: `state()` gives a `SyncState` (device id, version vector, legacy count and digest; `encode()` is a few lines of text), `missingFor(peerState)` gives the events that phone lacks (device events past its vector, plus the legacy events when the digests differ), `pack`/`unpack` use the c1 body codec, and `receive(events)` merges. Merging is by event id, so an interrupted or repeated exchange is safe to run again. Nothing in 6.2 calls it.

### Backup files, format 1 (Minim 5.10 to 6.1.1)

One pair of files per phone: `minim-3f9a0c5e1b7d2468-latest.minim` and `minim-3f9a0c5e1b7d2468-previous.minim`. Each file is a zip holding:

- `manifest.json`: format version, created time, device id, Fermata and app versions, event count, the version vector (highest counter per device), and a SHA-256 checksum per file. Since the automatic backups (Minim 5.10.0 branch) also `legacyCount` and `legacyDigest`: how many legacy events (imported from Minim's old tables) the file holds and a SHA-256 of their sorted ids. Legacy ids are hashes with no counter, so the version vector can't show them; with these two a merge can still tell from the manifest alone that a file has nothing new. Still format 1: older readers ignore the two fields, and a manifest without them is simply read in full when merged.
- `fermata-events.jsonl.gz`: the full event log as gzipped JSON Lines, one event per line

Since the whole library is events, the log is the whole backup. A model snapshot (`fermata-model.json.gz`) may be added later as an optional speed-up; readers ignore files they do not know, and refuse a format newer than they understand with a clear message.

One line looks like this:

```json
{"id":"3f9a0c5e1b7d2468:42","ts":1794405000000,"hour":19,"dow":3,"session":"3f9a0c5e1b7d2468.40","prev":"dQw4w9WgXcQ","source":"radio","type":"play","data":{"track":{"id":"kJQP7kiw5Fk","title":"Despacito","artist":"Luis Fonsi","durationMs":229000},"listenedMs":182000}}
```

An imported 5.9.0 play that stands in for the legacy copy of the same play also carries `"replaces":"legacy:<n>"`, and its session is a UUID. Readers that do not know a field ignore it.

JSON Lines rather than a raw database copy, because:

- Android 9 ships SQLite 3.22, which has no `VACUUM INTO` for safe live copies
- It's readable and open: anyone can inspect their own history, or build on it
- Restoring works across schema versions

**Never in the backup:** the Gemini API key or anything else secret. The format has no place for settings at all in version 1; if settings are added later, secrets stay out.

### When and how

- WorkManager unique periodic work (`fermata-daily-backup`), once a day, only while charging with battery not low. The job first merges the other phones' backups, then backs up only if something changed, so each backup carries what it merged. Picking a folder schedules it.
- One-time backups the app starts itself (`enqueueBackupOnce`, unique work `fermata-backup-once`): WorkManager runs them even if the app is closed or killed right after. Minim uses it at startup when there has been no backup for a day (the daily job only runs while charging), and right after a history clear with `replacePrevious`, which replaces a plain request still queued so the replacement is never lost.
- A "Back up now" button in Settings → Storage (`backUpNow()` blocking, or `enqueueBackUpNow()` as unique one-time work, KEEP, expedited), with the last backup time, or the error, shown from `status()`. Back up now always writes.
- **Automatic backups (Minim 5.10.0 branch).** `enqueueMergeOnOpen()` when the app comes to the foreground (unique work `fermata-merge-on-open`, KEEP, expedited): a manifest-first merge (below), then `FermataHolder.onFermataChanged()` if it added events. `enqueueBackupOnBackground()` when the app goes to the background (unique work `fermata-backup-on-close`, REPLACE so a newer close takes the place of one still waiting, expedited): `beforeBackup()`, then `backUpIfChanged()`. Both do nothing without a folder and only queue work, so they are safe on the main thread. Expedited jobs fall back to ordinary one-time work when the app is out of expedited quota (`RUN_AS_NON_EXPEDITED_WORK_REQUEST`); on Android 9 to 11 they are queued as ordinary one-time work, which those versions run right away (an expedited job there would need a foreground notification). WorkManager keeps them across the app being killed.
- **Only when something changed.** `backUpIfChanged()` reads the header of this phone's newest chain file (6.2; before, the `latest` manifest) and compares its version vector, event count and clear point with the log's (both read under the log's lock; the vector counts only events still in the log, not counters kept from pruned ones). Equal: nothing is written, and the result is a success with `written = false`, recorded as a backup that worked, because the chain is current. Any new event changes one of them (a new or merged event raises a counter or the count, a clear prunes events and adds its own, an imported legacy event adds to the count). Otherwise it backs up as below: usually one small piece.
- **One at a time.** Every backup, restore and merge in the process holds one lock (in `FolderBackups`, shared by every instance), so the on-close job, the daily job, Back up now and the rest wait for each other whichever thread or job runs them. WorkManager runs jobs in the app's process, so the lock covers them all.
- Each backup, step by step: see "The chain, step by step" above (6.2). Before 6.2 each backup rewrote the whole log as `latest` and renamed the old one to `previous` (or parked and deleted it); that code is gone, and `Format1Backups` in the tests keeps it as it shipped, to make the folders of phones that have not updated.
- **The daily `previous`** is the chain's restore point (above): it moves on at most once a day. When it last moved is kept in `fermata_backup` (`previous_rotated_at`, this phone's clock). Clock oddities: a clock set back a little (under an hour, a time sync) changes nothing; a clock now more than an hour before the last move (set back, or it was ahead then) moves it on once and the day counts from the new time. So a wrong clock costs at most one extra move, and it never moves on every backup or never again. A backup after a clear (`replacePrevious`, or a clear merged in) makes the new base the restore point.
- SAF has no atomic "rename over"; a chain never needs one, since a file's name is new when it is renamed into place. A file is created as `application/octet-stream` so providers keep the exact name; a provider that answers with another name (`...-p0003 (1).fermata`) is treated as a failure and its file removed.
- The highest generation this phone wrote is kept in `fermata_backup` (`last_gen`), so a new base never takes an old name even if the folder lost all of this phone's files.
- The result goes into `status()`: last attempt, last success, event count and size (since 6.2, the bytes a restore of the chain reads; the result also has `bytes`, the file this backup wrote), and the last error (`NO_FOLDER`, `FOLDER_ACCESS_LOST`, `WRITE_FAILED`, `VERIFY_FAILED`, `RENAME_FAILED`) with a message. A merge also saves what it left out: `lastMergeError` when the folder could not be read at all, and `lastMergeProblemCount` with the first `lastMergeProblem` ("file: reason") for other phones' files it could not merge (damaged, or the app's applier failed). Minim shows it as a second line under the backup status and in the note after Back up now. Lost folder permission and no folder are not retried (the user must act); other failures retry twice with WorkManager's backoff, then wait for the next day.
- What is remembered about backups (status, which backup of each other phone was merged last, whether the user said no to the restore offer) lives in SharedPreferences `fermata_backup`, which describes this install: Minim's Auto Backup rules must exclude it, like the device id.

### Restore

1. Fresh install, empty library: `restoreOffer()` says whether to offer "Restore from a backup" (`PickFolder`, `NoBackups`, or `Available` with the list). It is not offered once the library has events, or after `dismissRestoreOffer()`.
2. The user picks the same folder. `listBackups()` lists every phone's backups, newest first, read from the headers or manifests only: for a 6.2 phone its newest point and its restore point, for an older phone its `latest` and `previous`; a file that cannot be read, or is from a newer format, is listed with the reason and cannot be chosen.
3. The chosen backup is read and checked in full (for 6.2, the base and every piece up to it; for format 1, the manifest, checksums and every line) **before anything is touched**. Damaged, half-synced, gone or too new: nothing changes (`DAMAGED`, `NOT_FOUND`, `TOO_NEW`).
4. `Fermata.restore` replays the log as it would be after the restore in memory first, then stores the new events in one SQLite transaction, then rebuilds the library and the taste model from the log. If any step fails, the log, projections, counter and clock are as they were (`RESTORE_FAILED`).
5. Restored events keep their original ids. This phone keeps its own device id (stored outside backups); if the backup holds events with this phone's id, its counter continues past the highest of them, and its clock past the newest event.

**How Minim shows it (5.10.0):** Settings → Storage has Backup folder (the system folder picker), Back up now (merges the other phones' files first, then backs up), the last backup's time and event count or why it failed, and Restore (every phone's backups, newest first). Because restore is a merge, the restore sheet says "merged into what's already on this phone" when the phone already has likes, playlists or plays, and "comes back from the backup" on an empty phone. A fresh install with no library offers "Restore your library?" once after the welcome (pick a folder, or restore the newest backup); either answer is remembered in `minim_backup_offer.xml`, which Android's own backups leave out.

Backups from older Fermata versions restore the same way: fields and event types a version does not know are kept as they are, and the projections are always rebuilt from the events, never read from the file. Because restore is a merge, restoring into a library that already has history is safe too: nothing is replaced, nothing is counted twice.

### Several phones

Point every phone at the same folder (a Google Drive, Nextcloud or Dropbox folder through the SAF picker, or a Syncthing folder). Each phone writes its own chain (6.2; before, its own pair) and, when it runs (`merge()`, and before every daily backup), reads the other phones' backups and merges any events it has not seen. Because merging is conflict-free, it does not matter which phone syncs first or how often.

- **Only new events.** Merging is by event id, so repeating it adds nothing. The legacy and 5.9.0 rules travel inside the events (`replaces`, fixed legacy ids), so a play counts once whichever phones imported which copy, in whatever order.
- **Unchanged backups are skipped, and so are backups with nothing new.** For a 6.2 phone see "Merge" above: only files after the last merged chain point, and only their headers when nothing is new. For a phone on 6.1.1 or older, a merge reads each phone's manifest first. The file is not read in full when the checksum of its event log is the one merged last time, or when its manifest shows nothing new: every counter in its version vector is one this phone's log already reaches (a phone's events always reach another log as a whole prefix of its counters), and its legacy events are none, or the same set (`legacyDigest`) as merged from that phone last time. So opening the app with nothing new reads only the manifests. A manifest from an older Fermata (no legacy fields), from a newer format, or one that can't be read means a full read, which checks the file and reports it, falling back to that phone's `previous` as below. What was merged per phone (checksum and legacy digest) is kept in `fermata_backup`.
- **Phones writing at the same time.** Each phone touches only its own files; temp files have another extension and are ignored by readers. A format-1 file that disappears while being read (its phone was rotating it) is looked up again once; a chain file that disappears (its phone combined) ends that phone's merge, and the next merge reads the new base.
- **Partial or corrupt files.** Every file is checked in full before any event from it is used. A 6.2 piece that fails stops that phone's merge there, reported, and is tried again next time. When a format-1 `latest` fails (still syncing, damaged), its `previous` is used instead.
- The result says how many phones, files merged, unchanged and events added, with any problem files.

Next (6.3): a direct phone-to-phone transfer over local Wi-Fi, exchanging only the missing events, on top of `EventExchange` (above).

## Leaving Firebase

Today Minim uses Firebase for optional Google sign-in sync (Firestore) and for the built-in Gemini route (Firebase AI Logic + App Check). Fermata replaces the sync; the built-in Gemini route goes away with Firebase, and the bring-your-own AI Studio key stays.

- **Bridge release:** pulls the user's Firestore data down into the local Fermata log (as `legacy` events with deterministic ids), writes a backup, signs the user out and deletes the cloud copy.
- **Next release:** removes Firebase entirely (dependencies, google-services plugin, sign-in UI).

### Importing Minim's old tables

When Fermata is first wired into Minim, `Fermata.importMinim(MinimDatabaseReader.read(libraryDb))` imports Minim's history in one go (`MinimImport`, pure and deterministic, so running it twice or on two phones adds nothing):

- **`fermata_events` (Minim 5.9.0, the richest history)** → one event per row, id `<device_id>:<id>`. Columns map straight across: `ts`, `hour`, `dow` (ISO, 1 = Monday), `session_id`, `prev_track_id` and `source` go into the context. `play` → `play` (listened ms), `skip` → `skip` (position ms), `queued` → `queued`, `tape_play` → `tape_play` (listened ms, `speed_pct`, 100 when missing). Titles, artists and durations the row lacks come from `tracks`. Rows are taken as they are:
  - A `play` row's time is when the track **started**, and its heard time is the last save. 5.9.0 inserted the row at track start and updated it in place, so the row of a song playing when the app was killed is short by a few seconds at most.
  - Each repeat-one loop has its own row and stays its own play.
  - A source that is missing or not one of Fermata's keys is `unknown`. Artist pages, songs Gemini added and anything queued before 5.9.0 are already `unknown` in the table.
  - Missing hour or weekday is worked out from `ts`. Empty session or previous-track values become null.
  - A row without a device id gets a legacy id from its content. A row with no `ts`, a blank track id or an unknown type is skipped and counted in the result.
- **`plays(id, track_id, played_at, listened_ms, event_id)`** → `play` events with legacy key `play|<event_id>` (rows without one get the id Minim would give them, `base36(played_at)_track_id`), metadata from `tracks`.
- **No double counting.** Since 5.9.0, a play can be in both tables: the `fermata_events` row opened at track start, and the `plays` row written at the 30-second mark. A `play` row and an old play of the same track are the same play when the old play's time falls in the window when that row was open: from its start (less 5 seconds of slack) to the start of the next `play`/`tape_play` row from the same phone, capped at 12 hours. Within each track the earliest such old play is matched, one to one, so repeat-one loops pair up loop by loop. Only the 5.9.0 event is written. It gets `replaces = legacy("play|<event_id>")` and the larger of the two heard times (the old table may have saved a few seconds more before a kill). That legacy copy is then skipped wherever it turns up: another phone that only has the old table, or the Firestore copy. `tape_plays` pair with `tape_play` rows the same way (key `tape|<video_id>|<played_at>`). Old plays with no matching row (before 5.9.0, synced from another phone, or written while a row's insert failed) stay legacy plays.
- `tracks.is_liked / liked_at` → `like` events at `liked_at`
- `playlists` + `playlist_tracks` → `playlist_create` + `playlist_add` in `position` order. Minim's playlist names are unique, so the imported playlist id is derived from the name, and two phones importing the same playlist agree on it
- `not_interested` → `not_interested` events at `added_at`
- `tape_plays` → `tape_play` events (listened 30 s, the point at which Minim saved them), except those matched above
- `listen_time` and `tape_listen_time` (hourly totals) are recomputed from plays where the plays cover the same period; older totals that have no plays behind them are kept as a one-off summary (to be designed in step 4)

Rules for the integration release:

- **The release that imports `fermata_events` also stops writing it**, and imports only after the 5.9.0 logger is gone. The import takes each row as it is when it runs, and a later update to the same row id would never be read again.
- After the import, Fermata's counter for this device moves past the highest row id, so its own events never reuse a 5.9.0 id. This happens in `importMinim` and `merge`, and again on restart from the store's heads.
- Old tables stay untouched for one release, so a rollback is possible.
- Firestore plays brought down by the bridge release use the same `play|<event_id>` keys, so they land on the same legacy ids and are skipped where a 5.9.0 row replaced them.
- Minim's "Clear listening history" empties `plays` and `tape_plays` but not `fermata_events` in 5.9.0. Until that is settled, an import would bring the cleared plays back from `fermata_events`.

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

## Recommendations (Minim 6.1)

What Minim asks Fermata for, and how it stays fast. Code: `recs` (`Recommender`, `TasteModel`, `RecsConfig`) and `core` (`FermataRecs`, reached as `fermata.recs`).

**Requests.** All take a time (`now`, Fermata's clock by default, so tests are deterministic) and none blocks:

- `recs.radio(candidates)`: a radio batch from YouTube, reordered by the blend above. Leaves out tracks the person said "not interested" to (track or artist), tracks skipped early twice in a row (until heard past the early point again, or 60 days pass), and tracks heard in the last 3 hours. If that leaves fewer than 10, recently heard tracks come back at the end in YouTube's order. Returns `Reranked(picks, dropped)`, each drop with its reason.
- `recs.rank(candidates)`: the same for a shelf, without the recent-play filter.
- `recs.onRepeat()`: tracks counted 3 or more times in the last 7 days, most plays first.
- `recs.forgottenFavourites()`: tracks liked, or counted 5 or more times, and not heard for 30 days, ordered by a slow-fading "loved" score (half-life 180 days). A like newer than 30 days does not count yet.
- `recs.becauseYouLike()`: top artists by taste (liked, or 5 or more counted plays), each with the person's own best tracks of that artist as seeds for a mix.
- `recs.state()`: `NOT_READY`, `COLD_START` or `READY`; `recs.heardRecently(id)`.

**Cold start.** Fewer than 30 counted plays, or fewer than 10 distinct tracks counted: `radio` and `rank` return null and the shelves are empty, which means "keep your current behaviour". Past that, the blend uses the model's confidence but at least 0.5, so likes and skips show from the first days rather than after weeks (confidence alone stays near 0 for the first days).

**Skips.** A skip is early before 30 seconds, or before 20% of a track whose length is known, whichever is later. An early skip adds −0.6 to the track (a late one −0.2) and nothing to the artist. Repeat skips touch the artist lightly: once an artist has 3 early skips within 14 days, each further early skip in that window (the third included) adds −0.4 to the artist, of which half reaches each of its tracks' scores.

**Plays shorter than the early-skip point (Minim 6.1.1 review).** Minim writes a play row for every listen, an early skip included, then the skip. A play that stopped before the early-skip point (or, for a song shorter than that point, before 90% of it) counts nowhere in recs: not as a play or a partial play, not for "last heard", On repeat, Forgotten favourites or cold start. So an early skip costs its full −0.6, and three early skips of one artist come to −0.4. In 6.1 the play row added +0.2 to the song and +0.1 to the artist, so a skip cost only −0.4 and three came to about −0.1.

**"Not interested" and undo (Minim 6.1.1 review).**
- "Not interested" in a song adds −6 to that song only, like a skip: nothing to its artist. In 6.1 half of it hit the artist, so after two likes of an artist, hiding one of their songs took the artist from about +2.9 to about 0. "Not interested" in an artist still adds −6 to the artist.
- An unlike or "interested" takes back what is left of the original like or "not interested" after decay, not the full weight. The model keeps when each like and "not interested" happened and what went to the track and the artist, and the undo subtracts those amounts at that time, so the score comes back to exactly where it would be without them. In 6.1, unliking a 60-day-old like left the song at −2.25, and undoing a 60-day-old "not interested" left it at +4.5. A "just a phase" or reset between the two scales what the undo takes back the same way.
- The result depends only on the log, so it is the same live, after a rebuild, after a merge from another phone (one phone likes, the other unlikes later) and after a restore.

**Speed.**
- The model is built once per process by `recs.warmUp()`, on a background thread, from the taste event types only (`EventStore.ofTypes`; the SQLite store reads the `type` column first and does not decode the rest). Building does not hold Fermata's lock: events recorded meanwhile are kept aside and applied after the read.
- Every event this phone records is applied to the model as it is recorded (about a microsecond).
- Until the model is built, every request returns null: the app shows what it has now.
- A merge or import that brings taste events marks the model stale. It keeps serving and the next `warmUp()` rebuilds it. A history clear, restore or rolled-back transaction drops it, so cleared plays are never used.
- Ranking a candidate list is linear in its length; the shelves walk the model's tracks once. Timings are in the test report (`RecsBenchmarkTest`, `SqliteEventStoreTest`).

**Tunable numbers** (`RecsConfig`): `earlySkipMs` 30 000, `earlySkipFraction` 0.2, `recentWindowMs` 3 hours, `radioMinKeep` 10, `dropAfterEarlySkips` 2, `skipMemoryDays` 60, `artistSkipsBeforePenalty` 3, `artistSkipWindowDays` 14, `coldStartMinPlays` 30, `coldStartMinTracks` 10, `minWarmConfidence` 0.5, `onRepeatDays` 7, `onRepeatMinPlays` 3, `forgottenAfterDays` 30, `forgottenMinPlays` 5, `longHalfLifeDays` 180, `becauseMinArtistPlays` 5. Weights (`Weights`): counted play +1, finished +0.5 more, partial play +0.2 (only for a play past the early-skip point but short of a counted play, which the defaults never give), early skip −0.6, late skip −0.2, artist repeat skip −0.4, queued +1.5, like +3, not interested −6 (to the track or the artist named, never shared), over it −8, share to the artist 0.5. Scores halve every 30 days.

No backup format change: everything here is derived from the log.

## The API

```kotlin
val fermata = Fermata(store = SqliteEventStore(context), deviceId = savedDeviceId)

// The library
fermata.record(EventBody.Like(Subject.Track(track)))
fermata.record(EventBody.PlaylistAdd(playlistId, track))
val library: LibraryState = fermata.library()   // likes, playlists, history, stats

// Listening (session and previous track are filled in when not given)
fermata.record(EventBody.Play(track, listenedMs = 182_000), source = Source.RADIO)

// Recommendations (see "Recommendations (Minim 6.1)")
fermata.recs.warmUp()                            // blocking, background thread, once per process and after merges
val radio: Reranked? = fermata.recs.radio(youtubeRadio)   // null: not ready or cold start, keep YouTube's order
val shelf: Reranked? = fermata.recs.rank(shelfTracks)
fermata.recs.onRepeat(); fermata.recs.forgottenFavourites(); fermata.recs.becauseYouLike()
val picks: List<Pick> = fermata.rank(candidates)  // older: rebuilds from the whole log, no filters
// Pick(track, score, reason = "You've played this 4 times")
val taste: TasteView = fermata.taste()           // for the "your taste" screen
fermata.edit(TasteEditKind.OVER_IT, Subject.Artist("Artist"))

// Backup, restore, sync (low level)
fermata.export(out)                              // a .minim backup
fermata.import(input)                            // merge a backup in
fermata.merge(fermata2.eventsAfter(fermata.heads()))
fermata.restore(events)                          // all or nothing, then rebuild

// Backups for the app (android module). Blocking calls: run them off the main thread.
val backups = FermataBackups(context, fermata, appVersion = BuildConfig.VERSION_NAME)
backups.setFolder(treeUri)        // from ACTION_OPEN_DOCUMENT_TREE; keeps the permission, schedules daily
backups.folder(); backups.hasFolderAccess(); backups.clearFolder()
backups.backUpNow()               // BackupResult.Success / Failure(error, message)
backups.enqueueBackUpNow()        // the same as background work
backups.status()                  // BackupStatus: last success, last error, last merge
backups.listBackups()             // List<BackupInfo>, any phone's, newest first
backups.restore(info)             // RestoreResult
backups.merge()                   // MergeResult
backups.restoreOffer()            // NotNeeded / PickFolder / NoBackups / Available(backups)
backups.dismissRestoreOffer()
// The Application implements FermataHolder so the daily BackupWorker can reach Fermata.

// One-off import of Minim's history (5.9.0 fermata_events + the old tables), safe to repeat
fermata.importMinim(MinimDatabaseReader.read(libraryDb.readableDatabase))
// or one old row at a time, with a stable key
fermata.importLegacy(EventBody.Play(track, 90_000), playedAt, legacyKey = "play|$eventId")
```

## Build order

1. **Minim prep release:** start logging skips, session id, previous track and source in Minim, so richer history is already waiting when Fermata ships. *(Shipped in Minim 5.9.0 as the `fermata_events` table; Fermata's importer for it is in `core` (`MinimImport`) and `android` (`MinimDatabaseReader`).)*
2. **Fermata core:** events, ids, merge, library projection, backup format, decayed taste scores and confidence, with JVM tests. *(First commit.)*
3. **Fermata Android module:** SQLite event store, SAF daily backups and restore. *(Done in 0.2.0: `FolderBackups` in `core`, `FermataBackups` in `android`, with temp-verify-rename backups, restore, multi-phone merge and the restore offer.)*
4. **Integrate into Minim:** Minim's tables become projections; the old tables are imported as historical events. *(In progress on Minim's `fermata-integration` branch, 5.10.0: every write goes through the log in the same transaction, the one-time import, playback events, merges and restores written back into the tables, Settings → Storage, the fresh-install offer, and clear history as a `history_clear` event. Not released.)*
5. **Bridge release off Firebase:** pull Firestore down into the log, back up, sign out, delete the cloud copy.
6. **Remove Firebase** entirely.
7. **Recommendations:** context, co-play, fatigue, eras, rediscovery, explore, reasons and the taste screen; rerank the radio queue behind a setting. Then tag v1.0 and publish through JitPack. *(First part for Minim 6.1: the radio rerank, On repeat, Forgotten favourites, Because you like X, skip signals, the recent-play filter and the cold-start check, from aggregates kept in memory. Context, co-play, fatigue, eras and explore are still to come.)*

## Open questions

- Should tapes feed the same taste model, or keep a separate one? *Dev's leaning (not final): a separate one, so long podcasts don't steer song recommendations.*
- Should backups have an optional password (they hold listening history in a folder other apps, or a cloud provider, might read)? *Dev's leaning (not final): yes, eventually, as an option. Not built in 0.2.0; the format has room for it as a new format version.*
- ~~Should Minim's "Clear listening history" also empty `fermata_events`?~~ **Decided: yes.** Minim 5.9.1 empties `fermata_events` along with `plays` and `tape_plays`, so the import never brings cleared plays back. A plain `DELETE` keeps the row ids from being reused.
- ~~"Clear listening history" once Minim writes through Fermata.~~ **Built (Fermata 0.3, Minim 5.10.0 branch):** clearing records a `history_clear` event ("everything before T"); merges, restores and imports honour it on every phone, likes, playlists and "not interested" stay, and the cleared events are pruned while the counter keeps counting up. Minim backs up right after a clear and replaces that phone's `previous` file too. Other phones' older files are not rewritten; the clear event wins when they are merged.
- ~~Open (Minim): a Clear listening history button.~~ **Built (5.10.0 review):** Settings → Storage has Clear under Cache, with a confirm sheet. Likes and playlists stay. With Firebase sign-in on, the clear also writes `history/cleared` and deletes the older day documents from Firestore; pulls skip cleared plays, and a clear pulled from Firestore is recorded in Fermata too, so folder backups carry it.
- **Open (Minim): rolling back to 5.9.1 after a clear.** 5.9.1's own file is never written by 5.10.0, so going back to 5.9.1 after clearing brings the cleared history back on that phone. If 5.10.0 is then installed again, the clear event from the older copy's log hides it again (tested in 5.10.0's review fixes: re-upgrading keeps 5.10.0's unlikes, playlist removals and clears, and only imports what 5.9.1 changed after the first import). The listening minutes from the clear's own hour stay cleared too: when the older copy imported in or after that hour, 5.9.1's rows for it are dropped, since they hold minutes from before the clear (tested in the second review). Minutes played on 5.9.1 within that same clock hour after going back are lost; what 5.10.0 played in it after the clear comes back from the log.
- **Open (Minim): listening time while Firebase sign-in is on.** Firestore already brings other phones' listening time, so while sign-in is on 5.10.0 leaves other phones' listening time out when it merges folder backups (plays, likes and playlists still merge, since they carry ids). This goes away with Firebase.
- ~~Known limit (Minim): a downgrade-then-upgrade can re-like.~~ **Fixed and tested (5.10.0 review):** a song liked before the rollback and unliked in 5.9.1 stays unliked after re-upgrading, because only what 5.9.1 changed after the first import is recorded, at the time of that change.
- How long should a phone keep reading another phone's backups after it stops writing new ones (a lost or retired phone)?
- Do settings (theme, audio quality, and so on) belong in the log or in a separate, secret-free settings file?
