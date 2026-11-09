# Casino lab — design note

A new phase of the lab: extending `game-service` and the existing fleet
into a play-money casino-style platform simulation. Customer accounts, a
wallet, a couple of stand-in "game vendor" integrations, an internal back
office, and one security-monitoring scenario, built on top of the
infrastructure this repo already documents in `architecture.md`.

**This is a learning exercise, not a product.** Play money only - nothing
here moves or represents real currency, there's no real payment processor,
and nothing in it is licensed or represented as a real gambling platform.
Every piece is built from scratch for this lab. The reason for choosing
this domain specifically: platforms that move money - casinos, exchanges,
fintech apps - tend to need a particular combination of skills (strict
data integrity, fraud-style monitoring, careful rollout practices) that
a lot of other portfolio projects don't exercise, and that combination is
worth practicing deliberately.

## What's being added

- **Customer accounts** - signup, login, email-based one-time-code
  verification on signup (no real email sent; a local mail-catcher
  container stands in for an outbound mail server), session tracked via a
  Redis-backed cookie.
- **A public-facing website** - simple signup/login pages, and eventually
  the games themselves, in a browser - a real customer-style, click-
  through experience on top of the backend API, not something tested
  only by typing commands. Built after each backend piece it depends on
  has already been proven working on its own, not before.
- **Wallet** - a play-money balance per customer, with every change
  recorded as a transaction rather than just an updated number, so the
  balance can always be checked against its own history.
- **Two stand-in game vendors** - small separate services that mimic how
  a real platform integrates third-party game providers, wired to the
  wallet (placing a bet debits it, a win credits it).
- **Back office** - an internal, staff-only view onto customers and
  transactions. Not part of the public-facing site.
- **One security scenario** - a defined suspicious pattern (e.g. a burst
  of small transactions on one account) with a real alert built to catch
  it, the same kind of signal a monitoring team would watch for.
- **Monitoring** - extending the existing Prometheus/Grafana/ELK stack
  with metrics specific to this phase: deposit/withdrawal failures,
  wallet balance integrity, login and verification-code failure spikes,
  API error rates and latency per endpoint, and real external uptime
  checks rather than only "is the process running."

## How it's staged

Each stage only starts once the previous one is proven working - "proven"
meaning demonstrated against a real running system, not just reasoned
about. Stage 0 is infrastructure prep (explicit memory limits, database
migrations replacing auto-managed schema, a regression check against the
existing leaderboard feature) with no new casino-specific functionality
yet. See `docs/journal/` for the session-by-session record of what was
actually built and verified at each stage.
