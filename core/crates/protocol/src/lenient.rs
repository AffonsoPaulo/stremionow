// SPDX-License-Identifier: GPL-3.0-or-later
//! Forgiving readers for the loosely typed fields addons send.
//!
//! Addons are written by many people and often send `null` for an absent value, a number
//! where a string belongs or the other way around, or a list with a broken entry in it.
//! Stremio shrugs these off, so a field that cannot be read here falls back to its default,
//! and a list drops the entries it cannot read, instead of failing the whole response.

use serde::de::DeserializeOwned;
use serde::{Deserialize, Deserializer};
use serde_json::Value;

/// Treats an explicit `null` like a missing field.
pub(crate) fn null_as_default<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: Deserializer<'de>,
    T: Default + Deserialize<'de>,
{
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

/// Reads a value of `T`, or its default when the value is `null` or has the wrong shape; for
/// objects such as behavior hints whose absence means "nothing special".
pub(crate) fn or_default<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: Deserializer<'de>,
    T: Default + DeserializeOwned,
{
    Ok(serde_json::from_value(Value::deserialize(deserializer)?).unwrap_or_default())
}

/// Reads a list, skipping entries that do not have the expected shape instead of failing the
/// whole response; `null` or anything that is not a list reads as empty.
pub(crate) fn lenient_list<'de, D, T>(deserializer: D) -> Result<Vec<T>, D::Error>
where
    D: Deserializer<'de>,
    T: DeserializeOwned,
{
    Ok(match Value::deserialize(deserializer)? {
        Value::Array(values) => values
            .into_iter()
            .filter_map(|value| serde_json::from_value(value).ok())
            .collect(),
        _ => Vec::new(),
    })
}

/// Reads a list of texts, keeping strings and numbers (as text) and skipping anything else,
/// such as the `null` in `["Drama", null]`.
pub(crate) fn lenient_strings<'de, D>(deserializer: D) -> Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    Ok(strings(Value::deserialize(deserializer)?).unwrap_or_default())
}

/// Like [`lenient_strings`], for lists whose absence means something else than an empty list
/// (id prefixes: none listed means every id); `null` or a non-list reads as `None`.
pub(crate) fn lenient_optional_strings<'de, D>(
    deserializer: D,
) -> Result<Option<Vec<String>>, D::Error>
where
    D: Deserializer<'de>,
{
    Ok(strings(Value::deserialize(deserializer)?))
}

fn strings(value: Value) -> Option<Vec<String>> {
    match value {
        Value::Array(values) => Some(values.into_iter().filter_map(text).collect()),
        _ => None,
    }
}

/// Accepts a string or a number, since addons send ratings and years as either; blank text
/// and anything else read as `None`.
pub(crate) fn lenient_string<'de, D>(deserializer: D) -> Result<Option<String>, D::Error>
where
    D: Deserializer<'de>,
{
    Ok(text(Value::deserialize(deserializer)?))
}

/// Like [`lenient_string`], for text that is empty when absent.
pub(crate) fn lenient_text<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    Ok(text(Value::deserialize(deserializer)?).unwrap_or_default())
}

fn text(value: Value) -> Option<String> {
    match value {
        Value::String(text) if !text.trim().is_empty() => Some(text),
        Value::Number(number) => Some(number.to_string()),
        _ => None,
    }
}

/// Reads a flag sent as a boolean, or as `"true"`/`"false"`; anything else, `null` included,
/// is `false`.
pub(crate) fn lenient_bool<'de, D>(deserializer: D) -> Result<bool, D::Error>
where
    D: Deserializer<'de>,
{
    Ok(match Value::deserialize(deserializer)? {
        Value::Bool(flag) => flag,
        Value::String(text) => text.trim().eq_ignore_ascii_case("true"),
        _ => false,
    })
}

/// Reads a non-negative whole number sent as a number or a numeric string; anything else
/// (null, negative, fractional, text) becomes `None` instead of failing the whole response.
pub(crate) fn lenient_number<'de, D>(deserializer: D) -> Result<Option<u32>, D::Error>
where
    D: Deserializer<'de>,
{
    Ok(whole_number(Value::deserialize(deserializer)?).and_then(|n| u32::try_from(n).ok()))
}

/// Reads a size in bytes sent as a whole number, a number in exponent form such as `1.5e9`,
/// or a numeric string; negative or unreadable sizes become `None`.
pub(crate) fn lenient_size<'de, D>(deserializer: D) -> Result<Option<u64>, D::Error>
where
    D: Deserializer<'de>,
{
    Ok(match Value::deserialize(deserializer)? {
        Value::Number(n) => n.as_u64().or_else(|| n.as_f64().and_then(float_size)),
        Value::String(s) => {
            let s = s.trim();
            s.parse::<u64>()
                .ok()
                .or_else(|| s.parse::<f64>().ok().and_then(float_size))
        }
        _ => None,
    })
}

fn whole_number(value: Value) -> Option<u64> {
    match value {
        Value::Number(n) => n.as_u64(),
        Value::String(s) => s.trim().parse().ok(),
        _ => None,
    }
}

/// A size written as a float, rounded down; sizes beyond what a float holds exactly are
/// approximate anyway.
fn float_size(size: f64) -> Option<u64> {
    // The cast saturates, and the bounds keep NaN and negatives out.
    (size.is_finite() && size >= 0.0).then_some(size as u64)
}
