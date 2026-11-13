# Changelog

## 2026-11-13: lean, append-only backups (Minim 6.2.0)

- **Backup format 2.** Each phone keeps a chain of files it writes once: a base (`minim-<device>-g<gen>-base.fermata`) and small pieces (`-p<seq>.fermata`) holding only the events of real devices logged since. A readable JSON header (version vectors, counts, clear point, restore point, CRC-32), then a columnar body: a song table, one copy of each string, time deltas, one DEFLATE pass. On a generated year of listening: 102 KB for 5,228 events (19.5 bytes an event, was 286 KB and 54.7), 183 KB for 10,023 (was 587 KB) and 853 KB for 50,036 (was 3.41 MB). Lossless: every history decodes back to exactly the same events. See DESIGN.md, "Backup files, format 2".
- **The backup after one new play writes about 1 KB** (one piece), not the whole history: 3 to 10 ms on a desktop, against 238 KB to 2.96 MB rewritten before.
- **Combining.** At 32 pieces, or once the pieces reach half the base, the next backup writes a new base; the old generation is deleted only after it is in place. A kill at any step leaves a backup that restores (tested at every create, write, rename and delete). A clear history, a damaged file of this phone's chain, or merged-in legacy events also start a new base.
- **The daily restore point** is a point in the chain and moves on at most once a day, as before. After a clear it is the new base, and no file keeps the cleared history.
- **Merge on open** reads only the files after the last merged point, and only their headers when nothing is new: 1 to 5 ms for one new piece, 0.2 ms for nothing new. A half-synced or missing piece stops that phone's merge there; the next merge carries on.
- **Format 1** (`-latest.minim`, `-previous.minim`) is no longer written, but is still listed, restored and merged, from phones still on 6.1.1 and from this phone before the update. A phone's own pair is deleted once its chain has a restore point of its own. **6.1.1 and older can't read format 2.**
- **`EventExchange`**, for phone-to-phone sync in 6.3: `state()` (version vector and legacy digest), `missingFor(peer)`, `pack`/`unpack` and `receive`. Nothing calls it yet.
- `Fermata.deviceEventsAfter(heads)`: the events of real devices past a version vector, without the legacy ones.
- `BackupResult.Success.totalBytes` (the bytes a restore reads) next to `bytes` (the file this backup wrote), and `BackupState.lastGen`.
- Tests: `LeanBackupsTest` (kills, combining, merges across combining, many small pieces, damaged and missing pieces, format-1 restore and merge, clear and undo across combining, event exchange, and `bench` with `FERMATA_BENCH=1`). The old writer is kept in the tests as `Format1Backups`.

Earlier changes are in Minim's CHANGELOG.md.
