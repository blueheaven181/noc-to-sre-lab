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

## Late addendum — same night: nginx_exporter and rabbitmq_exporter deployed

Rather than one `monitoring_agents` role, wrote four separate net-new exporter roles
(`nginx_exporter`, `rabbitmq_exporter`, `redis_exporter`, `mysqld_exporter`) so the two with
zero credential dependency could run immediately without waiting on the vault problem below.
Also added a new project goal to `architecture.md`: an AI-driven observability / automated
root-cause-analysis layer, tracked as an unscoped future Phase 5.

- **`nginx_exporter`** (nginx01) and **`rabbitmq_exporter`** (rabbitmq01) run clean —
  `ansible-playbook site.yml --tags nginx_exporter,rabbitmq_exporter`. nginx01 converged on
  the first pass (`ok=10 changed=7`); rabbitmq01 needed a rerun scoped with `--limit
  rabbitmq01` since it was still mid-boot on the first pass (`UNREACHABLE`, not a real
  failure — same "still booting" shape as the winsrv01 blank-screen scare earlier tonight).
  Both `nginx_exporter` and `rabbitmq` jobs confirmed `UP` on Prometheus.
- **`redis_exporter`** and **`mysqld_exporter`** are written but intentionally blocked —
  both need real secrets that don't exist anywhere in the repo yet
  (`inventory/group_vars/vault.yml` still doesn't exist). Deliberately deferred creating that
  file until a less exhausted session, given it's the kind of task where a mistake with a real
  credential matters more than most, and this session ran well past 4am local time.
- Attempted to run `git add`/`git commit` for this batch from the cloud side via the device
  bridge's sandboxed shell — `git add` worked, but `git commit` hit two sandbox-specific
  issues worth remembering: stale `.git/index.lock` files that couldn't be deleted (worked
  around with `mv` instead of `rm`, since rename succeeded where unlink didn't), and no git
  author identity configured in that isolated environment. Ended up staging from the sandbox
  but committing from the normal terminal, where identity was already set up correctly.

## Second late addendum — same night: vault created, all six exporters live

Turned out to still be on-shift and awake well past 4am, so kept going rather than stopping —
verified the real MySQL root and Redis passwords manually first (`mysql -u root -p` + `STATUS;`,
`redis-cli` + `AUTH`) before trusting them to the vault, per the "don't guess, verify" instinct
from earlier sessions.

- **`inventory/group_vars/vault.yml` doesn't work — found and fixed a real bug in the original
  repo scaffolding.** Ansible's `group_vars` auto-loading only recognizes files/directories
  named after an actual inventory group (`all`, `linux_rhel`, etc.). A file just named
  `vault.yml` sitting directly in `group_vars/` is silently never loaded as variables at all —
  `--ask-vault-pass` still prompts for a password (it prompts off the CLI flag, not off whether
  anything actually needs decrypting), which produced a genuinely confusing `'vault_*' is
  undefined` error even with the correct vault password entered. This was a bug in the
  project's own scaffolding from before any of this session's work, not something introduced
  tonight. Fixed by converting `group_vars/all.yml` into a directory
  (`group_vars/all/vars.yml` + `group_vars/all/vault.yml`), which is the correct Ansible
  pattern for "plaintext vars + secrets, merged for the same group."
- **`mysqld_exporter` still failed after the vault fix** — `ok=10 changed=7`, service installed,
  but `systemctl status` showed a crash-loop. `journalctl -u mysqld_exporter -n 50 --no-pager`
  (not the paged `-xeu` form, which buried the real line) showed the actual cause:
  `level=error msg="failed to validate config" section=client err="no user specified in section
  or parent"`. mysqld_exporter v0.15.1 doesn't reliably honor the `DATA_SOURCE_NAME`
  environment variable the role originally used — it expects a `.my.cnf`-style file passed via
  `--config.my-cnf`. Fixed by writing `/etc/mysqld_exporter/.my.cnf` (mode 0600, owned by the
  `mysqld_exporter` service account) and pointing `--config.my-cnf` at it instead.
- Also found `python3-PyMySQL` was missing on mysql01 entirely — it was only ever meant to be
  installed by the original (never-run) `mysql` role. Added it as an explicit prerequisite
  inside `mysqld_exporter`'s own tasks so the role doesn't silently depend on a different,
  still-untested role having run first.
- `redis_exporter` worked cleanly on the first real attempt once the vault was fixed
  (`ok=9 changed=7`, no follow-up bugs) — its environment-variable-based config approach just
  works, unlike mysqld_exporter's.
- **All six exporters confirmed `UP` on Prometheus by the end of the night**: `node_exporter`,
  `windows_exporter`, `nginx_exporter`, `rabbitmq`, `mysqld_exporter`, `redis_exporter`.

## Takeaways (round two)

- Two more real bugs found and fixed tonight, both in *scaffolding written before this session
  even started* (the `group_vars/vault.yml` path, the `DATA_SOURCE_NAME` assumption) — further
  confirms the pattern from earlier: actually running automation against a live system surfaces
  drift and mistakes that just reading the code never would. Every one of tonight's "blockers"
  turned into a small, permanent improvement to the repo.
- `journalctl -xeu` and `journalctl -u ... --no-pager` are not equivalent for debugging — the
  paged/highlighted `-xeu` view can visually bury the one line that actually matters (the
  `level=error` from the application itself) under systemd's own restart-loop noise. When a
  service crash-loops, go straight to the plain, unpaged form and read every line.
- Verifying credentials independently (manual `mysql -u root -p`, manual `redis-cli AUTH`)
  *before* writing them into a vault file caught nothing wrong this time, but was worth doing
  anyway — the cost of checking is a two-minute CLI login, the cost of not checking is a wrong
  password baked into an encrypted file that's a pain to safely re-open and fix later.

## Next up

Six RHEL hosts are still gradually coming back online post-crisis (jvmapp01, jvmapp02 still
off as of this entry) — bring the rest up deliberately, not all at once, watching host
disk/memory headroom. Remaining Phase 4 work: Spring Boot Actuator/Micrometer on `game-service`
(app-code work, not an Ansible role — needs a rebuild like Session 3's deploy) and
Filebeat/Winlogbeat for the ELK log-shipping side. Still open: `winsrv01`'s actual workload
(unresolved since Phase 3). Once logs are flowing too, Phase 4 is functionally complete and
Phase 5 (the AI-driven RCA goal added earlier tonight) becomes buildable for real.
