# Architecture — NOC-to-SRE Lab

This is the living systems-design doc: what's built, why it's shaped this way, and
what's next. Session-by-session narrative (what broke, what got fixed) stays in
`docs/journal/` — this file is the current-state map, not the story of how we got
here. (This file didn't exist before now; it's being created as part of Phase 4
planning, backfilled with Phases 1–3 for context.)

Mirrors a real Azure-hosted gaming platform, deliberately run as self-hosted OSS
on VMware-local infra instead of Azure PaaS equivalents (Azure Monitor / Managed
Grafana / Log Analytics) — the point of the lab is doing the plumbing by hand.

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
| winsrv01    | 192.168.11.20  | Windows Server 2022 | **Unassigned** — see Open Decisions     |

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
- **Winlogbeat** on `winsrv01` → same Logstash pipeline, once winsrv01 has an
  actual workload to generate meaningful logs.

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

### Open decisions (need a call before/alongside implementation)

1. **winsrv01's workload.** The last journal entry flagged this as unresolved
   and it affects what the Windows side of Phase 4 is even monitoring. Options
   discussed nowhere yet — worth a short decision pass before Winlogbeat
   config is worth writing.
2. **Retention.** Prometheus default 15-day local retention is probably fine
   for a lab; Elasticsearch retention needs an explicit policy or the
   `docker/` volume grows unbounded.
3. **Alerting (Alertmanager).** Not in scope for this pass — dashboards and
   log search first, alerting once there's a baseline of what "normal" looks
   like. Flagging as Phase 5 candidate rather than scope-creeping Phase 4.
4. **Auth on Grafana/Kibana.** Lab-only for now (default creds behind the
   firewalld restriction above), but worth a one-line note here so it doesn't
   quietly ship that way if this ever gets exposed beyond localhost.

## Rollout order (proposed)

1. Resolve winsrv01's role (decision, not a build step).
2. `docker-compose.yml` on controller01: Prometheus + Grafana + Elasticsearch + Logstash + Kibana.
3. New Ansible role `monitoring_agents` (or extend existing per-service roles): installs the net-new exporters + Filebeat, opens firewalld ports.
4. Point Prometheus scrape config at the full target list above; confirm every target is `UP` before touching Grafana.
5. Provision Grafana dashboards from `dashboards/*.json`, one tier at a time, cross-checking each panel against a real metric you can also see with `curl localhost:PORT/metrics`.
6. Wire Filebeat → Logstash → Elasticsearch, confirm log lines land in Kibana before building any saved searches.

## Next up

Once this is live: CI/CD pipeline (GitHub Actions building the versioned jar automatically instead of manual `scp`), then chaos/postmortem phase — which needs this observability layer to actually be useful (you can't write a postmortem about a failure you couldn't see).
