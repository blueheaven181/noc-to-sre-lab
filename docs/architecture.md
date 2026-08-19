# Architecture — NOC-to-SRE Lab

This is the living systems-design doc: what's built, why it's shaped this way, and
what's next. Session-by-session narrative (what broke, what got fixed) stays in
`docs/journal/` — this file is the current-state map, not the story of how we got
here. (This file didn't exist before now; it's being created as part of Phase 4
planning, backfilled with Phases 1–3 for context.)

Mirrors a real Azure-hosted gaming platform, deliberately run as self-hosted OSS
on VMware-local infra instead of Azure PaaS equivalents (Azure Monitor / Managed
Grafana / Log Analytics) — the point of the lab is doing the plumbing by hand.

## Project goals

1. Run the full lifecycle of a real production system by hand — build it,
   automate it, observe it, and troubleshoot it when it breaks — the way an
   SRE actually would, not just read about it.
2. **(Added 2026-08-18)** Build an AI-driven observability and automated
   root-cause-analysis (RCA) platform on top of the Phase 4 data (Prometheus
   metrics + ELK logs). Not scoped yet — approach (LLM incident summarizer vs.
   anomaly-detection model vs. full automated remediation agent) is an open
   decision, deliberately deferred until Phase 4 is live and there's actual
   data to build against. Tracked as a future phase below.

### The bigger picture (recap, 2026-08-18)

You're now in Phase 4: observability. The goal there is to bolt on the same
kind of monitoring/logging stack a real SRE team would run — Prometheus for
metrics, Grafana for dashboards, the ELK stack for logs — so the platform
isn't just "up," it's *observable*: you can see its health, catch problems,
and eventually build alerting on top of real signals instead of guessing.

Layered on top of that, you made a deliberate call partway through Phase 4 to
stop doing everything by hand and bring in Ansible — not because the app
needed it, but because "provisioning and configuring infrastructure as code"
is itself a core SRE skill, and this was your first real test of whether
roles written from a plan actually survive contact with a live, imperfect
system (which, as the 2026-08-17/18 session showed with the nginx01
registration gap and the VM resource crashes, they don't always cleanly —
and diagnosing *why* is the actual learning).

So overall: it's less "get a gaming platform running" (that part's done) and
more "run the full lifecycle of a real production system — build it,
automate it, observe it, and troubleshoot it when it breaks — the way an SRE
actually would." And per goal #2 above: the point isn't to end up as someone
who just watches dashboards — it's to build the layer that reasons about
what those dashboards are showing and explains *why* something broke.

## Fleet

| Host        | IP             | OS                  | Role                                   |
|-------------|----------------|---------------------|-----------------------------------------|
| controller01| 192.168.11.10  | RHEL 9.5            | Ansible control node, local connection  |
| nginx01     | 192.168.11.11  | RHEL 9.5            | Reverse proxy / load balancer           |
| mysql01     | 192.168.11.12  | RHEL 9.5            | MySQL — durable game data               |
| redis01     | 192.168.11.13  | RHEL 9.5            | Redis — leaderboard cache               |
| rabbitmq01  | 192.168.11.14  | RHEL 9.5            | RabbitMQ — async event bus              |
| jvmapp01    | 192.168.11.15  | RHEL 9.5            | Spring Boot `game-service` instance A   |
| jvmapp02    | 192.168.11.16  | RHEL 9.5            | Spring Boot `game-service` instance B   |
| winsrv01    | 192.168.11.20  | Windows Server 2022 | CI/CD build agent (live 2026-08-20)     |

All static IPs, one purpose per box (deliberate — see `ansible/README.md`).

## Data flow (Phases 1–3, done)

Player → `nginx01` (round-robin) → `jvmapp01`/`02` → publishes to `rabbitmq01`
→ background listener writes `mysql01` + updates `redis01` cache → leaderboard
reads hit Redis first, MySQL on cold start. Full diagram: `docs/diagrams/how_it_all_connects.png`.

Proven end-to-end on 2026-08-16 (`docs/journal/2026-08-16-...md`): a real
`POST /api/session/complete` → RabbitMQ → MySQL write → Redis update →
`GET /api/leaderboard` reflecting it, in ~2 seconds.

## Phase 4 — Observability (Prometheus + Grafana + ELK)

### Goal

Right now the only way to see what this system is doing is `journalctl` on
whichever box you happen to be SSH'd into. Phase 4 makes fleet health, app
behavior, and logs visible in one place, without logging into eight machines.

### Where it lives

**`controller01`**, via `docker/docker-compose.yml` (the `docker/` directory is
already scaffolded, currently empty). Rationale:

- It's the one box with no app-tier workload competing for CPU/RAM.
- It already has SSH/Ansible reach to everything, which matters later for any
  agent-based log/metric collection that needs credentials.
- Keeps the observability stack itself out of the blast radius of the system
  it's observing — if `mysql01` falls over, the monitoring stack watching it
  shouldn't be on the same box.

In the real Azure platform this maps to Managed Prometheus/Grafana/Log
Analytics running outside the app's own resource group — same separation
principle, different implementation.

### Metrics — Prometheus

**Already deployed** (Phases 1–3 laid the groundwork without calling it out
as observability work):

| Exporter          | Host(s)                          | Port  | Status      |
|--------------------|-----------------------------------|-------|-------------|
| `node_exporter`    | all 6 RHEL hosts (`common` role)  | 9100  | ✅ live      |
| `windows_exporter` | winsrv01 (`windows_common` role)  | 9182  | ✅ live      |

**Net-new for Phase 4:**

| Exporter                     | Host        | Port  | Notes                                                        |
|-------------------------------|-------------|-------|----------------------------------------------------------------|
| `nginx-prometheus-exporter`   | nginx01     | 9113  | Sidecar; current `/nginx_status` is stub_status text, not Prometheus format |
| `mysqld_exporter`              | mysql01     | 9104  | Needs a scoped `exporter` MySQL user, not root                |
| `redis_exporter`               | redis01     | 9121  | |
| RabbitMQ `prometheus` plugin   | rabbitmq01  | 15692 | Built into RabbitMQ ≥3.8, just needs `rabbitmq-plugins enable rabbitmq_prometheus` |
| Spring Boot Actuator + Micrometer | jvmapp01/02 | 8080 `/actuator/prometheus` | App-level metrics: request latency, JVM heap/GC, thread pool — most valuable signal in the whole stack, since it's *our* code |
| `prometheus` server            | controller01| 9090  | Scrapes everything above |

### Logs — ELK

- **Elasticsearch + Logstash + Kibana** on `controller01`, same docker-compose.
- **Filebeat** on every RHEL host, shipping:
  - `nginx01`: access + error logs
  - `jvmapp01`/`02`: `game-service` application log (not just journald — the
    app should log structured JSON so Kibana queries are actually useful)
  - `mysql01`: MySQL error log
  - `rabbitmq01`: RabbitMQ logs
  - all RHEL hosts: `journald` (systemd unit failures, SELinux denials —
    directly useful given how much of Phases 1–3's pain was permission/SELinux
    related)
- **Winlogbeat** on `winsrv01` → same Logstash pipeline. Unblocked as of
  2026-08-20 — the CI/CD build agent workload (see "winsrv01's workload"
  below) is live and generating real logs (runner service events, build
  history) — not yet wired up, but no longer blocked on anything.

### Dashboards — Grafana

`dashboards/` (currently empty) becomes provisioned dashboard JSON, one per
tier, checked into git rather than built by hand in the UI (reproducible, and
survives `docker-compose down`):

- Fleet overview: node_exporter + windows_exporter (CPU/mem/disk/network across all 8 hosts)
- nginx: request rate, upstream response codes, active connections
- game-service (JVM): request latency (p50/p95/p99), JVM heap/GC pauses, RabbitMQ publish rate
- MySQL: connections, slow queries, replication lag (n/a today, future-proofed)
- Redis: hit/miss ratio, memory, evictions
- RabbitMQ: queue depth, consumer count, unacked messages

### Firewall / security (same discipline as Phases 1–3)

Every new exporter port gets the same treatment `node_exporter` already got —
scoped `firewalld` rule to the lab subnet, not `0.0.0.0/0`, and SELinux stays
`enforcing` (add booleans/port contexts as needed rather than disabling).
`controller01` additionally opens 9090 (Prometheus), 3000 (Grafana), 5601
(Kibana) — ideally reachable only from the host machine, not the whole lab
subnet, since these are operator-facing UIs.

### Net-new exporter rollout (updated 2026-08-18)

Implemented as **four separate roles**, not one `monitoring_agents` role as
originally sketched — kept `nginx_exporter`/`rabbitmq_exporter` (no
credentials needed) fully decoupled from `redis_exporter`/`mysqld_exporter`
(need real secrets), so the credential-free two can run today without
waiting on the vault problem below.

| Role                | Tag                | Hosts        | Status |
|----------------------|---------------------|--------------|--------|
| `nginx_exporter`     | `nginx_exporter`    | nginx01      | Ready to run — no vault dependency |
| `rabbitmq_exporter`  | `rabbitmq_exporter` | rabbitmq01   | Ready to run — no vault dependency (just enables the built-in plugin) |
| `redis_exporter`     | `redis_exporter`    | redis01      | **Blocked** — needs `vault_redis_password` (must match the already-live `requirepass` value) |
| `mysqld_exporter`    | `mysqld_exporter`   | mysql01      | **Blocked** — needs `vault_mysql_root_password` + a new `vault_mysqld_exporter_password` for a purpose-built, least-privilege MySQL user (PROCESS + REPLICATION CLIENT + `performance_schema.*:SELECT` only, no access to `gamedb`) |

`inventory/group_vars/vault.yml` still doesn't exist — creating it (with the
*real*, already-working Redis/MySQL credentials, not new/guessed ones) is the
blocking prerequisite for the last two. Spring Boot Actuator/Micrometer for
`game_service` is app-code work (new dependency + config in `app/`, needs a
rebuild like Session 3's deploy), not an Ansible role — tracked separately,
not started yet.

### winsrv01's workload — live as of 2026-08-20: CI/CD build agent

winsrv01 is a self-hosted GitHub Actions runner, building `game-service` as
part of a real CI pipeline — replacing the manual `scp` + `mvn` build done by
hand back in Session 3.

1. **Done.** winsrv01 registered as a self-hosted Actions runner for the repo
   (Java 17 + Maven installed directly, since `winget` isn't available on
   Windows Server 2022 by default — see the 2026-08-19/20 journal addendum).
   Runs as a Windows service, survives reboots without a logged-in session.
2. **Done.** `.github/workflows/build-game-service.yml` triggers on push to
   `app/game-service/**` (plus manual `workflow_dispatch`), builds the
   versioned jar on winsrv01, and commits it to
   `roles/jvm_app/files/game-service-{{ app_version }}.jar` — closing the gap
   that file's absence would have caused if the `jvm_app` role's copy task
   were ever run. **First real run confirmed green end-to-end** — jar landed
   in the repo at 61,204,407 bytes, verified via `git pull` on `controller01`.
3. **Deliberately not automated yet.** Triggering
   `ansible-playbook site.yml --tags jvm_app` after a successful build stays
   a manual step — wiring that in means putting SSH secrets into GitHub
   Actions, a call worth making on purpose later rather than defaulting into
   during this pass.
4. **Now unblocked, not yet done.** winsrv01 has a real recurring workload
   (the runner service itself, every build it runs), so Winlogbeat is worth
   wiring up next — see the Logs — ELK section above.

### Open decisions (need a call before/alongside implementation)

1. **Retention.** Prometheus default 15-day local retention is probably fine
   for a lab; Elasticsearch retention needs an explicit policy or the
   `docker/` volume grows unbounded.
2. **Alerting (Alertmanager).** Not in scope for this pass — dashboards and
   log search first, alerting once there's a baseline of what "normal" looks
   like. Flagging as Phase 5 candidate rather than scope-creeping Phase 4.
3. **Auth on Grafana/Kibana.** Lab-only for now (default creds behind the
   firewalld restriction above), but worth a one-line note here so it doesn't
   quietly ship that way if this ever gets exposed beyond localhost.

## Rollout order (proposed)

1. Resolve winsrv01's role (decision, not a build step).
2. `docker-compose.yml` on controller01: Prometheus + Grafana + Elasticsearch + Logstash + Kibana.
3. New Ansible role `monitoring_agents` (or extend existing per-service roles): installs the net-new exporters + Filebeat, opens firewalld ports.
4. Point Prometheus scrape config at the full target list above; confirm every target is `UP` before touching Grafana.
5. Provision Grafana dashboards from `dashboards/*.json`, one tier at a time, cross-checking each panel against a real metric you can also see with `curl localhost:PORT/metrics`.
6. Wire Filebeat → Logstash → Elasticsearch, confirm log lines land in Kibana before building any saved searches.

## Phase 5 (future, unscoped) — AI-driven observability & automated RCA

**Added 2026-08-18** as a new project goal. Hard dependency on Phase 4 being
live: there's no metrics/log data to reason over otherwise. Direction not yet
decided — options on the table when this gets picked up:

- **LLM incident summarizer** — on alert, an agent pulls the relevant
  Prometheus window + ELK logs and produces a plain-English "here's what
  likely happened" summary for a human to review.
- **Anomaly detection model** — statistical/ML model over the Prometheus
  time-series to flag abnormal behavior ahead of a hard threshold alert.
- **Full automated RCA + remediation agent** — diagnoses *and* acts (restart,
  scale, rollback) on its own findings. Most ambitious, real operational
  risk if wrong, needs guardrails.

Revisit this section once Phase 4's exporters/dashboards/logs are confirmed
working end-to-end — that's the natural point to scope it for real.

## Next up

CI/CD is live (GitHub Actions on a self-hosted winsrv01 runner, builds
`game-service` and commits the jar back automatically — see "winsrv01's
workload" above). Immediate remaining work: Spring Boot Actuator/Micrometer
on `game-service` (dependency not added yet), Grafana dashboards, and
Winlogbeat on winsrv01 (now unblocked). After that: the chaos/postmortem
phase (needs observability to actually be useful — can't write a postmortem
about a failure you couldn't see), and then Phase 5 above once there's real
data to build the AI/RCA layer against.
