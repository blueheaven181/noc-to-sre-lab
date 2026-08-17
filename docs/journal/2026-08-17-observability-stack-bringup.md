# Session 4 — Phase 4 kickoff: architecture doc + observability stack live on controller01

**Date:** 2026-08-17
**Goal:** Start Phase 4 (Prometheus/Grafana/ELK). Write the design doc first, scaffold the
`docker-compose` stack, fix the fleet's IPs to the real subnet, and get the stack actually
running on `controller01`.

## What got built

- **`docs/architecture.md` created** — didn't exist before today, despite Phases 1–3 assuming
  it did. Now the living systems-design doc: fleet table, data-flow recap, and the full Phase 4
  plan (where the stack lives and why, exporter list, log sources, dashboard list, open
  decisions, rollout order).
- **`docker/` scaffolded**: `docker-compose.yml` (Prometheus + Grafana + Elasticsearch +
  Logstash + Kibana), `prometheus/prometheus.yml` with scrape targets for every host in the
  fleet, Grafana provisioning (datasources auto-wired to Prometheus + Elasticsearch, dashboard
  provider pointed at the repo-root `dashboards/` folder), and a pass-through Logstash pipeline
  (beats input → Elasticsearch output, no per-source parsing yet). Validated locally with
  `docker compose config` before it ever touched `controller01`.
- **Fleet IPs corrected** from the placeholder `192.168.56.x` range to the real
  `192.168.11.x` subnet across `ansible/inventory/hosts.yml`, `docker/prometheus/prometheus.yml`,
  `docs/architecture.md`, and the compose file's header comment.
- **Stack is live on `controller01`**: all five containers (`elasticsearch`, `logstash`,
  `kibana`, `prometheus`, `grafana`) up via `docker compose up -d`, Elasticsearch already
  reporting `(healthy)`.

## What actually went wrong (the useful part)

- **SSH host key warning connecting to `controller01` at its new IP** — expected, not an
  actual MITM: something else had previously held `192.168.11.10` in `known_hosts`. Cleared
  with `ssh-keygen -R <ip>` rather than blindly force-accepting, and cross-checked the new
  fingerprint against the host's own key before trusting it.
- **`firewall-cmd --zone=<your-zone>` typed literally** — the angle brackets were meant as a
  placeholder, not something to paste as-is. Bash parsed `<your-zone>` as an input redirect and
  threw a syntax error instead of running the command at all. Lesson: any command with `<...>`
  placeholder syntax needs the real value substituted before it touches a shell, every time.
- **Two active firewalld zones, only one was right** — `docker` (interface `docker0`, Docker's
  own internal bridge) showed up alongside `public` (interface `ens160`, the actual NIC). Ports
  had to go on `public`; `docker` would've been a silent no-op for anything reachable from the
  lab subnet.
- **`git` isn't on `controller01`** — RHEL's Minimal Install doesn't include it. Straightforward
  `dnf install -y git` fix, but worth remembering for the next fresh RHEL box.
- **Multi-line pasted commands got fed into a live prompt.** `git clone`'s username prompt
  swallowed the next line (`cd docker`) as literal input, garbling both the git auth and the
  following command. Same shape of bug as Phase 1's BITS/curl silent-failure lesson: a
  prompt-driven command needs to be run alone and watched, not pasted as part of a longer block.
- **Private repo over HTTPS, no cached credentials on this machine.** `controller01` is a
  different box from the Windows dev machine, so it had none of the saved GitHub auth. Fixed
  with a fine-grained personal access token (scoped read-only to just this repo, 30-day expiry)
  used as the HTTPS password.
- **`docker compose up` → "permission denied ... docker.sock"** — the `ansible` user wasn't in
  the `docker` group yet. `usermod -aG docker ansible` + `newgrp docker` fixed it without a full
  logout/login and without falling back to prefixing every command with `sudo`.
- **`docker/.env` had to be created by hand, twice** — once on the Windows machine, once again
  on `controller01` — because it's correctly gitignored (holds the Grafana admin password, even
  as a lab placeholder) and git doesn't carry gitignored files across a clone. Not a bug, just a
  process step that's easy to forget is needed on *every* machine that runs the stack.

## Takeaways

- Almost none of today's friction was the observability stack itself — every container came up
  clean on the first `docker compose up -d`. All of it was the same category of problem as
  Phase 1: identity/auth boundaries between machines, and shell/tooling gotchas (placeholder
  syntax, prompt timing, missing packages on a minimal install).
- `controller01` being a genuinely separate machine from the Windows dev box (different
  credentials, different installed tooling, different firewalld state) is a good stand-in for
  the real gap between "my laptop" and "the actual server" in production — worth treating that
  boundary deliberately rather than assuming anything carries over.

## Fleet status

All 8 machines unchanged tier-wise. `controller01` now additionally runs the observability
stack (Elasticsearch, Logstash, Kibana, Prometheus, Grafana) via Docker Compose — all reporting
up, Elasticsearch healthy. Prometheus scrape results not yet confirmed.

## Next up

Confirm `node_exporter` and `windows_exporter` show `UP` on the Prometheus targets page
(`http://192.168.11.10:9090/targets`) — that's the real test of whether the fleet is actually
visible, not just whether the containers started. Then: write the `monitoring_agents` Ansible
role for the net-new exporters (nginx, mysqld, redis, RabbitMQ, game-service Actuator) and
Filebeat/Winlogbeat, and still-open from last session — decide `winsrv01`'s actual workload.
