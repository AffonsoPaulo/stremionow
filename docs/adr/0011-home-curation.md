# 0011. Pick the home screen's rows on the device

Date: 2026-11-12
Status: Accepted

## Context

The home screen showed the first page of every addon catalog, in addon order, under the names the addons give them ("Popular · Movies"). Streaming apps the project takes as references (Netflix, Prime Video) open on rows made for the viewer: new episodes of what they follow, titles like what they just watched, more of what they like, their own list, and only then what is popular.

Those apps compute this on their servers from everyone's history. StremioNow has no server (see the README's privacy section) and must not send the user's history anywhere new. What it has: the local history (progress, finished videos, My list, in the core's SQLite store), and the installed addons. Cinemeta, installed by default, gives each title its genres, year, director, cast and rating, filters its catalogs by genre and searches by name, people included.

## Decision

**Where.** The core works the home out, so every platform shares it and it can be tested without a device:

- `stremionow-curation`, a new crate without I/O: the genre profile, the similarity score, new-episode detection and de-duplication, deterministic for their inputs (the current time is a parameter).
- `ffi/src/home.rs`: reads the history, plans and makes the requests, and assembles the rows. One call, `home_feed(listener)`, hands the home to the interface in stages and returns the finished one. Each row carries a typed reason (`NewEpisodes`, `BecauseYouWatched { title }`, `MoreGenre { genre }`, `FromYourList`, `Catalog { catalog }`); the interface names it in the user's language. Catalogs carry a `CatalogRole` (trending, new releases, featured, other) for Cinemeta's well-known catalogs.
- `similar_titles(kind, id)` reuses the similarity engine for a title's "More like this" row on its details page.

**Rows, in order, each only when it has titles.** "Continue watching" (unchanged, local) comes first; the core leaves its titles out of every other row.

1. **New episodes.** For up to 6 series followed (episodes watched, or saved to My list and not started), most recently active first: the first regular episode after the last one watched that came out since the user last watched the series (or saved it), within 90 days. Specials, unnumbered episodes and episodes without a release date are never offered.
2. **Because you watched _title_.** For the 2 most recent titles finished or at least half watched. Candidates: the popular and best rated titles of the seed's main genre, the popular ones of its second genre, and Cinemeta searches for its first director and lead actor. Titles the user has seen or started are left out. Score, highest first:

   | Signal | Points |
   | --- | --- |
   | Genres in common (shared ÷ all of both) | up to 3 |
   | A director in common | 2 |
   | Found by searching the seed's director, with no director listed to confirm | 3.5 |
   | Found by searching the seed's lead actor, with no cast listed to confirm | 1.5 |
   | Each lead actor in common | 0.5, up to 1 |
   | Release years apart (15 or more scores nothing) | up to 1 |
   | IMDb rating from 6 to 9 | up to 0.5 |
   | Position in its source list | up to 0.5 |

   A candidate with no genre, director or search in common is dropped. Search results carry no genres or people, so a director search hit stands for the shared director and half the genre points.
3. **More _genre_.** The user's top genres, at most 2: each recent title (up to 12) weighs `max(share watched, 0.25) × 0.5^(days since / 30)`, split equally among its genres; a second genre needs half the first's weight. Filled with the popular and best rated titles of that genre and type, taken in turn.
4. **From your list.** Titles in My list not started.
5. **The addons' catalogs,** in addon order, at most 12, Cinemeta's "New" catalog included for the current year.

Each title shows once, in the first (most personal) row that has it. Suggestion rows left with fewer than 4 titles are dropped; rows of the user's own data are not. The hero rotates through up to 5 titles with a backdrop and a synopsis, two at most from each recommendation row first, then the trending catalogs, taken in turn.

**Requests.** Only catalog, metadata and search requests to the installed addons, the same kinds the app makes when browsing, 4 at a time, at most about 30 for the first stage and 14 for the recommendations, all through the response cache (catalogs fresh for 30 minutes, details for 6 hours, stale entries used offline). The first stage answers from the cache alone, so a returning user sees their home at once; then stale and missing entries are fetched and the recommendations follow. A finished home is reused for 10 minutes while the history, My list and the addons are unchanged.

**Privacy.** Nothing about the user leaves the device beyond what browsing already sends: addons learn which titles' details and which genre pages are asked for, and Cinemeta receives searches for the names of a watched title's director and lead actor, as it would if the user searched for them. No new third party is contacted, and images stay HTTPS only.

## Alternatives

- **The addons' catalogs only, as before.** Simple and private, but the home ignores the user.
- **Collaborative filtering (people who watched X also watched Y).** Needs many users' histories on a server.
- **TMDB or Trakt recommendations.** Better signals (TMDB's "similar" and "recommendations", Trakt's personal lists) but a new third party, an API key or an account, and the history leaving the device. They could plug in later, **off by default**, as an opt-in in Settings that names the service and what it receives: a source that turns a seed title into candidates feeds the same `rank_similar` (or replaces its candidates), keys and tokens live in the platform's secret store (ADR 0006), and requests go through the same cache and limits. Not built now.

## Consequences

- Home opens on rows about the user without a server or an account, and works the same on phone, TV and desktop.
- The curation is plain Rust with unit tests for every rule, and an integration test against a fake Cinemeta.
- Quality depends on the metadata addon: without Cinemeta, any catalog of the right type that filters by genre or searches is used, and rows the addons cannot fill are simply absent.
- The first stage on a cold cache waits for the network; later launches show the cached home first and update rows in place, which can reorder titles inside a row when fresh data differs.
- Weights are hand-tuned; changing them is a change to this record.
