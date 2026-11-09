# Session 6 — Starting the casino-lab phase: Stage 0 prep

**Date:** 2026-11-01
**Goal:** Before writing any casino-specific feature (accounts, wallet,
vendors), get the ground under `game-service` solid: an explicit memory
ceiling instead of an unset default, a real migration tool managing the
database schema instead of Hibernate quietly auto-altering it, and proof
- not an assumption - that swapping schema ownership didn't change the
leaderboard's behavior.

## Why this phase exists

Extending the lab into a play-money casino-style simulation: customer
accounts, a wallet, a couple of stand-in game vendor integrations, a back
office, and a monitored security scenario. Play money only, built from
scratch, no real payment processing, nothing licensed as a real gambling
product - the point is practicing the kind of platform work that a
money-moving system demands (data integrity, fraud-style monitoring,
careful rollouts), on top of infrastructure this repo already has. Full
shape of it is in `docs/casino-lab-design.md`.

Stage 0 adds nothing casino-specific yet. It's entirely about not building
the next few stages on a shaky foundation.

## What got built

- **Explicit JVM heap for `game-service`.** It had run since first
  deployment with no `-Xms`/`-Xmx` set at all - the JVM picked a default
  off whatever memory the host reported, untested against any real
  ceiling. Added `jvm_heap_min`/`jvm_heap_max` to
  `ansible/roles/jvm_app/defaults/main.yml` (256m/512m, deliberately
  conservative for this lab's VM sizing) and wired them into the
  `ExecStart` line in `game-service.service.j2`. Not yet applied to the
  live fleet - this is a prepared change on a branch, not a deploy.

- **Flyway replacing Hibernate's `ddl-auto: update`.** `application.yml`
  has said "use migrations in real prod" in a comment since the day it
  was written (see the 2026-08-xx dashboard work) - this is that. Added
  `flyway-core`/`flyway-mysql` to `pom.xml`, wrote
  `V1__baseline_players.sql` to recreate the `players` table exactly as
  Hibernate had been creating it (same columns, types, constraints - not
  a schema change), and switched `ddl-auto` to `validate` so Hibernate
  checks the mapping instead of altering anything. `spring.flyway.
  baseline-on-migrate`/`baseline-version: 1` handles the real lab's
  mysql01, which already has this table from the old auto-ddl behavior -
  Flyway treats V1 as already applied there instead of trying to
  re-create an existing table.

- **A Docker proof harness for the leaderboard**, since "I reasoned
  through it and it should be fine" isn't the standard this project holds
  itself to (see: the Winlogbeat saga). Added `Dockerfile` and
  `docker-compose.test.yml` under `app/game-service/` - real MySQL,
  Redis, and RabbitMQ containers plus the actual app, built from source,
  no shortcuts. `STAGE0-PROOF.md` in the same folder has the exact steps:
  exercise the real `/api/session/complete` → RabbitMQ → MySQL write
  path, confirm `/api/leaderboard` reads it back from Redis, flush Redis,
  confirm the MySQL fallback path reads the same data back through the
  now-Flyway-managed table. Requires Docker, which this environment
  doesn't have - the harness is built and ready, running it and recording
  the actual result is the next step.

- **`docs/casino-lab-design.md`** - the public-safe version of the plan.
  No employer details, no proprietary specifics - just what's being
  built, why this domain was chosen, and how the stages are gated. The
  actual working plan with more context stays private, outside this repo.

## Proof result (2026-11-09): it passed

Finally ran the actual Docker proof today, after being stuck for over a
week on a Docker Desktop disk problem that the hardware upgrade fixed. In
plain terms, here's the one question this was all about: the database
table the app uses used to be built automatically by the app itself,
silently, every time it started. Now it's built from a written, numbered
instruction file instead (that's what "Flyway" does), and the app just
double-checks the table looks right rather than changing it. The worry
was that switching from "automatic and silent" to "written down and
checked" might accidentally change how the leaderboard behaves. It
didn't.

Three things were checked, in order:

1. **Did the new setup even build correctly?** Started the app alongside
   real (but temporary, throwaway) copies of the database, cache, and
   message queue, all in containers. Watched it start up: the written
   instruction file ran successfully and built the table, the app's own
   double-check agreed the table looked right, and the app came all the
   way up with no errors.
2. **Does finishing a game still work?** Sent the app a fake "a player
   just finished a game" request, the same kind a real player's game
   would send. A couple seconds later, checked the leaderboard - the
   player and their score were there, correct.
3. **Is the data actually saved for real, not just remembered
   temporarily?** Wiped the cache completely, so the app had no choice
   but to go read the real, permanently-stored table directly instead of
   its fast temporary copy. Checked the leaderboard again - same player,
   same score, read straight from the real table.

All three passed. One harmless, cosmetic detail: the score displayed as
`4242.0` from the cache and `4242` from the real table - same number,
just two different ways of writing it, not a data problem.

**Bottom line: the leaderboard behaves exactly the same as it did
before.** The new, more careful way of managing the database didn't
break anything it's supposed to protect.

## Still open

- Nothing left to prove for Stage 0 - it's genuinely done and verified
  now, not just built.
- The changes described above (JVM heap, Flyway, the proof harness
  itself) are being committed now that they're proven - held back on
  purpose until this point, per the "don't commit until it's proven"
  rule for this phase.
- Next real decision: what Stage 1 of the casino-lab actually starts
  with - customer accounts, the wallet, or one of the designed games
  (`docs/casino-lab-games.md`).
