# Session 5 — First Ansible run against the live fleet, a real infrastructure crisis, and closing the Phase 4 exporter checkpoint

**Date:** 2026-08-17 – 2026-08-18
**Goal:** Bootstrap Ansible for the first time ever against the live fleet (everything up to
this point was built and modified by hand over SSH — see every prior journal entry), get
`node_exporter` and `windows_exporter` confirmed `UP` on Prometheus, and close the "both
existing exporters green" checkpoint before writing any net-new exporter roles.

## What got built

- **Ansible bootstrapped on `controller01` for the first time, ever.** `ansible-core`
  installed (it had never been run against this fleet at all — every host so far was built by
  hand). Generated a dedicated automation key (`~/.ssh/lab_rsa`) and distributed it to all 6
  remote RHEL hosts via `ssh-copy-id`, since nothing had SSH-key trust with `controller01`
  before now.
- **`ansible.cfg` fixed** — `stdout_callback = yaml` referenced a `community.general.yaml`
  callback plugin that's been removed in `community.general` 12.0+; the installed 13.3.0
  broke on startup before a single task could run. Switched to `stdout_callback = default`
  with a `[callback_default] result_format = yaml` section instead.
- **`site.yml` tagged per-tier** (`common`, `nginx`, `mysql`, `redis`, `rabbitmq`, `jvm_app`,
  `windows_common`) with an explicit safety note: the `mysql`/`redis`/`rabbitmq` roles set
  credentials from `vault_*` variables, but `inventory/group_vars/vault.yml` doesn't actually
  exist — nothing was ever vaulted, because nothing was ever automated. Deliberately scoped the
  first-ever live run to `--tags common` only (installs `node_exporter`, touches no app-tier
  state), and left the tier-specific tags as a reviewed decision for later, not a routine re-run.
- **`ansible-playbook site.yml --tags common --ask-become-pass`** run across the full RHEL
  fleet. Fixed 3 more hardcoded `192.168.56.0/24` firewalld rules (mysql/redis/jvm_app roles)
  found while reviewing those roles for safety before deciding not to run them yet.
- **Diagnosed and fixed `nginx01`'s install failure**: `"No package vim-enhanced available."`
  turned out to mean `nginx01` was never registered with `subscription-manager` at all — the
  other 6 hosts were. Registered it, pinned `release --set=9.5` to match the rest of the fleet,
  re-ran scoped to `--limit nginx01`, converged clean (`ok=14 changed=9 failed=0`).
- **All 7 RHEL hosts now fully converged on the `common` role** — `node_exporter` deployed and
  confirmed `7/7 UP` on the Prometheus targets page.
- **`windows_exporter` properly installed as a real Windows service on `winsrv01`** —
  `LISTEN_PORT=9182`, firewalled to the lab subnet only, `StartupType Automatic`. Turned out
  the "already deployed ✅ live" note in `architecture.md` was wrong: nothing was registered as
  a service, no binary existed anywhere on the VM — whatever showed it working before was
  almost certainly a manually-started foreground process that a reboot wiped out.
- **Both existing exporters (`node_exporter`, `windows_exporter`) confirmed `UP`** — closes the
  checkpoint that was set before touching the net-new exporter roles.
- **Added a new project goal to `architecture.md`**: an AI-driven observability / automated
  root-cause-analysis layer, tracked as an unscoped future Phase 5, deliberately deferred until
  Phase 4's metrics/logs are actually live and there's real data to build against.

## What actually went wrong (the big one, and it was a real one)

- **A genuine infrastructure crash mid-session** — kernel-level NVMe I/O timeouts and XFS
  filesystem shutdowns on `jvmapp01`, `jvmapp02`, and `controller01`, caught live via VMware
  console screenshots (`nvme0: I/O tag ... timeout, disable controller`,
  `Identify Controller failed (-4)`, `XFS (dm-0): log I/O error -5`, soft lockups, core-dump
  pipe failures). Two stacked root causes, not one:
  - **Host-wide:** running 8 VMs plus the new Docker observability stack simultaneously pushed
    the Windows host's committed memory past physical RAM.
  - **Per-VM:** `controller01` and `jvmapp01` had only ever been allocated ~1.6GB RAM each,
    which was already causing severe internal swap thrashing on its own, independent of host
    load.
  Fixed with hard power-cycles (guest OSes were fully unresponsive — `systemctl`/`shutdown`
  themselves returned I/O errors) and increasing VM memory allocations in VMware (`controller01`
  1.6GB → 4096MB; similar bump recommended for `jvmapp01`). Explicitly avoided blanket-bumping
  and powering on all 8 VMs at once, to not immediately recreate the host-wide version of the
  same problem.
- **`ssh-copy-id` repeatedly failing with "connection reset by peer"** against `jvmapp01`/
  `jvmapp02` — not an SSH config problem, the VMs were mid-crash or rebooting. Confirmed by
  checking the actual VMware console rather than retrying the SSH command blind.
- **`ansible-playbook: site.yml could not be found`**, more than once — running the command
  from `~` instead of `~/noc-to-sre-lab/ansible`, usually from pasting several commands
  together with the working directory assumption wrong for the last one.
- **Host disk hit 100% full mid-session**, forcing a full emergency shutdown of every VM and
  the host PC itself. In hindsight, this is probably a *better* explanation for at least some of
  the earlier NVMe/XFS symptoms than RAM alone — a VM's virtual disk is just a file on the
  host's real disk, and VMware failing to grow/write a `.vmdk` because the host has no space
  left presents inside the guest exactly like a storage-hardware failure. Worth remembering:
  check both host memory commit *and* host disk free space when a guest shows storage errors,
  not just one.
- **The `windows_exporter` "still down" mystery** — every diagnostic and fix (MSI install,
  firewall rule, `Get-Service` check, all showing success) was actually being run against the
  physical host PC's own PowerShell window, not the `winsrv01` VM. Two Windows terminal windows
  look identical; nothing about the prompt made it obvious which machine was actually being
  used. Only caught because `Get-Service windows_exporter` and a full-drive binary search both
  came back completely empty even right after an apparently successful install — redid
  everything after confirming `hostname` returned `winsrv01` inside the actual VM console.
- **First real install attempt inside the VM failed with MSI exit code 1603** — a non-elevated
  PowerShell session. Windows service installs run via `msiexec /qn` fail silently rather than
  prompting for elevation; `([Security.Principal.WindowsPrincipal]...).IsInRole(...Administrator)`
  returning `False` confirmed it before wasting another blind retry.

## Takeaways

- The 2026-08-17 entry's line that "almost none of today's friction was the observability
  stack itself" doesn't hold for this session — and that's actually the point. This was the
  first time automation touched infrastructure that had quietly drifted from what the Ansible
  roles assumed (`nginx01` never registered, `windows_exporter` never actually a persistent
  service). Surfacing that drift is exactly what running IaC for the first time against a
  system that was built by hand is *for* — it's not a sign the roles were wrong, it's the
  reason to run them before trusting them.
- Two unrelated resource problems (host RAM exhaustion, host disk exhaustion) produced nearly
  identical symptoms inside the guest VMs. A guest's "storage is failing" signal doesn't by
  itself tell you which host-side resource is actually the bottleneck — check memory commit
  and disk free space both.
- Both of today's most confusing moments ("blank screen, no error" and "the fix isn't taking
  effect") turned out to have completely mundane explanations — still booting, and wrong
  physical machine — rather than anything actually broken. Worth checking the boring
  explanation (what machine am I even on right now?) before escalating to deeper debugging.
- Running `hostname` as the first command in any new terminal session is now a standing habit
  worth keeping — this is the second host/guest mix-up on this project (the earlier Windows
  dev-box vs. `controller01` SSH confusion, and now host PC vs. `winsrv01` VM).

## Fleet status

All 7 RHEL hosts converged on the Ansible `common` role, `node_exporter` confirmed `7/7 UP`.
`winsrv01` has a properly installed, auto-starting `windows_exporter`, confirmed `UP`.
`controller01`'s Docker observability stack (Prometheus/Grafana/Elasticsearch/Logstash/Kibana)
survived a full power-cycle cleanly, Elasticsearch reporting healthy again within ~2 minutes.
`mysql01`, `redis01`, `rabbitmq01`, `jvmapp01`, `jvmapp02`, and `nginx01` are currently powered
off (left off deliberately after the disk-space scare) — `common`-role convergence on all of
them was already confirmed before the shutdown, so no re-work needed there, just a careful
power-on.

## Next up

Free up more host disk headroom before bringing the rest of the fleet back up, to avoid
immediately recreating tonight's situation. Power the remaining 6 RHEL hosts back on
deliberately, not all at once. Then: write the `monitoring_agents` Ansible role (or extend the
existing per-service roles) for the net-new exporters — `nginx-prometheus-exporter`,
`mysqld_exporter`, `redis_exporter`, RabbitMQ's built-in `rabbitmq_prometheus` plugin, and
Spring Boot Actuator/Micrometer on `game-service` — plus Filebeat/Winlogbeat for the ELK side.
Still open: `winsrv01`'s actual workload (unresolved since Phase 3), and reconciling the
`vault_mysql_root_password` / `vault_gameapp_db_password` / `vault_rabbitmq_password` /
`vault_redis_password` variables against what's actually live before ever running the
`mysql`/`redis`/`rabbitmq` role tags — no vault file exists yet.
