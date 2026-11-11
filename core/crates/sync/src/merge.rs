// SPDX-License-Identifier: GPL-3.0-or-later
//! Reconciliation of the local library with the account library.

use std::collections::BTreeMap;

use crate::RemoteLibraryItem;

/// Outcome of [`merge_library`].
#[derive(Debug, Clone, Default, PartialEq)]
pub struct MergeResult {
    /// Items the server does not have or has an older version of; send them with
    /// [`crate::StremioApi::put_library`].
    pub to_upload: Vec<RemoteLibraryItem>,
    /// The reconciled library, one item per id, sorted by id. Store it locally.
    pub merged: Vec<RemoteLibraryItem>,
}

/// Merges the local and remote libraries, item by item.
///
/// Policy, per `_id`:
/// - On both sides: the item with the newer `_mtime` wins as a whole (no field-level merge,
///   matching the official apps). On a tie the remote item wins, so nothing is uploaded for
///   items that did not change. An `_mtime` that is not a valid timestamp counts as older
///   than any valid one. If the local item wins it is also added to `to_upload`.
/// - Only remote: kept.
/// - Only local: kept and added to `to_upload`, since the server has never seen it.
///
/// Removed and temporary items are merged like any other: `removed: true` with a newer
/// `_mtime` is how a removal reaches the other devices, so tombstones must be kept.
/// Duplicate ids within one side are collapsed to their newest entry first.
///
/// The function is pure: callers decide what to filter before uploading (stremio-core, for
/// example, does not upload items of type `other` or removals older than a year).
pub fn merge_library(local: &[RemoteLibraryItem], remote: &[RemoteLibraryItem]) -> MergeResult {
    let local = newest_by_id(local);
    let mut merged = newest_by_id(remote);
    let mut to_upload = Vec::new();

    for (id, local_item) in local {
        let local_wins = merged
            .get(id)
            .is_none_or(|remote_item| is_newer(local_item, remote_item));
        if local_wins {
            to_upload.push(local_item.clone());
            merged.insert(id, local_item);
        }
    }

    MergeResult {
        to_upload,
        merged: merged.into_values().cloned().collect(),
    }
}

/// Indexes items by id, keeping the newest entry of each id (the last one on a tie).
fn newest_by_id(items: &[RemoteLibraryItem]) -> BTreeMap<&str, &RemoteLibraryItem> {
    let mut by_id: BTreeMap<&str, &RemoteLibraryItem> = BTreeMap::new();
    for item in items {
        let keep_existing = by_id
            .get(item.id.as_str())
            .is_some_and(|existing| is_newer(existing, item));
        if !keep_existing {
            by_id.insert(&item.id, item);
        }
    }
    by_id
}

/// Whether `a` was modified strictly after `b`. Invalid timestamps sort first (`None < Some`).
fn is_newer(a: &RemoteLibraryItem, b: &RemoteLibraryItem) -> bool {
    a.mtime_ms() > b.mtime_ms()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(id: &str, mtime_ms: i64) -> RemoteLibraryItem {
        RemoteLibraryItem::new(id, "movie", id, None, mtime_ms)
    }

    fn ids(items: &[RemoteLibraryItem]) -> Vec<&str> {
        items.iter().map(|item| item.id.as_str()).collect()
    }

    #[test]
    fn newer_local_item_wins_and_is_uploaded() {
        let local = [item("a", 2_000)];
        let remote = [item("a", 1_000)];
        let result = merge_library(&local, &remote);
        assert_eq!(result.merged, local);
        assert_eq!(result.to_upload, local);
    }

    #[test]
    fn newer_remote_item_wins_and_nothing_is_uploaded() {
        let local = [item("a", 1_000)];
        let remote = [item("a", 2_000)];
        let result = merge_library(&local, &remote);
        assert_eq!(result.merged, remote);
        assert!(result.to_upload.is_empty());
    }

    #[test]
    fn tie_keeps_remote() {
        let mut local = item("a", 1_000);
        local.name = "local".to_owned();
        let remote = item("a", 1_000);
        let result = merge_library(&[local], std::slice::from_ref(&remote));
        assert_eq!(result.merged, [remote]);
        assert!(result.to_upload.is_empty());
    }

    #[test]
    fn items_on_one_side_are_kept() {
        let result = merge_library(&[item("local", 1)], &[item("remote", 1)]);
        assert_eq!(ids(&result.merged), ["local", "remote"]);
        assert_eq!(ids(&result.to_upload), ["local"]);
    }

    #[test]
    fn newer_removal_propagates() {
        let mut removed = item("a", 2_000);
        removed.removed = true;
        let result = merge_library(std::slice::from_ref(&removed), &[item("a", 1_000)]);
        assert_eq!(result.merged, [removed.clone()]);
        assert_eq!(result.to_upload, [removed]);
    }

    #[test]
    fn invalid_mtime_loses_to_a_valid_one() {
        let mut broken = item("a", 5_000);
        broken.mtime = "garbage".to_owned();
        let valid = item("a", 1_000);
        let result = merge_library(&[broken], std::slice::from_ref(&valid));
        assert_eq!(result.merged, [valid]);
        assert!(result.to_upload.is_empty());
    }

    #[test]
    fn duplicates_collapse_to_the_newest() {
        let remote = [item("a", 3_000), item("a", 1_000)];
        let local = [item("a", 2_000)];
        let result = merge_library(&local, &remote);
        assert_eq!(result.merged, [item("a", 3_000)]);
        assert!(result.to_upload.is_empty());
    }

    #[test]
    fn empty_inputs() {
        assert_eq!(merge_library(&[], &[]), MergeResult::default());
    }
}
