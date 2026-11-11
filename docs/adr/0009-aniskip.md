# 0009. Skip anime openings and endings with AniSkip

Date: 2026-11-11
Status: Accepted

## Context

The player offers "Skip intro" and "Up next" from a file's chapters (`Chapters` in the `player` module). Many anime releases have no chapters, so anime, where skipping the same opening every episode matters most, often gets no button.

[AniSkip](https://api.aniskip.com) is a free, crowd-sourced database of opening, ending and recap times for anime episodes, used by ani-cli, several mpv scripts and browser extensions. Its v2 API needs no key: `GET /v2/skip-times/{malId}/{episode}?types[]=op&types[]=ed&types[]=recap&types[]=mixed-op&types[]=mixed-ed&episodeLength=<seconds>`. Given the length of the file, it returns only the submissions made on a file of about the same length (in our tests, within roughly 15 s), so the times match the copy being played. It answers 404 when it has nothing.

AniSkip files episodes by MyAnimeList id. Stremio's anime addons name titles `kitsu:<id>` (the most used, Anime Kitsu), `mal:<id>` or `anilist:<id>`, and episodes `<prefix>:<id>:<episode>`, numbered within that entry. Kitsu and AniList ids need a mapping to MyAnimeList. Two public services were checked:

- **ARM** (Anime Relations Mapping, `https://arm.haglund.dev/api/v2/ids?source=kitsu&id=41370`, open source): about 300 bytes of ids. It answers `null` for an unknown id and 400 for one out of range.
- **ani.zip** (`https://api.ani.zip/mappings?kitsu_id=41370`): the same mapping inside about 28 KB of titles and episode data.

## Decision

- A new crate, `stremionow-markers`, works out the episode from the ids and asks the services. It follows the addon client: rustls with the webpki roots, a 10 s timeout, a 64 KiB response limit, no Referer, no redirects, and HTTPS only.
- Ids are read strictly: lowercase `mal`, `kitsu` or `anilist`, a decimal id of at most 50,000,000 and an episode from 1 to 10,000. The episode comes from the video id when it has one; otherwise from the metadata, but only in the first season, since a later season is usually a separate entry. Specials (season 0) are left out, and a movie is episode 1. Every part of the request URLs is a number the crate formatted, so ids cannot inject anything. IMDb and other ids are never sent anywhere.
- Mapping uses **ARM**, the smaller answer. MyAnimeList ids skip the mapping.
- AniSkip's results are checked before use: clamped to the video, at least 3 s and at most 10 min long, at most one per kind (intro, recap, credits), a plain opening or ending over a "mixed" one, which overlaps the story.
- Answers, including "nothing found" and "no MyAnimeList entry", are cached in memory for the life of the process, so seeking or reopening an episode does not ask again. Failures are not cached, and are only logged at debug level. The player asks once the file's length is known and never waits for the answer.
- In the player, the file's chapters win kind by kind; AniSkip fills the kinds they do not mark. One prompt shows at a time (`PlaybackPrompt.at`): "Skip intro" or "Skip recap" inside those segments; inside credits that run to the end, "Up next" when there is a next episode (skipping them means moving on); inside credits followed by more of the episode (an after-credits scene or a preview of the next one), "Skip credits", which lands on what follows instead of moving on and losing it, then "Up next" in the last half minute. On TV, a prompt that appears while the controls are hidden takes the focus, so OK acts on it; with the controls up it does not move the focus.
- A setting, "Skip segments from AniSkip" (Settings › Playback, on by default), turns it off. Its description says what is sent and to whom. The core checks it too, so a caller cannot send a request with it off.

## Consequences

- Anime from Kitsu, MyAnimeList and AniList addons gets skip buttons without chapters, on phone, TV and desktop.
- Playing an anime episode tells AniSkip which anime and episode is playing and the file's length in whole seconds, and tells ARM (for Kitsu and AniList ids only) which anime; both also see the device's IP address, as with any request. Nothing else leaves the device: no account, no Stremio id and no stream address. Users who mind can turn it off.
- Both are volunteer services. If either goes away or changes its API, the buttons stop appearing and playback is unaffected; ani.zip is a drop-in alternative for the mapping.
- Crowd-sourced times can be wrong. The length filter, the sanity checks and the preference for the file's chapters limit that, and a button never acts without the viewer, except "Up next", which only replaces credits that run to the end of the file.
- The cache is in memory only; a new app session asks again, once per episode.
