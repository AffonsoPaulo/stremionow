# 0002. License the project under GPL-3.0-or-later

Date: 2026-11-11
Status: Accepted

## Context

The project is public and wants community contributions and translations. Its key dependencies have these licenses:

| Dependency | License |
| --- | --- |
| libmpv | GPL or LGPL, depending on the build |
| librqbit | Apache-2.0 |
| Inter and Outfit fonts | SIL Open Font License |
| Lucide icons | ISC |

A permissive license for our code would force an LGPL-only build of libmpv and allow closed forks.

## Decision

License all project code under GPL-3.0-or-later. Every source file carries `SPDX-License-Identifier: GPL-3.0-or-later`.

## Consequences

- Any libmpv build can be used.
- Anyone who distributes a modified version must publish its source.
- New dependencies must be GPL-3.0 compatible; check before adding one.
- Distribution through stores that forbid GPL terms may need review.
