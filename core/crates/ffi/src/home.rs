// SPDX-License-Identifier: GPL-3.0-or-later
//! The home screen: rows picked from the user's own history, then the addons' catalogs.
//!
//! Everything personal is worked out here, on the device (docs/adr/0011-home-curation.md).
//! The history never leaves it: the only requests are catalog and metadata requests to the
//! installed addons, the same kinds the app makes when browsing, a few at a time, through
//! the response cache. [`StremioCore::home_feed`] first answers from that cache alone, so a
//! returning user sees their home at once, then refreshes what is stale and missing.

use std::collections::{HashMap, HashSet};
use std::hash::{DefaultHasher, Hash, Hasher};
use std::time::{Duration, Instant};

use futures::{StreamExt, stream};
use serde::Deserialize;
use serde::de::DeserializeOwned;
use stremionow_addons::InstalledAddon;
use stremionow_curation::{
    self as curation, Candidate, EpisodeInfo, FoundBy, Title, Watched, dedupe_in_order,
};
use stremionow_library::{LibraryEntry, ProgressEntry, WatchedTitle};
use stremionow_protocol::{CatalogDescriptor, Extra, MetaDetail};

use crate::core::{CATALOG_FRESHNESS, META_FRESHNESS, StremioCore, dedupe, now_millis};
use crate::records::{CatalogInfo, CatalogRole, CoreError, Episode, MetaItem};

/// Most catalog rows, to keep the first screen fast.
const MAX_CATALOG_ROWS: usize = 12;
/// Requests to addons at the same time: firing every row at once on a cold, slow connection
/// makes them all compete and time out together.
const CONCURRENCY: usize = 4;
/// History entries read from the database; more never change the picks.
const HISTORY_READ: usize = 50;
/// Recent titles whose details feed the genre profile.
const PROFILE_TITLES: usize = 12;
/// Series checked for new episodes, the most recently watched or saved first.
const NEW_EPISODE_SERIES: usize = 6;
/// "Because you watched" rows, for the most recent titles finished or mostly watched.
const SEEDS: usize = 2;
/// Share of a title watched for it to lead a "Because you watched" row.
const SEED_COMPLETION: f32 = 0.5;
/// Episodes of a series that count as having watched all of it, for the genre profile.
const SERIES_EPISODES_FOR_FULL_WEIGHT: f32 = 3.0;
/// "More <genre>" rows.
const GENRE_ROWS: usize = 2;
/// Titles in a personal row.
const PERSONAL_ROW_ITEMS: usize = 20;
/// A recommendation or catalog row with fewer titles left after removing repeats is dropped
/// rather than shown nearly empty.
const MIN_ROW_ITEMS: usize = 4;
/// Titles the hero rotates through.
const HERO_PICKS: usize = 5;
/// Titles in a details page's "More like this" row.
const SIMILAR_TITLES: usize = 20;
/// History entries read to leave watched titles out of "More like this".
const SIMILAR_EXCLUSION_READ: usize = 1_000;
/// A home worked out this recently is shown again as is, while the history is unchanged.
const REUSE_FOR: Duration = Duration::from_secs(10 * 60);

/// Why a row is on the home screen; the interface names it from this.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum HomeReason {
    /// Series the user follows, each with an episode out since they last watched it.
    NewEpisodes,
    /// Titles like `title`, which the user finished or mostly watched.
    BecauseYouWatched { title: String },
    /// More of a genre the user watches a lot, as the addons spell it.
    MoreGenre { genre: String },
    /// Titles in "My list" the user has not started.
    FromYourList,
    /// The first page of an addon's catalog.
    Catalog { catalog: CatalogInfo },
}

/// A title in a home row, with the episode the row is about, if any.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HomeEntry {
    pub item: MetaItem,
    /// For [`HomeReason::NewEpisodes`]: the new episode.
    pub episode: Option<Episode>,
}

/// A row of the home screen.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HomeSection {
    /// Stable identity of the row across updates.
    pub key: String,
    pub reason: HomeReason,
    pub entries: Vec<HomeEntry>,
    /// Still loading: shown as a placeholder row, and filled or removed by a later update.
    pub loading: bool,
}

/// The home screen. Every title shows in one row only, the most personal that has it, and
/// none of the rows has a title in "Continue watching", which the app shows first.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HomeFeed {
    /// Titles for the hero to rotate through, personal picks first; all have a backdrop.
    pub hero: Vec<MetaItem>,
    pub sections: Vec<HomeSection>,
    /// False while more updates are on the way.
    pub complete: bool,
}

/// Receives the home screen as it fills in. Called from the core's threads; keep it short.
#[uniffi::export(callback_interface)]
pub trait HomeListener: Send + Sync {
    fn on_update(&self, feed: HomeFeed);
}

/// The last home worked out, kept for [`REUSE_FOR`].
pub(crate) struct RecentHome {
    at: Instant,
    signature: u64,
    feed: HomeFeed,
}

#[uniffi::export(async_runtime = "tokio")]
impl StremioCore {
    /// Titles like the given one, for its details page, best first: scored as the home's
    /// "Because you watched" rows are, by genres, era, director and cast (see
    /// `docs/adr/0011-home-curation.md`), without the title itself and titles the user has
    /// watched or started. A handful of cached catalog requests to the installed addons.
    pub async fn similar_titles(
        &self,
        kind: String,
        id: String,
    ) -> Result<Vec<MetaItem>, CoreError> {
        let addons = self.enabled()?;
        let seed = self
            .home_meta(&addons, &kind, &id, Fetch::Network)
            .await
            .ok_or(CoreError::NotFound)?;
        let known: HashSet<String> = {
            let store = self.store()?;
            let mut known: HashSet<String> = store
                .watched_titles(SIMILAR_EXCLUSION_READ)?
                .into_iter()
                .map(|w| w.id)
                .collect();
            known.extend(
                store
                    .progress_list(SIMILAR_EXCLUSION_READ)?
                    .into_iter()
                    .map(|p| p.id),
            );
            known
        };
        let lists: Vec<(FoundBy, Vec<MetaDetail>)> = stream::iter(seed_sources(&addons, &seed))
            .map(|source| async move {
                let metas = self
                    .cached_or_fetched::<RichCatalog>(
                        &source.url,
                        CATALOG_FRESHNESS,
                        Fetch::Network,
                    )
                    .await
                    .ok()
                    .flatten()
                    .and_then(|catalog| catalog.metas)
                    .unwrap_or_default();
                (source.found_by, metas)
            })
            .buffered(CONCURRENCY)
            .collect()
            .await;
        Ok(rank_like(
            &seed,
            lists
                .iter()
                .map(|(found_by, metas)| (*found_by, metas.as_slice())),
            &|candidate: &str| known.contains(candidate),
            SIMILAR_TITLES,
        ))
    }

    /// Works out the home screen, handing each stage to `listener` as it is ready: first what
    /// the response cache holds, then rows refreshed from the addons, and last the personal
    /// recommendations. Returns the finished home, which `listener` also receives. Fails only
    /// when nothing could be shown because every catalog failed.
    pub async fn home_feed(&self, listener: Box<dyn HomeListener>) -> Result<HomeFeed, CoreError> {
        let addons = self.enabled()?;
        let local = self.local_history()?;
        let signature = signature(&local, &addons);
        if let Some(feed) = self.reusable_home(signature)? {
            listener.on_update(feed.clone());
            return Ok(feed);
        }
        let plan = Plan::new(&addons, &local, now_millis());

        let cached = self.work_out(&plan, Fetch::CacheOnly, None, &|_| {}).await;
        if !cached.feed.sections.is_empty() {
            listener.on_update(cached.feed.clone());
        }
        let fresh = self
            .work_out(&plan, Fetch::Network, Some(&cached), &|feed| {
                listener.on_update(feed);
            })
            .await;
        if fresh.feed.sections.is_empty()
            && let Some(error) = fresh.catalog_error
        {
            return Err(error);
        }
        listener.on_update(fresh.feed.clone());
        *self.recent_home()? = Some(RecentHome {
            at: Instant::now(),
            signature,
            feed: fresh.feed.clone(),
        });
        Ok(fresh.feed)
    }
}

/// Where data may come from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Fetch {
    /// The response cache only, however old; nothing is requested.
    CacheOnly,
    /// The cache while fresh, else the addon (falling back to the stale cache offline).
    Network,
}

/// What the database says about the user.
struct Local {
    progress: Vec<ProgressEntry>,
    watched: Vec<WatchedTitle>,
    library: Vec<LibraryEntry>,
    /// Videos watched per series, for the last episode seen.
    watched_videos: HashMap<String, Vec<String>>,
}

/// A recently watched title.
#[derive(Debug, Clone)]
struct Recent {
    id: String,
    kind: String,
    completion: f32,
    at: i64,
}

/// A series to check for new episodes.
#[derive(Debug, Clone)]
struct Followed {
    id: String,
    /// Season and number of the last episode watched; None for a saved series not started.
    last_seen: Option<(u32, u32)>,
    /// Episodes out after this are new: the last watch, or when the series was saved.
    since: i64,
}

/// What to fetch and how to combine it, decided from the local data alone.
struct Plan<'a> {
    addons: &'a [InstalledAddon],
    now: i64,
    catalogs: Vec<(CatalogInfo, Option<String>)>,
    recent: Vec<Recent>,
    followed: Vec<Followed>,
    library: Vec<LibraryEntry>,
    /// Titles the user finished videos of.
    seen: HashSet<String>,
    /// Titles in "Continue watching".
    started: HashSet<String>,
}

impl<'a> Plan<'a> {
    fn new(addons: &'a [InstalledAddon], local: &Local, now: i64) -> Self {
        let started: HashSet<String> = local.progress.iter().map(|p| p.id.clone()).collect();
        let seen: HashSet<String> = local.watched.iter().map(|w| w.id.clone()).collect();

        // Most recent first, each title once: what is in progress and what was finished.
        let mut recent: Vec<Recent> = Vec::new();
        let mut push = |entry: Recent| match recent.iter_mut().find(|r| r.id == entry.id) {
            Some(known) => {
                known.at = known.at.max(entry.at);
                known.completion = known.completion.max(entry.completion);
            }
            None => recent.push(entry),
        };
        for progress in &local.progress {
            let completion = if progress.duration_ms > 0 {
                (progress.position_ms as f32 / progress.duration_ms as f32).clamp(0.0, 1.0)
            } else {
                0.0
            };
            push(Recent {
                id: progress.id.clone(),
                kind: progress.kind.clone(),
                completion,
                at: progress.updated_at,
            });
        }
        for watched in &local.watched {
            let series = watched.last_video_id != watched.id;
            push(Recent {
                id: watched.id.clone(),
                kind: if series { "series" } else { "movie" }.to_owned(),
                completion: if series {
                    (watched.videos as f32 / SERIES_EPISODES_FOR_FULL_WEIGHT).min(1.0)
                } else {
                    1.0
                },
                at: watched.watched_at,
            });
        }
        recent.sort_by(|a, b| b.at.cmp(&a.at).then_with(|| a.id.cmp(&b.id)));

        // Series followed: watched episodes, then saved and not started, latest first. Those
        // in "Continue watching" are already on screen.
        let mut followed: Vec<Followed> = local
            .watched
            .iter()
            .filter(|w| w.last_video_id != w.id && !started.contains(&w.id))
            .map(|w| Followed {
                id: w.id.clone(),
                last_seen: local.watched_videos.get(&w.id).and_then(|videos| {
                    videos.iter().filter_map(|v| episode_number(&w.id, v)).max()
                }),
                since: w.watched_at,
            })
            .collect();
        followed.extend(
            local
                .library
                .iter()
                .filter(|l| l.kind == "series" && !seen.contains(&l.id) && !started.contains(&l.id))
                .map(|l| Followed {
                    id: l.id.clone(),
                    last_seen: None,
                    since: l.added_at,
                }),
        );
        followed.sort_by(|a, b| b.since.cmp(&a.since).then_with(|| a.id.cmp(&b.id)));
        followed.truncate(NEW_EPISODE_SERIES);

        let library = local
            .library
            .iter()
            .filter(|l| !seen.contains(&l.id) && !started.contains(&l.id))
            .take(PERSONAL_ROW_ITEMS)
            .cloned()
            .collect();

        Self {
            addons,
            now,
            catalogs: home_catalogs(addons),
            recent,
            followed,
            library,
            seen,
            started,
        }
    }

    /// Whether the user has seen or started `id`, so recommending it makes no sense.
    fn known(&self, id: &str) -> bool {
        self.seen.contains(id) || self.started.contains(id)
    }

    /// Details to fetch in the first stage: the recent titles, then the followed series.
    fn titles_to_describe(&self) -> Vec<(String, String)> {
        let mut wanted: Vec<(String, String)> = Vec::new();
        let recent = self
            .recent
            .iter()
            .take(PROFILE_TITLES)
            .map(|r| (r.kind.clone(), r.id.clone()));
        let followed = self
            .followed
            .iter()
            .map(|f| ("series".to_owned(), f.id.clone()));
        for pair in recent.chain(followed) {
            if !wanted.contains(&pair) {
                wanted.push(pair);
            }
        }
        wanted
    }
}

/// One stage's result.
struct WorkedOut {
    feed: HomeFeed,
    /// The personal recommendation rows on their own, to show again while they refresh.
    recommendations: Vec<HomeSection>,
    /// Why catalogs failed, when one did.
    catalog_error: Option<CoreError>,
}

/// A meta response in which `{"meta": null}`, or no meta at all, means the addon does not have
/// the title, so the next addon can be asked.
#[derive(Deserialize)]
struct MaybeMeta {
    #[serde(default)]
    meta: Option<MetaDetail>,
}

/// A catalog response read with each title's people and episodes, which Cinemeta includes.
#[derive(Deserialize)]
struct RichCatalog {
    #[serde(default)]
    metas: Option<Vec<MetaDetail>>,
}

impl StremioCore {
    /// One pass over the plan with the given data source. With the network, reports the
    /// catalogs and new episodes through `report` before working out the recommendations,
    /// which reuse `previous`'s meanwhile.
    async fn work_out(
        &self,
        plan: &Plan<'_>,
        fetch: Fetch,
        previous: Option<&WorkedOut>,
        report: &(dyn Fn(HomeFeed) + Sync),
    ) -> WorkedOut {
        // Stage 1: catalog pages and the details of recent and followed titles, together.
        let addons = plan.addons;
        let catalog_pages = stream::iter(plan.catalogs.clone())
            .map(|(catalog, genre)| async move {
                self.home_catalog_page(addons, &catalog, genre.as_deref(), fetch)
                    .await
            })
            .buffered(CONCURRENCY);
        let described = stream::iter(plan.titles_to_describe())
            .map(|(kind, id)| async move {
                let detail = self.home_meta(plan.addons, &kind, &id, fetch).await;
                ((kind, id), detail)
            })
            .buffer_unordered(CONCURRENCY);
        let (pages, described): (Vec<_>, Vec<_>) =
            futures::future::join(catalog_pages.collect(), described.collect()).await;
        let details: HashMap<String, MetaDetail> = described
            .into_iter()
            .filter_map(|((_, id), detail)| detail.map(|d| (id, d)))
            .collect();

        let mut catalog_error = None;
        let mut catalog_sections = Vec::new();
        for ((catalog, _), page) in plan.catalogs.iter().zip(pages) {
            let key = format!(
                "catalog:{}/{}/{}",
                catalog.addon_manifest_url, catalog.kind, catalog.catalog_id
            );
            let (entries, loading) = match page {
                Ok(Some(metas)) => (entries_of(metas), false),
                Ok(None) => (Vec::new(), true),
                Err(error) => {
                    log::warn!("home row {} failed: {error}", catalog.catalog_id);
                    catalog_error.get_or_insert(error);
                    continue;
                }
            };
            if entries.is_empty() && !loading {
                continue;
            }
            catalog_sections.push(HomeSection {
                key,
                reason: HomeReason::Catalog {
                    catalog: catalog.clone(),
                },
                entries,
                loading,
            });
        }
        let new_episodes = new_episodes_section(plan, &details);
        let my_list = self.my_list_section(plan);

        if fetch == Fetch::Network {
            let carried = previous
                .map(|p| p.recommendations.clone())
                .unwrap_or_default();
            report(assemble(
                plan,
                new_episodes.clone(),
                carried,
                my_list.clone(),
                catalog_sections.clone(),
                false,
            ));
        }

        // Stage 2: candidates for the recommendation rows.
        let recommendations = self.recommendations(plan, &details, fetch).await;
        let feed = assemble(
            plan,
            new_episodes,
            recommendations.clone(),
            my_list,
            catalog_sections,
            fetch == Fetch::Network,
        );
        WorkedOut {
            feed,
            recommendations,
            catalog_error,
        }
    }

    /// "Because you watched" rows for the latest titles finished or mostly watched, then
    /// "More <genre>" rows for the user's top genres.
    async fn recommendations(
        &self,
        plan: &Plan<'_>,
        details: &HashMap<String, MetaDetail>,
        fetch: Fetch,
    ) -> Vec<HomeSection> {
        let seeds: Vec<&MetaDetail> = plan
            .recent
            .iter()
            .filter(|r| r.completion >= SEED_COMPLETION)
            .filter_map(|r| details.get(&r.id))
            .take(SEEDS)
            .collect();
        let history: Vec<Watched> = plan
            .recent
            .iter()
            .take(PROFILE_TITLES)
            .filter_map(|r| {
                details.get(&r.id).map(|detail| Watched {
                    title: title_of(detail),
                    completion: r.completion,
                    watched_at: r.at,
                })
            })
            .collect();
        let genres = curation::top_genres(&curation::genre_profile(&history, plan.now), GENRE_ROWS);

        // Every request of this stage, so they share the concurrency limit.
        let mut jobs: Vec<(usize, Source)> = Vec::new();
        for (index, seed) in seeds.iter().enumerate() {
            for source in seed_sources(plan.addons, seed) {
                jobs.push((index, source));
            }
        }
        for (index, genre) in genres.iter().enumerate() {
            for catalog in genre_catalogs(plan.addons, &genre.kind, &genre.genre)
                .into_iter()
                .take(2)
            {
                jobs.push((
                    SEEDS + index,
                    Source {
                        url: catalog_url(catalog.0, catalog.1, Some(&genre.genre), None),
                        found_by: FoundBy::Catalog,
                    },
                ));
            }
        }
        let fetched: Vec<(usize, FoundBy, Vec<MetaDetail>)> = stream::iter(jobs)
            .map(|(owner, source)| async move {
                let metas = self
                    .cached_or_fetched::<RichCatalog>(&source.url, CATALOG_FRESHNESS, fetch)
                    .await
                    .ok()
                    .flatten()
                    .and_then(|catalog| catalog.metas)
                    .unwrap_or_default();
                (owner, source.found_by, metas)
            })
            .buffered(CONCURRENCY)
            .collect()
            .await;

        let mut sections = Vec::new();
        for (index, seed) in seeds.iter().enumerate() {
            let lists = fetched
                .iter()
                .filter(|(owner, ..)| *owner == index)
                .map(|(_, found_by, metas)| (*found_by, metas.as_slice()));
            let entries: Vec<HomeEntry> =
                rank_like(seed, lists, &|id: &str| plan.known(id), PERSONAL_ROW_ITEMS)
                    .into_iter()
                    .map(|item| HomeEntry {
                        item,
                        episode: None,
                    })
                    .collect();
            sections.push(HomeSection {
                key: format!("because:{}", seed.preview.id),
                reason: HomeReason::BecauseYouWatched {
                    title: seed.preview.name.clone(),
                },
                entries,
                loading: false,
            });
        }
        for (index, genre) in genres.iter().enumerate() {
            // The lists of each catalog taken in turn: popular, best rated, popular...
            let lists: Vec<&Vec<MetaDetail>> = fetched
                .iter()
                .filter(|(owner, ..)| *owner == SEEDS + index)
                .map(|(_, _, metas)| metas)
                .collect();
            let longest = lists.iter().map(|l| l.len()).max().unwrap_or(0);
            let interleaved = (0..longest)
                .flat_map(|i| lists.iter().filter_map(move |list| list.get(i)))
                .filter(|meta| meta.preview.kind == genre.kind && !plan.known(&meta.preview.id))
                .take(PERSONAL_ROW_ITEMS * 2)
                .cloned();
            sections.push(HomeSection {
                key: format!("genre:{}", genre.genre.to_lowercase()),
                reason: HomeReason::MoreGenre {
                    genre: genre.genre.clone(),
                },
                entries: entries_of(interleaved.collect()),
                loading: false,
            });
        }
        sections
    }

    /// "My list" titles not started, with what the cache knows about them.
    fn my_list_section(&self, plan: &Plan<'_>) -> Option<HomeSection> {
        let entries: Vec<HomeEntry> = plan
            .library
            .iter()
            .map(|entry| {
                let item = self
                    .cached_meta(plan.addons, &entry.kind, &entry.id)
                    .map(|detail| MetaItem::from(detail.preview))
                    .unwrap_or_else(|| MetaItem {
                        id: entry.id.clone(),
                        kind: entry.kind.clone(),
                        name: entry.name.clone(),
                        poster: entry.poster.clone().filter(|p| p.starts_with("https://")),
                        background: None,
                        logo: None,
                        description: None,
                        release_info: None,
                        imdb_rating: None,
                        genres: Vec::new(),
                    });
                HomeEntry {
                    item,
                    episode: None,
                }
            })
            .collect();
        (!entries.is_empty()).then(|| HomeSection {
            key: "my-list".into(),
            reason: HomeReason::FromYourList,
            entries,
            loading: false,
        })
    }

    async fn home_catalog_page(
        &self,
        addons: &[InstalledAddon],
        catalog: &CatalogInfo,
        genre: Option<&str>,
        fetch: Fetch,
    ) -> Result<Option<Vec<MetaDetail>>, CoreError> {
        let Some(addon) = addons
            .iter()
            .find(|a| a.url.manifest_url() == catalog.addon_manifest_url)
        else {
            return Ok(Some(Vec::new()));
        };
        let url = catalog_url(addon, &catalog.kind_id(), genre, None);
        Ok(self
            .cached_or_fetched::<RichCatalog>(&url, CATALOG_FRESHNESS, fetch)
            .await?
            .map(|page| page.metas.unwrap_or_default()))
    }

    async fn home_meta(
        &self,
        addons: &[InstalledAddon],
        kind: &str,
        id: &str,
        fetch: Fetch,
    ) -> Option<MetaDetail> {
        self.first_meta(addons, kind, id, fetch)
            .await
            .ok()
            .flatten()
    }

    /// A title's details from the first of `addons` that has them, in addon order. An addon
    /// that claims every id (`meta` without `idPrefixes`) may still not know this one, or fail,
    /// so one that answers with an error, a 404 or `{"meta": null}` is passed over for the next.
    /// None when no addon has the title (or, from the cache alone, none has it cached); the
    /// first error when one failed and no other had it.
    pub(crate) async fn first_meta(
        &self,
        addons: &[InstalledAddon],
        kind: &str,
        id: &str,
        fetch: Fetch,
    ) -> Result<Option<MetaDetail>, CoreError> {
        let mut first_error = None;
        for url in meta_urls(addons, kind, id) {
            match self
                .cached_or_fetched::<MaybeMeta>(&url, META_FRESHNESS, fetch)
                .await
            {
                Ok(Some(MaybeMeta { meta: Some(meta) })) => return Ok(Some(meta)),
                Ok(_) => {}
                Err(error) => {
                    first_error.get_or_insert(error);
                }
            }
        }
        first_error.map_or(Ok(None), Err)
    }

    fn cached_meta(&self, addons: &[InstalledAddon], kind: &str, id: &str) -> Option<MetaDetail> {
        let store = self.store().ok()?;
        meta_urls(addons, kind, id).find_map(|url| {
            let (body, _) = store.cached(&url).ok()??;
            serde_json::from_slice::<MaybeMeta>(&body).ok()?.meta
        })
    }

    /// `url` from the response cache alone, or through it from the network.
    async fn cached_or_fetched<T: DeserializeOwned>(
        &self,
        url: &str,
        freshness: Duration,
        fetch: Fetch,
    ) -> Result<Option<T>, CoreError> {
        match fetch {
            Fetch::CacheOnly => Ok(self
                .store()?
                .cached(url)?
                .and_then(|(body, _)| serde_json::from_slice(&body).ok())),
            Fetch::Network => self.cached_json(url, freshness).await.map(Some),
        }
    }

    fn local_history(&self) -> Result<Local, CoreError> {
        let store = self.store()?;
        let progress = store.progress_list(HISTORY_READ)?;
        let watched = store.watched_titles(HISTORY_READ)?;
        let mut watched_videos = HashMap::new();
        for title in watched.iter().filter(|w| w.last_video_id != w.id) {
            watched_videos.insert(title.id.clone(), store.watched_videos(&title.id)?);
        }
        Ok(Local {
            progress,
            watched,
            library: store.library()?,
            watched_videos,
        })
    }

    fn reusable_home(&self, signature: u64) -> Result<Option<HomeFeed>, CoreError> {
        Ok(self
            .recent_home()?
            .as_ref()
            .filter(|recent| recent.signature == signature && recent.at.elapsed() < REUSE_FOR)
            .map(|recent| recent.feed.clone()))
    }

    fn recent_home(&self) -> Result<std::sync::MutexGuard<'_, Option<RecentHome>>, CoreError> {
        self.recent_home.lock().map_err(|_| CoreError::Storage {
            reason: "the home lock was poisoned".into(),
        })
    }
}

/// Puts the rows in their order, removes repeats so each title shows in its most personal
/// row (and none that is in "Continue watching"), and picks the hero.
fn assemble(
    plan: &Plan<'_>,
    new_episodes: Option<HomeSection>,
    recommendations: Vec<HomeSection>,
    my_list: Option<HomeSection>,
    catalogs: Vec<HomeSection>,
    complete: bool,
) -> HomeFeed {
    let mut sections: Vec<HomeSection> = new_episodes
        .into_iter()
        .chain(recommendations)
        .chain(my_list)
        .chain(catalogs)
        .collect();
    let mut lists: Vec<Vec<HomeEntry>> = sections
        .iter_mut()
        .map(|section| std::mem::take(&mut section.entries))
        .collect();
    dedupe_in_order(
        &mut lists,
        |entry| entry.item.id.clone(),
        plan.started.clone(),
    );
    for (section, mut entries) in sections.iter_mut().zip(lists) {
        if matches!(
            section.reason,
            HomeReason::BecauseYouWatched { .. } | HomeReason::MoreGenre { .. }
        ) {
            entries.truncate(PERSONAL_ROW_ITEMS);
        }
        section.entries = entries;
    }
    // Rows the user's own data makes (new episodes, their list) show any number of titles;
    // suggestions need a row's worth.
    sections.retain(|section| {
        let own = matches!(
            section.reason,
            HomeReason::NewEpisodes | HomeReason::FromYourList
        );
        section.loading
            || (!section.entries.is_empty() && (own || section.entries.len() >= MIN_ROW_ITEMS))
    });
    HomeFeed {
        hero: hero_picks(plan, &sections),
        sections,
        complete,
    }
}

/// Up to [`HERO_PICKS`] titles with a backdrop and a synopsis: the top recommendations first, two at most
/// from each row, then the trending catalogs, taken in turn.
fn hero_picks(plan: &Plan<'_>, sections: &[HomeSection]) -> Vec<MetaItem> {
    // A billboard needs a picture and something to say: search results come without a
    // synopsis, and would leave the hero a bare title.
    let suitable = |entry: &&HomeEntry| {
        entry.item.background.is_some()
            && entry.item.description.is_some()
            && entry.episode.is_none()
            && !plan.known(&entry.item.id)
    };
    let personal: Vec<Vec<&HomeEntry>> = sections
        .iter()
        .filter(|s| {
            matches!(
                s.reason,
                HomeReason::BecauseYouWatched { .. } | HomeReason::MoreGenre { .. }
            )
        })
        .map(|s| s.entries.iter().filter(suitable).take(2).collect())
        .collect();
    let trending: Vec<Vec<&HomeEntry>> = sections
        .iter()
        .filter(|s| {
            matches!(
                &s.reason,
                HomeReason::Catalog { catalog } if catalog.role == CatalogRole::Trending
            )
        })
        .map(|s| s.entries.iter().filter(suitable).take(HERO_PICKS).collect())
        .collect();
    let mut picks: Vec<MetaItem> = Vec::new();
    for group in [personal, trending] {
        let longest = group.iter().map(Vec::len).max().unwrap_or(0);
        for i in 0..longest {
            for list in &group {
                if let Some(entry) = list.get(i)
                    && picks.len() < HERO_PICKS
                    && !picks.iter().any(|p| p.id == entry.item.id)
                {
                    picks.push(entry.item.clone());
                }
            }
        }
    }
    if picks.is_empty() {
        // No trending catalog: the first backdrop of any row.
        picks.extend(
            sections
                .iter()
                .flat_map(|s| s.entries.iter())
                .find(|entry| suitable(entry))
                .map(|entry| entry.item.clone()),
        );
    }
    picks
}

/// Followed series with an episode out since the user last watched them, latest first.
fn new_episodes_section(
    plan: &Plan<'_>,
    details: &HashMap<String, MetaDetail>,
) -> Option<HomeSection> {
    let mut found: Vec<(i64, HomeEntry)> = plan
        .followed
        .iter()
        .filter_map(|followed| {
            let detail = details.get(&followed.id)?;
            let infos: Vec<EpisodeInfo> = detail
                .videos
                .iter()
                .map(|video| EpisodeInfo {
                    season: video.season,
                    episode: video.episode,
                    released_ms: video.released.as_deref().and_then(release_ms),
                })
                .collect();
            let index =
                curation::new_episode(&infos, followed.last_seen, followed.since, plan.now)?;
            Some((
                infos[index].released_ms.unwrap_or_default(),
                HomeEntry {
                    item: MetaItem::from(detail.preview.clone()),
                    episode: Some(Episode::from(detail.videos[index].clone())),
                },
            ))
        })
        .collect();
    found.sort_by_key(|(released, _)| std::cmp::Reverse(*released));
    (!found.is_empty()).then(|| HomeSection {
        key: "new-episodes".into(),
        reason: HomeReason::NewEpisodes,
        entries: found.into_iter().map(|(_, entry)| entry).collect(),
        loading: false,
    })
}

/// When a video came out, in Unix milliseconds: a full timestamp, as Cinemeta sends, or a date
/// alone (`2024-05-01`), as other addons do, taken as that day's start in UTC.
fn release_ms(released: &str) -> Option<i64> {
    let released = released.trim();
    stremionow_sync::parse_timestamp(released).or_else(|| {
        let b = released.as_bytes();
        let date_only = b.len() == 10
            && b[4] == b'-'
            && b[7] == b'-'
            && b.iter()
                .enumerate()
                .all(|(i, c)| i == 4 || i == 7 || c.is_ascii_digit());
        date_only
            .then(|| stremionow_sync::parse_timestamp(&format!("{released}T00:00:00Z")))
            .flatten()
    })
}

/// The titles of `lists` most like `seed` (see [`curation::similarity`]), best first, at
/// most `limit`, leaving out the seed and what `excluded` says the user has seen or started.
/// Each list comes with how it was found.
fn rank_like<'a>(
    seed: &MetaDetail,
    lists: impl Iterator<Item = (FoundBy, &'a [MetaDetail])>,
    excluded: &dyn Fn(&str) -> bool,
    limit: usize,
) -> Vec<MetaItem> {
    let mut items: HashMap<String, MetaItem> = HashMap::new();
    let mut candidates = Vec::new();
    for (found_by, metas) in lists {
        let of = metas.len();
        for (rank, meta) in metas.iter().enumerate() {
            items
                .entry(meta.preview.id.clone())
                .or_insert_with(|| MetaItem::from(meta.preview.clone()));
            candidates.push(Candidate {
                title: title_of(meta),
                rank,
                of,
                found_by,
            });
        }
    }
    curation::rank_similar(&title_of(seed), candidates, excluded, limit)
        .into_iter()
        .filter_map(|candidate| items.remove(&candidate.title.id))
        .collect()
}

/// A request for candidates.
struct Source {
    url: String,
    found_by: FoundBy,
}

/// Where to look for titles like `seed`: popular and best rated titles of its main genre,
/// popular ones of its second genre, and searches for its first director and lead actor.
fn seed_sources(addons: &[InstalledAddon], seed: &MetaDetail) -> Vec<Source> {
    let kind = &seed.preview.kind;
    let mut sources = Vec::new();
    for (position, genre) in seed.preview.genres.iter().take(2).enumerate() {
        let catalogs = genre_catalogs(addons, kind, genre);
        for (addon, catalog) in catalogs.into_iter().take(if position == 0 { 2 } else { 1 }) {
            sources.push(Source {
                url: catalog_url(addon, catalog, Some(genre), None),
                found_by: FoundBy::Catalog,
            });
        }
    }
    if let Some((addon, catalog)) = search_catalog(addons, kind) {
        let people = seed
            .director
            .first()
            .map(|name| (name, FoundBy::Director))
            .into_iter()
            .chain(seed.cast.first().map(|name| (name, FoundBy::Actor)));
        for (person, found_by) in people.filter(|(name, _)| !name.trim().is_empty()) {
            sources.push(Source {
                url: catalog_url(addon, catalog, None, Some(person.trim())),
                found_by,
            });
        }
    }
    sources
}

/// Catalogs of `kind` that can filter by `genre`, in addon order.
fn genre_catalogs<'a>(
    addons: &'a [InstalledAddon],
    kind: &str,
    genre: &str,
) -> Vec<(&'a InstalledAddon, &'a CatalogDescriptor)> {
    addons
        .iter()
        .flat_map(|addon| addon.manifest.catalogs.iter().map(move |c| (addon, c)))
        .filter(|(_, c)| {
            c.kind == kind
                && !c.requires_extra()
                && c.extra_options("genre")
                    .iter()
                    .any(|g| g.eq_ignore_ascii_case(genre))
        })
        .collect()
}

/// The first catalog of `kind` that searches.
fn search_catalog<'a>(
    addons: &'a [InstalledAddon],
    kind: &str,
) -> Option<(&'a InstalledAddon, &'a CatalogDescriptor)> {
    addons
        .iter()
        .flat_map(|addon| addon.manifest.catalogs.iter().map(move |c| (addon, c)))
        .find(|(_, c)| c.kind == kind && c.supports_extra("search"))
}

/// Catalogs for the home rows, in addon order: every catalog that needs nothing to browse,
/// and Cinemeta's new releases for the current year. A catalog a manifest lists twice, or an
/// addon listed twice, shows once: the interface keys rows by addon, type and id, and a repeated
/// key crashes it.
fn home_catalogs(addons: &[InstalledAddon]) -> Vec<(CatalogInfo, Option<String>)> {
    let mut seen: HashSet<(String, String, String)> = HashSet::new();
    addons
        .iter()
        .flat_map(|addon| {
            addon.manifest.catalogs.iter().filter_map(move |c| {
                let info = CatalogInfo::new(addon, c);
                if !c.requires_extra() {
                    Some((info, None))
                } else if info.role == CatalogRole::NewReleases {
                    // Its "genre" is the year; the first option is the latest.
                    let year = c.extra_options("genre").into_iter().next()?;
                    Some((info, Some(year)))
                } else {
                    None
                }
            })
        })
        .filter(|(info, _)| {
            seen.insert((
                info.addon_manifest_url.clone(),
                info.kind.clone(),
                info.catalog_id.clone(),
            ))
        })
        .take(MAX_CATALOG_ROWS)
        .collect()
}

/// A catalog address, built the way [`StremioCore::catalog_page`] builds it so both share
/// the response cache.
fn catalog_url(
    addon: &InstalledAddon,
    catalog: impl CatalogId,
    genre: Option<&str>,
    search: Option<&str>,
) -> String {
    let mut extra = Extra::new();
    if let Some(genre) = genre {
        extra = extra.with("genre", genre);
    }
    if let Some(search) = search {
        extra = extra.with("search", search);
    }
    let (kind, id) = catalog.kind_and_id();
    addon.url.resource("catalog", kind, id, &extra)
}

/// The addresses of a title's details at every addon that may have them, in addon order, as
/// [`StremioCore::meta`] asks for them.
fn meta_urls<'a>(
    addons: &'a [InstalledAddon],
    kind: &'a str,
    id: &'a str,
) -> impl Iterator<Item = String> + 'a {
    addons
        .iter()
        .filter(move |addon| addon.manifest.supports("meta", kind, id))
        .map(move |addon| addon.url.resource("meta", kind, id, &Extra::new()))
}

/// A catalog's type and id, from either its descriptor or its [`CatalogInfo`].
trait CatalogId {
    fn kind_and_id(&self) -> (&str, &str);
}

impl CatalogId for &CatalogDescriptor {
    fn kind_and_id(&self) -> (&str, &str) {
        (&self.kind, &self.id)
    }
}

impl CatalogId for &(String, String) {
    fn kind_and_id(&self) -> (&str, &str) {
        (&self.0, &self.1)
    }
}

impl CatalogInfo {
    fn kind_id(&self) -> (String, String) {
        (self.kind.clone(), self.catalog_id.clone())
    }
}

fn entries_of(metas: Vec<MetaDetail>) -> Vec<HomeEntry> {
    dedupe(metas.into_iter().map(|meta| MetaItem::from(meta.preview)))
        .into_iter()
        .map(|item| HomeEntry {
            item,
            episode: None,
        })
        .collect()
}

fn title_of(detail: &MetaDetail) -> Title {
    let preview = &detail.preview;
    Title {
        id: preview.id.clone(),
        kind: preview.kind.clone(),
        genres: preview.genres.clone(),
        year: preview
            .release_info
            .as_deref()
            .and_then(curation::parse_year),
        directors: detail.director.clone(),
        cast: detail.cast.clone(),
        rating: preview
            .imdb_rating
            .as_deref()
            .and_then(curation::parse_rating),
    }
}

/// Season and number of an episode of title `id` from its video id, such as `tt0903747:1:2`.
/// Ids in other shapes (`kitsu:1376:5` has no season) give nothing.
fn episode_number(id: &str, video_id: &str) -> Option<(u32, u32)> {
    let rest = video_id.strip_prefix(id)?.strip_prefix(':')?;
    let (season, episode) = rest.split_once(':')?;
    Some((season.parse().ok()?, episode.parse().ok()?))
}

/// Changes when anything the home depends on changes: the history, the list or the addons.
fn signature(local: &Local, addons: &[InstalledAddon]) -> u64 {
    let mut hasher = DefaultHasher::new();
    for p in &local.progress {
        (&p.id, &p.video_id, p.updated_at).hash(&mut hasher);
    }
    for w in &local.watched {
        (&w.id, w.watched_at, w.videos).hash(&mut hasher);
    }
    for l in &local.library {
        (&l.id, l.added_at).hash(&mut hasher);
    }
    for addon in addons {
        addon.url.manifest_url().hash(&mut hasher);
    }
    hasher.finish()
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use serde_json::{Value, json};
    use stremionow_library::LibraryEntry;
    use wiremock::matchers::{method, path, path_regex};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;
    use crate::playback::WatchProgress;

    const DAY: i64 = 86_400_000;

    /// Collects every update the home screen sends.
    #[derive(Default)]
    struct Updates(Arc<Mutex<Vec<HomeFeed>>>);

    impl HomeListener for Updates {
        fn on_update(&self, feed: HomeFeed) {
            self.0.lock().expect("lock").push(feed);
        }
    }

    fn listener() -> (Box<dyn HomeListener>, Arc<Mutex<Vec<HomeFeed>>>) {
        let updates = Updates::default();
        let seen = updates.0.clone();
        (Box::new(updates), seen)
    }

    fn meta(id: &str, kind: &str, genres: &[&str], year: u32, extra: Value) -> Value {
        let mut value = json!({
            "id": id, "type": kind, "name": format!("Title {id}"), "genres": genres,
            "releaseInfo": year.to_string(),
            "poster": format!("https://img.example/poster/{id}.jpg"),
            "background": format!("https://img.example/background/{id}.jpg"),
            "description": "A synopsis.",
        });
        if let (Some(target), Some(fields)) = (value.as_object_mut(), extra.as_object()) {
            target.extend(fields.clone());
        }
        value
    }

    fn list(prefix: &str, kind: &str, genres: &[&str], count: usize, year: u32) -> Vec<Value> {
        (0..count)
            .map(|n| meta(&format!("tt{prefix}{n:02}"), kind, genres, year, json!({})))
            .collect()
    }

    async fn serve(server: &MockServer, route: &str, body: Value) {
        Mock::given(method("GET"))
            .and(path_regex(format!("^{route}$")))
            .respond_with(ResponseTemplate::new(200).set_body_json(body))
            .mount(server)
            .await;
    }

    /// An addon shaped like Cinemeta, with its catalogs, details and searches.
    async fn cinemeta() -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/manifest.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "id": "com.linvo.cinemeta", "version": "3.0.0", "name": "Cinemeta",
                "resources": ["catalog", {"name": "meta", "types": ["movie", "series"], "idPrefixes": ["tt"]}],
                "types": ["movie", "series"],
                "catalogs": [
                    {"type": "movie", "id": "top", "name": "Popular", "extra": [
                        {"name": "genre", "options": ["Horror", "Thriller", "Drama"]},
                        {"name": "search"}, {"name": "skip"}]},
                    {"type": "series", "id": "top", "name": "Popular", "extra": [
                        {"name": "genre", "options": ["Drama"]}, {"name": "search"}]},
                    {"type": "movie", "id": "year", "name": "New", "extra": [
                        {"name": "genre", "isRequired": true, "options": ["2026", "2025"]}]},
                    {"type": "movie", "id": "imdbRating", "name": "Featured", "extra": [
                        {"name": "genre", "options": ["Horror", "Drama"]}]},
                    {"type": "series", "id": "calendar-videos", "extra": [
                        {"name": "calendarVideosIds", "isRequired": true}]}
                ]
            })))
            .mount(&server)
            .await;
        let mut popular = list("m", "movie", &["Drama"], 8, 2026);
        // In "Continue watching" in the personal test; never shown in the feed then.
        popular.push(meta("ttstarted", "movie", &["Drama"], 2026, json!({})));
        serve(
            &server,
            "/catalog/movie/top.json",
            json!({ "metas": popular }),
        )
        .await;
        serve(
            &server,
            "/catalog/series/top.json",
            json!({ "metas": list("s", "series", &["Drama"], 6, 2026) }),
        )
        .await;
        serve(
            &server,
            "/catalog/movie/year/genre=2026.json",
            json!({ "metas": list("n", "movie", &["Comedy"], 6, 2026) }),
        )
        .await;
        // Shares two titles with Popular, which keeps them.
        let mut featured = list("f", "movie", &["Drama"], 6, 1990);
        featured.extend(list("m", "movie", &["Drama"], 2, 2026));
        serve(
            &server,
            "/catalog/movie/imdbRating.json",
            json!({ "metas": featured }),
        )
        .await;
        let mut horror = list("h", "movie", &["Horror"], 24, 2015);
        horror.push(meta(
            "tt0063350",
            "movie",
            &["Horror", "Thriller"],
            1968,
            json!({}),
        ));
        serve(
            &server,
            "/catalog/movie/top/genre=Horror.json",
            json!({ "metas": horror }),
        )
        .await;
        let mut classics = list("c", "movie", &["Horror", "Thriller"], 12, 1972);
        classics.push(meta(
            "ttromero",
            "movie",
            &["Horror", "Thriller"],
            1978,
            json!({"director": ["George A. Romero"], "cast": ["Ken Foree"]}),
        ));
        serve(
            &server,
            "/catalog/movie/imdbRating/genre=Horror.json",
            json!({ "metas": classics }),
        )
        .await;
        serve(
            &server,
            "/catalog/movie/top/genre=Thriller.json",
            json!({ "metas": list("t", "movie", &["Thriller"], 10, 2000) }),
        )
        .await;
        // Search results come without genres or people.
        serve(
            &server,
            "/catalog/movie/top/search=[^/]*.json",
            json!({ "metas": [{"id": "ttsearch", "type": "movie", "name": "Martin", "releaseInfo": "1977"}] }),
        )
        .await;
        serve(
            &server,
            "/meta/movie/tt0063350.json",
            json!({ "meta": meta("tt0063350", "movie", &["Horror", "Thriller"], 1968, json!({
                "name": "Night of the Living Dead",
                "director": ["George A. Romero"], "cast": ["Duane Jones", "Judith O Dea"]
            })) }),
        )
        .await;
        let now = now_millis();
        let released = |days: i64| stremionow_sync::format_timestamp(now - days * DAY);
        serve(
            &server,
            "/meta/series/ttshow.json",
            json!({ "meta": meta("ttshow", "series", &["Drama"], 2024, json!({
                "videos": [
                    {"id": "ttshow:0:1", "season": 0, "episode": 1, "released": released(1)},
                    {"id": "ttshow:1:1", "season": 1, "episode": 1, "released": released(300)},
                    {"id": "ttshow:1:2", "season": 1, "episode": 2, "released": released(2), "title": "Two"},
                    {"id": "ttshow:1:3", "season": 1, "episode": 3}
                ]
            })) }),
        )
        .await;
        server
    }

    async fn core_with(server: &MockServer) -> (Arc<StremioCore>, tempfile::TempDir) {
        let directory = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(directory.path().to_string_lossy().into_owned()).expect("core");
        let url = format!("{}/manifest.json", server.uri());
        let preview = core.preview_addon(url.clone()).await.expect("previews");
        core.install_addon(url, preview.addon.fingerprint)
            .await
            .expect("installs");
        (core, directory)
    }

    fn ids(section: &HomeSection) -> Vec<&str> {
        section.entries.iter().map(|e| e.item.id.as_str()).collect()
    }

    #[tokio::test]
    async fn a_new_user_gets_the_catalogs_and_a_trending_hero() {
        let server = cinemeta().await;
        let (core, _dir) = core_with(&server).await;
        let (listener, updates) = listener();
        let feed = core.home_feed(listener).await.expect("home");
        assert!(feed.complete);
        let roles: Vec<_> = feed
            .sections
            .iter()
            .map(|s| match &s.reason {
                HomeReason::Catalog { catalog } => (catalog.kind.as_str(), catalog.role),
                other => panic!("no personal rows without history: {other:?}"),
            })
            .collect();
        assert_eq!(
            roles,
            [
                ("movie", CatalogRole::Trending),
                ("series", CatalogRole::Trending),
                ("movie", CatalogRole::NewReleases),
                ("movie", CatalogRole::Featured),
            ],
            "the year catalog shows this year's releases; one that needs ids does not show"
        );
        assert_eq!(
            ids(&feed.sections[3]),
            ["ttf00", "ttf01", "ttf02", "ttf03", "ttf04", "ttf05"],
            "titles already in Popular are not repeated"
        );
        assert_eq!(feed.hero.len(), HERO_PICKS);
        assert_eq!(feed.hero[0].id, "ttm00");
        assert_eq!(feed.hero[1].id, "tts00", "movies and series take turns");
        let updates = updates.lock().expect("lock");
        assert!(updates.last().is_some_and(|last| last == &feed));
    }

    #[tokio::test]
    async fn the_cache_answers_first_and_offline() {
        let server = cinemeta().await;
        let (core, _dir) = core_with(&server).await;
        let first = core.home_feed(listener().0).await.expect("home");
        drop(server);
        // A change to the history works the home out again, from the cache.
        core.add_to_library(MetaItem {
            id: "ttsaved".into(),
            kind: "movie".into(),
            name: "Saved".into(),
            poster: Some("http://insecure/p.jpg".into()),
            background: None,
            logo: None,
            description: None,
            release_info: None,
            imdb_rating: None,
            genres: vec![],
        })
        .expect("saves");
        let (listener, updates) = listener();
        let again = core.home_feed(listener).await.expect("offline home");
        assert_eq!(again.sections.len(), first.sections.len() + 1);
        let list = &again.sections[0];
        assert_eq!(list.reason, HomeReason::FromYourList);
        assert_eq!(
            list.entries[0].item.poster, None,
            "plain HTTP images are dropped"
        );
        let updates = updates.lock().expect("lock");
        assert!(updates.len() >= 2, "the cached home comes first");
        assert!(!updates[0].complete && updates[0].sections.iter().all(|s| !s.loading));
    }

    #[tokio::test]
    async fn personal_rows_come_first_and_each_title_shows_once() {
        let server = cinemeta().await;
        let (core, _dir) = core_with(&server).await;
        let now = now_millis();
        {
            let store = core.store().expect("store");
            store
                .mark_watched("tt0063350", "tt0063350", now - DAY)
                .expect("marks");
            store
                .add_to_library(&LibraryEntry {
                    id: "ttshow".into(),
                    kind: "series".into(),
                    name: "Show".into(),
                    poster: None,
                    added_at: now - 10 * DAY,
                })
                .expect("saves");
        }
        core.save_progress(WatchProgress {
            id: "ttstarted".into(),
            kind: "movie".into(),
            name: "Started".into(),
            poster: None,
            background: None,
            video_id: "ttstarted".into(),
            season: None,
            episode: None,
            position_ms: 600_000,
            duration_ms: 6_000_000,
            updated_at: 0,
        })
        .expect("saves progress");

        let feed = core.home_feed(listener().0).await.expect("home");
        let reasons: Vec<_> = feed.sections.iter().map(|s| &s.reason).collect();
        assert_eq!(reasons[0], &HomeReason::NewEpisodes);
        assert_eq!(
            reasons[1],
            &HomeReason::BecauseYouWatched {
                title: "Night of the Living Dead".into()
            }
        );
        assert_eq!(
            reasons[2],
            &HomeReason::MoreGenre {
                genre: "Horror".into()
            }
        );
        assert!(
            !reasons.contains(&&HomeReason::FromYourList),
            "the saved series is in New episodes already"
        );

        let new = &feed.sections[0].entries;
        assert_eq!(new.len(), 1);
        assert_eq!(
            new[0].episode.as_ref().map(|e| e.id.as_str()),
            Some("ttshow:1:2"),
            "released since it was saved; not the special, not the undated one"
        );

        let because = ids(&feed.sections[1]);
        assert_eq!(because[0], "ttromero", "same director, genres and era");
        assert!(
            because.contains(&"ttsearch"),
            "found by the director's name"
        );
        assert!(!because.contains(&"tt0063350"), "not the title itself");
        assert_eq!(because.len(), PERSONAL_ROW_ITEMS);

        let mut all: Vec<&str> = feed.sections.iter().flat_map(ids).collect();
        assert!(
            !all.contains(&"ttstarted"),
            "in Continue watching, so in no row"
        );
        let count = all.len();
        all.sort_unstable();
        all.dedup();
        assert_eq!(all.len(), count, "no title in two rows");
        assert!(feed.hero.iter().all(|h| h.background.is_some()));
        assert!(
            ids(&feed.sections[1]).contains(&feed.hero[0].id.as_str()),
            "the hero leads with a personal pick"
        );
    }

    #[tokio::test]
    async fn fails_only_when_no_catalog_answers() {
        let server = cinemeta().await;
        let (core, _dir) = core_with(&server).await;
        server.reset().await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(500))
            .mount(&server)
            .await;
        assert!(matches!(
            core.home_feed(listener().0).await,
            Err(CoreError::Network { .. })
        ));
    }

    #[tokio::test]
    async fn suggests_titles_like_one_without_what_was_watched() {
        let server = cinemeta().await;
        let (core, _dir) = core_with(&server).await;
        core.store()
            .expect("store")
            .mark_watched("ttromero", "ttromero", now_millis())
            .expect("marks");
        let similar = core
            .similar_titles("movie".into(), "tt0063350".into())
            .await
            .expect("similar");
        let ids: Vec<_> = similar.iter().map(|i| i.id.as_str()).collect();
        assert_eq!(ids.len(), SIMILAR_TITLES);
        assert!(!ids.contains(&"tt0063350") && !ids.contains(&"ttromero"));
        assert_eq!(ids[0], "ttsearch", "found by its director's name");
        assert!(
            ids[1].starts_with("ttc"),
            "then classics of the same genres and era"
        );
        assert!(matches!(
            core.similar_titles("movie".into(), "ttmissing".into())
                .await,
            Err(CoreError::NotFound)
        ));
    }

    #[tokio::test]
    async fn details_come_from_the_next_addon_when_one_does_not_have_them() {
        // Listed first and claiming every id, as an addon with `meta` and no `idPrefixes` does,
        // but it knows only one title: it says null for one and fails for the rest.
        let greedy = MockServer::start().await;
        serve(
            &greedy,
            "/manifest.json",
            json!({
                "id": "community.greedy", "version": "1.0.0", "name": "Greedy",
                "resources": ["meta"], "types": ["movie", "series"], "catalogs": []
            }),
        )
        .await;
        serve(&greedy, "/meta/movie/ttnull.json", json!({ "meta": null })).await;
        serve(
            &greedy,
            "/meta/movie/ttown.json",
            json!({ "meta": meta("ttown", "movie", &["Drama"], 2020, json!({"name": "Own"})) }),
        )
        .await;
        let cinemeta = cinemeta().await;
        serve(
            &cinemeta,
            "/meta/movie/ttnull.json",
            json!({ "meta": meta("ttnull", "movie", &["Drama"], 2020, json!({})) }),
        )
        .await;
        let (core, _dir) = core_with(&greedy).await;
        let url = format!("{}/manifest.json", cinemeta.uri());
        let preview = core.preview_addon(url.clone()).await.expect("previews");
        core.install_addon(url, preview.addon.fingerprint)
            .await
            .expect("installs");

        let null = core.meta("movie".into(), "ttnull".into()).await;
        assert_eq!(null.expect("from Cinemeta").item.id, "ttnull");
        let failed = core.meta("movie".into(), "tt0063350".into()).await;
        assert_eq!(
            failed.expect("from Cinemeta").item.name,
            "Night of the Living Dead"
        );
        let own = core.meta("movie".into(), "ttown".into()).await;
        assert_eq!(own.expect("from the first").item.name, "Own");
        assert!(
            core.meta("movie".into(), "ttmissing".into()).await.is_err(),
            "no addon has it"
        );
        assert!(
            core.similar_titles("movie".into(), "ttnull".into())
                .await
                .is_ok(),
            "the home's lookups pass it over too"
        );
    }

    #[test]
    fn a_catalog_listed_twice_makes_one_row() {
        let addon = |url: &str| InstalledAddon {
            url: stremionow_protocol::AddonUrl::parse(url).expect("valid url"),
            manifest: serde_json::from_value(json!({
                "id": "x", "version": "1", "name": "X", "resources": ["catalog"],
                "catalogs": [
                    {"type": "movie", "id": "top"},
                    {"type": "series", "id": "top"},
                    {"type": "movie", "id": "top", "name": "Again"}
                ]
            }))
            .expect("valid manifest"),
        };
        let addons = [
            addon("https://a.example/manifest.json"),
            addon("https://a.example/manifest.json"),
            addon("https://b.example/manifest.json"),
        ];
        let keys: Vec<_> = home_catalogs(&addons)
            .into_iter()
            .map(|(c, _)| format!("{}/{}/{}", c.addon_manifest_url, c.kind, c.catalog_id))
            .collect();
        assert_eq!(
            keys,
            [
                "https://a.example/manifest.json/movie/top",
                "https://a.example/manifest.json/series/top",
                "https://b.example/manifest.json/movie/top",
                "https://b.example/manifest.json/series/top",
            ]
        );
    }

    #[test]
    fn reads_release_dates_with_or_without_a_time() {
        let day = stremionow_sync::parse_timestamp("2024-05-01T00:00:00Z").expect("valid");
        assert_eq!(release_ms("2024-05-01"), Some(day));
        assert_eq!(release_ms(" 2024-05-01 "), Some(day));
        assert_eq!(release_ms("2024-05-01T00:00:00.000Z"), Some(day));
        assert_eq!(
            release_ms("2024-05-01T12:00:00Z"),
            Some(day + 12 * 3_600_000)
        );
        for bad in [
            "",
            "2024",
            "2024-5-1",
            "2024-13-01",
            "2024-05-0x",
            "01/05/2024",
        ] {
            assert_eq!(release_ms(bad), None, "{bad}");
        }
    }

    #[test]
    fn reads_episode_numbers_of_the_title() {
        assert_eq!(episode_number("tt1", "tt1:2:5"), Some((2, 5)));
        assert_eq!(episode_number("kitsu:7", "kitsu:7:5"), None);
        assert_eq!(episode_number("tt1", "tt2:1:1"), None);
        assert_eq!(episode_number("tt1", "tt1"), None);
    }
}
