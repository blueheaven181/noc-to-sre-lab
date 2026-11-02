# Session 7 — Colour wheel game design, and the fleet-overview Grafana dashboard

**Date:** 2026-11-02
**Goal:** Two separate pieces of real work in one session: design the
fourth casino-lab game (a colour wheel) with the same rigor as the first
three, and finally build the "fleet overview" Grafana dashboard that's
been sitting as "not started" in the architecture doc since Phase 4.

## What got built

- **Colour wheel added to `docs/casino-lab-games.md`.** A 20-segment
  wheel across four colours (red 10, blue 5, green 4, gold 1), modeled on
  how a real "Big Six" wheel keeps the house edge constant no matter
  which colour is bet — pay each colour proportionally more the rarer it
  is, rather than punishing long-shot bets harder. Researched against
  Wikipedia's Big Six wheel, Return to player, and Chuck-a-luck articles
  before picking numbers, not guessed from scratch. Every colour comes
  out to exactly 93% RTP / 7% house edge, checked by computing each
  colour's return separately with a script rather than just averaging.
  That slots the colour wheel into the house-edge lineup between dice and
  slots: coin toss 97.5% → dice 95% → colour wheel 93% → slots 91%, each
  number computed, not picked to look nice. One unbuilt candidate
  (Over/Under 7) is still noted in the doc for a possible later game.

- **`dashboards/fleet-overview.json`** — the dashboard flagged as "not
  started" in `docs/architecture.md` since Phase 4. 12 panels (an
  Overview row of stats, then CPU, Memory, Disk, and Network rows of
  timeseries) covering all 7 Linux hosts via `node_exporter`
  (`controller01`, `nginx01`, `mysql01`, `redis01`, `rabbitmq01`,
  `jvmapp01`, `jvmapp02`), filterable by a `$host` template variable,
  same pattern as `game-service.json`. Deliberately leaves out
  `winsrv01`/`windows_exporter` — its metric names weren't checked
  against a live target, and guessing them would just produce panels
  that silently show no data instead of a real result.

- **`docs/architecture.md` updated** — the "Fleet overview... not
  started" line now reflects the dashboard actually being live and
  verified, same as the `game-service` entry got updated back in August.

## What actually went wrong (the useful part)

- **Forgot the Grafana login entirely.** Fixed by checking the project's
  own docs before guessing — `architecture.md` already noted Grafana was
  still on default/lab creds, which turned out to be exactly right.
- **Browser-pane approval fatigue on a private IP.** Every single action
  against `192.168.11.10` (Grafana's address) needed its own fresh
  one-time approval — navigating, scrolling, and each screenshot all
  asked separately, since private network addresses are treated as
  high-risk regardless of an earlier "always allow this site" grant. Not
  a bug, just a safety behavior that makes driving a private-IP page
  through automation slow; logging in and driving it directly was
  faster.
- **The two-machine lesson, again.** Built `fleet-overview.json` and
  wrote it into the Windows checkout at `D:\PROJECTS\noc-to-sre-lab\
  dashboards\` — and nothing in Grafana changed, because Grafana runs in
  Docker on `controller01`, reading from *that machine's own* clone of
  the repo, not the Windows one. Same shape of problem the 2026-08-17
  session already hit needing separate GitHub auth on `controller01`.
  Fixed for this one file with a direct `scp` from the Windows machine
  straight into `controller01`'s checkout — not a commit or push, just
  getting one file across the gap — and the dashboard picked it up
  within the provider's 30-second poll interval with no restart needed.
- **A tool-level bug, not a repo bug: a remote shell's own writes didn't
  reach the real disk.** Editing files on the Windows machine through a
  remote command shell (used earlier for Stage 0) silently stopped being
  reliable for *new* writes partway through this session — a file write
  and a doc edit both read back successfully through that same shell
  afterward, looking completely normal, but neither one had actually
  reached the real filesystem. It only surfaced because a `git add` run
  directly in PowerShell on the real machine couldn't find a file the
  shell swore existed. Confirmed every other file from this session and
  from Stage 0 directly against the real disk before trusting any of it
  again, and redid the two affected writes through the same transfer
  path already used successfully for the dashboard and the casino-lab
  docs. Lesson: a tool telling you a write succeeded isn't proof by
  itself — cross-check against the real machine, the same standard this
  project already holds application behavior to.

## Still open

- `winsrv01` has no fleet-dashboard panels yet — needs
  `windows_exporter`'s real metric names confirmed against a live target
  first, not guessed.
- The Stage 0 Docker proof still hasn't been run for real — everything
  for it is in place and confirmed genuinely on disk, but
  `STAGE0-PROOF.md`'s actual steps are still pending, now that the
  hardware upgrade should make Docker Desktop usable again.
- This session's docs/dashboard work is committed (`fleet-overview.json`
  as one commit, the design docs + architecture update as a second,
  mirroring the August `game-service` pattern). Stage 0's app changes
  (Flyway, JVM heap, the Docker test harness) are deliberately still
  uncommitted — held until the proof above actually runs, per this
  phase's own "prove it before moving on" rule.
