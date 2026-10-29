# ADR-0057: Sport fixtures from football-data.org with the viewer's own token, team names from Wikidata, and no channel claims

- **Status:** Accepted (data layer built 2026-10-29; screens to follow; NOT YET tried with a real token or on any device)
- **Date:** 2026-10-29
- **Spec:** owner's plan, round 4 (*Round 4 - Sport and Arabic.md*: sport data layer, x12, 4.16, 4.17, s1–s3); owner's
  rulings of 29 Oct (free sources only, no half solutions, nothing he has to maintain, x11 dropped)

## Context

The owner wants to find his teams' matches, be reminded, and see what is on now, without paying for a feed and without
correcting anything by hand. He dropped "which of your channels shows the match" (x11): no free source says it reliably.
Saudi, Lebanese and Gulf leagues have no free fixtures source; for those, Search still finds the matches his own guide
lists.

## Decision

- **Fixtures:** football-data.org API v4, free plan, with **the viewer's own token** (like TMDB's key, ADR-0038). The
  token is checked before it is kept, lives only in the platform secret store (`CredentialRef("football-data-token")`),
  travels only as the `X-Auth-Token` request header through the transport's *sensitive* headers (never in a URL, never
  logged, never in an error), and is never in a setting, a backup or an export. Removing it forgets every fixture and
  table read with it; what the viewer follows stays.
- **What is read:** only what the viewer follows (a team or a competition, one press, x12): matches from yesterday to two
  weeks ahead, and a followed competition's table. At most once in each six-hour slot of the viewer's day (from
  midnight, 06:00, noon, 18:00), and at once for something just followed. One request every 7.5 s (eight a minute; the
  free plan allows ten). Never while something plays: a refresh stops before its next request and is tried again later.
  A refused token or a "too many requests" answer stops the refresh and keeps what was read.
- **Team names:** Wikidata (free, no key): the labels and aliases in Arabic, French and English of the football club
  Wikidata finds for a followed team, read once per 90 days, kept as folded search names (`sport_alias`), so "الهلال" and
  "Al Hilal" find the same team. Coverage of smaller Arab clubs is NOT YET VERIFIED.
- **No channel claims:** nothing stores, guesses or shows which channel carries a match. A match opens a search of the
  viewer's own guide for its two teams (4.16); the guide's rows are shown as the guide writes them, only reordered.
- **Storage:** the round's migration (`16.sqm`, schema 17) holds `sport_follow`, `sport_team`, `sport_alias`,
  `sport_fixture`, `sport_standing`, `sport_reminder`, `sport_fetch`; not per source. A second migration (`17.sqm`,
  schema 18) adds the catalogue, `sport_competition` and `sport_team_competition`: the competitions the token can read
  and their teams, read once a week (one request per competition; one the plan refuses, a 403, is skipped), so Search
  offers only what can be followed. `SportStore` in `shared/storage`,
  `SportService` in `shared/ingestion`, `FootballDataClient` and `WikidataClient` in `shared/protocols`. No new library.

## Alternatives considered

- **SofaScore and similar:** no public API; scraping breaks and is not ours to take.
- **Paid feeds (live scores, line-ups, broadcasters):** the owner wants nothing paid.
- **A shared Luz token:** one token for every viewer would be a secret in a public repository and a shared rate limit.
- **Matching matches to channels from guide text (x11):** dropped by the owner; a wrong "certain" channel is worse than
  none.

## Consequences

- `FootballDataClientTest`, `WikidataClientTest`, `SportStoreTest` and `SportServiceTest` (token kept only when
  accepted, once per slot, spacing, rate limit stops and keeps, nothing while playing, removal) run and pass on synthetic
  fixtures. NOT YET VERIFIED with a real token: the competitions a free token can read, and whether `/competitions`
  lists only those, are unchecked.
- `HttpFetcher.get` gained `sensitiveHeaders`, passed to the transport's existing redacted headers.
- "In your guide" (4.3) is built: OK on a programme on now tunes its channel. **Open:** OK on a later programme opens the
  Guide at the present time; it should jump to that hour with the programme focused.
- Screens (follow, Matches and "In your guide" shelves, reminders, Sport now, match-day Home, the table, the phone's
  calendar) follow in this round, each under its own go/no-go in the round 4 brief.
