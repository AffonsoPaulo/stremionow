# 0010. Discover addons from Stremio's lists

Date: 2026-11-11
Status: Accepted

## Context

The Addons screen only installed addons from a pasted link, so finding one meant searching the web. The official Stremio app has a catalog of addons with an Official and a Community list, a type filter, search, a Configure button for addons that need settings, and Share.

Where Stremio's lists come from:

- The protocol has an `addon_catalog` resource: an addon can list other addons as `{"addons": [{"transportUrl", "transportName", "manifest"}]}`. Cinemeta declares `addonCatalogs` for `official` and `community`, by type (`all`, `movie`, `series`, `channel`, `tv`, `other`, …). `https://v3-cinemeta.strem.io/addon_catalog/all/community.json` held 97 addons (about 120 KB) when checked, `…/all/official.json` 7 (10 KB). Stremio asks every installed addon that declares the resource.
- `https://api.strem.io/addonscollection.json` is a larger collection (about 320 KB) that the apps do not use for this screen.

The lists name addons the app cannot use: OpenSubtitles v1 (a legacy protocol with no `/manifest.json`), Local Files (`http://127.0.0.1:11470`, Stremio's own local server). 48 of the 97 community addons require configuration first, and configured addons keep their settings, often a debrid key, in the manifest URL.

## Decision

**Source.** Discover reads Cinemeta's `all/official` and `all/community` lists, at a fixed address in the core (`DISCOVER_SOURCE`), not from whatever addons are installed: an addon the user added cannot put its own recommendations in front of them, and a hostile or broken addon cannot fill the screen. Official addons come first and carry an "Official" badge.

**Fetching.** Through the addon client (rustls with the webpki roots, a 15 s timeout, an 8 MiB response limit, no Referer) and the response cache. The lists are fresh for 24 hours and served stale when offline. They are fetched only when the user opens Discover. Each entry is parsed on its own, so one broken manifest drops only itself; a list fails only when its body is not a catalog, and Discover fails only when neither list can be read.

**What is kept.** An entry needs a manifest URL served over HTTPS from a public host: not `localhost`, `.local`, `.internal`, `.lan` or `.home.arpa`, and not a loopback, private, link-local, carrier-grade NAT or unspecified address. So a list from the internet cannot point the app at devices on the user's network. Each addon appears once, by id and by URL, at most 500 per list. Names, descriptions, versions and types are trimmed and cut to a sane length (100, 500, 32 characters, 12 types), and logos must be HTTPS, as for installed addons.

**Installing.** Nothing installs without a confirmation that says what the addon provides (catalogs, information, sources, subtitles), whether it replaces an installed one, whether it needs setting up first, and that addons can see what the user browses and watches. A pasted link goes through the same confirmation: the core downloads the manifest (`preview_addon`) and installs it only after the user agrees. The install is held to what was confirmed: each manifest gets a fingerprint, a SHA-256 of its id, resources, types, catalogs, id prefixes and behavior hints (not its name, version, description or artwork, which change between releases). A previewed manifest is kept in memory (the last 8) and installed as it was shown, without asking the addon again. A Discover addon is asked again at install, since lists keep snapshots; if its fingerprint no longer matches the one the list showed, nothing is installed and the app shows the fresh manifest for another confirmation. So there is no gap between what the user agreed to and what is installed. SHA-256 comes from ring, which rustls already uses. An addon whose id is already installed, under the same or another URL (it was configured again), replaces the installed one in its place, keeping its priority and enabled state, so an addon is never listed twice.

**Configuring.** For addons with a configuration page (`behaviorHints.configurable` or `configurationRequired`), Configure opens `<base>/configure`, the base including the current settings so the page can start from them, as Stremio does. Phones and the desktop open it in the browser (HTTPS, or HTTP for an addon the user installed from their own network; nothing else). TVs, and devices where no browser opens it, show a QR code. "Add new link" then takes the link the page gives. Addons that require configuration show Configure instead of Install in Discover.

**Sharing.** Share shows the manifest URL with Copy and, on phones, the share sheet; TVs show it as a QR code. A URL that seems to carry settings gets a warning first, since settings can hold private keys: credentials, a query, or any path segment before `/manifest.json` that is 16 characters or longer or holds anything other than letters, digits, `.`, `-` and `_` (`key=value`, `|`, `%`, base64, UUIDs). The rule errs towards warning.

**Keeping the app usable.** Turning off or removing the only enabled addon that gives movies and series their information (Cinemeta, by default) asks first and says what stops working.

## Consequences

- Discovering addons needs no server of ours and matches what Stremio users already know, including the Official and Community split.
- Discover depends on Cinemeta's lists staying where they are; if they move, the screen shows its error state and installing from a link still works.
- The lists describe addons as Stremio last saw them, so a Discover install whose addon has since changed what it serves asks again, with the fresh manifest.
- A public host name can still resolve to a private address. The user confirms every install, and the addon client treats addons the same wherever they live.
- The settings heuristic will warn about some links that hold no secret. Missing a key would be worse.
