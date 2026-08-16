# Session 3 — RabbitMQ, the JVM game service, and a full 3-tier system live

**Date:** 2026-08-16
**Goal:** Install RabbitMQ, build and deploy the actual game backend service, and get nginx load-balancing real traffic across two app instances.

## What got built

- **`rabbitmq01`:** RabbitMQ installed from the official Cloudsmith-mirrored repo (GPG keys imported, `.repo` file added — RHEL's own repos only carry an outdated version), management plugin enabled, `/game` vhost and `gameapp` user created, both AMQP (5672) and the management UI (15672) reachable and working.
- **The game service itself:** a real Spring Boot app (`game-service`) — `POST /api/session/complete` publishes an event to RabbitMQ instead of writing to MySQL synchronously; a background listener consumes that event, updates MySQL, and refreshes a Redis-backed leaderboard cache; `GET /api/leaderboard` reads the cache first, falling back to MySQL on a cold start. Source lives in the repo under `app/`.
- **Deployed to both `jvmapp01` and `jvmapp02`** as a dedicated `gameapp` systemd service, environment-based secrets, firewall opened for port 8080.
- **nginx wired up for real** — `game-proxy.conf` load-balancing across both app instances, confirmed with genuine alternating round-robin traffic.
- **End-to-end pipeline proven live:** a real `POST /api/session/complete` call → RabbitMQ → background listener → MySQL write → Redis cache update → `GET /api/leaderboard` reflecting it, all in about two seconds.

## What actually went wrong (the useful part — most of the session, honestly)

- **My own sandbox couldn't reach Maven Central** to compile the app — a real network boundary, not a bug. Worked around it by writing the source here and building it live on `jvmapp01`, which had genuine internet access. Ended up being the right call anyway: building on the actual target machine is closer to reality than handing over a pre-built artifact.
- **MySQL access denied, `gameapp`@`jvmapp01` — the big one.** Turned out to be a genuinely lost/mismatched password, not a bug in the app. Attempting to fix it hit a chain of RHEL-specific surprises: `mysqld_safe` (the classic password-recovery tool) doesn't exist on RHEL's systemd-managed MySQL package at all — it's a leftover from older, non-systemd distributions. The correct modern approach is a **systemd unit override** (`systemctl edit`-style, `ExecStart=` cleared then reset with `--skip-grant-tables`). First attempt at that didn't take effect either, because `systemctl start` is a no-op on an already-running service — had to explicitly `stop` first, then `start`, before the new flags actually applied. Confirmed with `ps aux` rather than trusting `systemctl status`, which is worth remembering as a habit: the unit file can say one thing while the actual running process command line says another.
- **`jvmapp02` crash-looped with "Unable to access jarfile"`** — the jar had never actually been copied there. The `scp` step to pull it from `jvmapp01` failed silently with a misleading "No such file or directory," which was really a permissions problem: `/opt/game-service` is locked to `700`, owned by `gameapp`, so the `ansible` user doing the `scp` couldn't even see inside it to find the file. Fixed by staging a copy in a readable location first, rather than trying to `scp` directly out of a locked-down service directory.
- **Round-robin only ever hit `jvmapp02`, never `jvmapp01`**, even though both app instances were confirmed healthy individually. Root cause: the firewall rule opening port 8080 had only actually been run on `jvmapp02` — `jvmapp01` was silently unreachable the whole time nginx was trying to route to it. `curl`'s exit code `7` ("failed to connect") from `nginx01` was what actually proved this, rather than guessing from nginx's own logs.
- **nginx silently ignored `game-proxy.conf` at first**, serving RHEL's default page instead — `nginx -t` even printed a warning about it (`conflicting server name "_"`) that was easy to skim past. RHEL's packaged `nginx.conf` ships its own built-in default `server {}` block using the same `server_name _` as ours, and it gets parsed first, so it silently won every request. Fixed by removing that built-in block entirely, leaving `game-proxy.conf` as the only server definition.

## Takeaways

- Several bugs today shared the same root shape: **something was only done on one of two identical-looking machines** (the firewall rule) or **assumed to have completed when it hadn't** (the jar copy, the first systemd override attempt). "I ran the same steps on both" is worth actually verifying per-host, not assumed — easy to lose track of which VM a given command actually landed on across a long session with many SSH tabs open.
- Misleading error messages showed up constantly: "No such file or directory" that was really a permissions problem, a systemd status that looked fine while the process underneath was still running the old command. The fix each time was the same instinct — check the thing directly (`ps aux`, actual firewall rules, actual file ownership) instead of trusting the first, most convenient signal.
- The full round-trip through RabbitMQ — publish, consume, write, cache — worked correctly on the very first real test once all the surrounding plumbing (passwords, firewalls, jar files) was actually in place. The messaging pattern itself was never the hard part; getting five machines to agree on how to reach each other was.

## Fleet status

All 8 machines built and now genuinely serving a working 3-tier application: nginx load-balancing real traffic across two JVM app instances, both backed by MySQL, Redis, and RabbitMQ. `winsrv01` still has no assigned workload.

## Next up

Decide `winsrv01`'s actual purpose. Then: push `app/` to GitHub properly, start on the observability stack (Prometheus/Grafana/ELK) so this system's behavior is actually visible somewhere other than `journalctl`, and eventually the CI/CD pipeline so deployments stop being manual `scp` + `systemctl restart`.
