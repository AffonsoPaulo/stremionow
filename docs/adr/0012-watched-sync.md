# 0012. Sync watched videos with the account in the official format

Date: 2026-11-14
Status: Accepted

## Context

"Mark as watched" (an episode, a season, a whole series, a movie) and the end of a playback record the video in the core's local `watched` table only. The account sync (`stremionow-sync`, `ffi/src/account.rs`) sends My list and the playback position, but never which videos were watched, so marks made here never reach the official apps or the user's other devices, and theirs never arrive here.

The official apps keep this on each library item's `state`, in stremio-core (read at commit `2c89236` of `Stremio/stremio-core`, branch `development`: `stremio-watched-bitfield/src/{bitfield8,watched_bitfield}.rs`, `src/types/library/library_item.rs`, `src/types/resource/meta_item.rs`, `src/models/{meta_details,player}.rs`, `src/models/ctx/update_library.rs`):

- **Series and other titles with videos**: `state.watched`, a string `{anchor video id}:{anchor length}:{bits}`. The id may contain `:` (`tt0944947:1:5`), so the last two parts are split off and the rest is the id. `bits` is standard base64, with padding, of a zlib stream (level 6, flate2 with its default pure-Rust backend) of a bit array: bit `i` is bit `i % 8` of byte `i / 8`, in `ceil(videos / 8)` bytes, one per video of the title. The anchor is the last watched video and the length its position plus one (the first video and 1 when none is, `undefined` without videos). Reading a string shifts the bits so the anchor lands where that video now is, so marks survive new episodes and specials added in front; bits shifted outside the list are dropped, and an anchor that is not in the list reads as nothing watched.
- **The video order** is the metadata's, as stremio-core prepares it: duplicate ids dropped (first kept); sorted stably with season and episode first (specials last), then the rest by release (oldest first in a series, newest first otherwise, undated last); then sorted stably again by season, episode and release, a missing value first, which puts specials and unnumbered videos first.
- **Movies**: "Mark as watched" adds 1 to `state.timesWatched`, sets `lastWatched` to now and `timeOffset` to 0; "Mark as unwatched" sets `timesWatched` to 0. A movie counts as watched while `timesWatched` is above 0. No bitfield.
- Playback past 70% of the time watched sets the episode's bit and adds 1 to `timesWatched`. An item watched without being added to the library is created as `removed: true, temp: true`.

The user approved writing marks in this format so the official apps read ours and we read theirs.

## Decision

- **Format.** `stremionow-sync` gains `watched.rs`, a port of the official encoding: `watched_order`, `decode_watched`, `encode_watched`, pure and unit-tested against the strings of the official crate's tests (`tt2934286:1:5:5:eJyTZwAAAEAAIA==`, `undefined:1:eJwDAAAAAAE=`, `tt7767422:3:8:24:eJz7//8/AAX9Av4=`), which it reproduces byte for byte. Two deliberate differences, both on reading only: an anchor that is not in the list is an error, not "nothing watched", so a string we cannot place never unmarks anything; and a string that inflates past 1 MiB (8 million videos) is refused. A `state.watched` that is not a string reads as none instead of dropping the whole item.
- **Dependencies.** `flate2` 1.1 with only the `rust_backend` feature (miniz_oxide, no C library to build for Android) and `base64` 0.23 with only `std`, pinned in the workspace `Cargo.toml`. Both were already in the tree (librqbit, hyper-util), so no new crate is downloaded or audited. They are the ones the official crate uses, so the compressed bytes match.
- **Local model.** Marks stay in the `watched` table, unchanged. A new migration (3) adds `watched_sync` (title id, the account's `state.watched` string as last read or written) and `watched_sync_videos` (title id, video id: the watched videos both sides had at the last sync). The store gains `watched_sync`, `save_watched_sync` and `clear_watched_sync`; nothing else in the library model changes.
- **Merge.** `merge_library` is unchanged: per item, the newer `_mtime` wins as a whole, a tie keeps the account's. Marks are then combined per video, three ways, against the last sync: a video changed on one side only takes that side's value; changed on both, the two agree, since a mark is yes or no; with no last sync (the first one, or a new account) a video watched on either side stays watched. When the result differs from the account's, the item is uploaded with the new `state.watched` (or `timesWatched` for a movie): the one already being uploaded, else the account's copy with `_mtime` set to now, else a new `removed: true, temp: true` item named from the metadata. The account's string and the agreed videos are saved only after the upload succeeds.
- **Metadata.** Encoding needs the title's video list, read with the same addon request and cache as the details page. Only titles whose marks changed on either side since the last sync are read, 4 at a time, at most 300 per sync, those changed here first. A title whose metadata cannot be had waits for the next sync and keeps its marks on both sides.
- **Unreadable strings.** A string that cannot be read counts as "the account did not change": local marks are kept, the string is left alone, and it is replaced only when the marks change here.
- **Interface.** Nothing new is exported. `set_watched` and the end of a playback record marks as before; the next sync sends them. The details page keeps reading marks with `watched_videos`, which now include the account's after a sync. Signing out keeps the marks and forgets what the account had.
- Titles watched without being saved are now created on the account as `removed: true, temp: true`, as the official apps do, and the progress of such an item from the account is applied here (the official `is_in_continue_watching` rule); before, ours were `removed: false` and showed in the official apps' library.

## Alternatives

- **Our own field** (a list of watched video ids under a new key). Simple and order-free, but the official apps would ignore it and their marks would never arrive.
- **Last writer wins for the whole bitfield**, as the item merge does. Simpler, but a mark made on one device would erase one made elsewhere since the last sync; the three-way merge costs one small table.
- **Union of both sides.** Never loses a mark, but an unmark could never sync.
- **Decoding when the details page opens** instead of during the sync. Fewer metadata requests on the first sign-in, but the home screen, "Continue watching" and other devices would not see the marks until each title is opened.
- **The C zlib** (`flate2` with `zlib`) or **`miniz_oxide` directly.** The first needs a C toolchain for every Android ABI; the second saves little and loses the `Read`/`Write` adapters.

## Consequences

- Marks reach the official apps and come back from them, on every platform, with no change to the interface.
- The first sync of an account with many watched series reads each one's metadata once (served from the cache afterwards); a heavy library may take a few syncs to bring every title.
- Matching the format depends on our metadata matching theirs: the same addon (Cinemeta by default) and the same video ids. Video lists that differ (another metadata addon, an addon that numbers episodes with `number` or sends them as text) can shift marks within a title; the anchor keeps the latest mark right.
- Series do not raise `timesWatched` when an episode is marked or played here, unlike the official player; only the bitfield and the movie flag are written.

## Amendments

- 2026-11-15: Signing out no longer forgets what the account had (nor the removals and ratings not sent yet): the app also signs out when a session expires, and signing in again to the same account must still send them. They are forgotten when a different account signs in (the account id is kept for that). A string that cannot be read is no longer recorded as synced, and before one is replaced because marks changed here, the title's metadata is read again without the cache. The `watched` table is now keyed by title and video (migration 5), so a video id shared by two titles counts for both.
