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

## Third late addendum — same night: filebeat log shipping live

Still going. Wrote a new `filebeat` role (nginx access/error logs, MySQL error log, RabbitMQ
logs where applicable, plus journald on every RHEL host — journald is how game-service's own
output gets captured for now, until it has proper structured file logging). No vault
dependency, safe standalone. Bundled in one small addition: since Logstash's beats input
(port 5044, already published by docker-compose) never had an OS-level firewalld rule opening
it, added that as a conditional task within the same role, scoped to `controller01` only.

- Hit one new gotcha: after fixing the vault path to live correctly under `group_vars/all/`,
  Ansible now tries to decrypt it on **every** run, regardless of tags — so `--ask-vault-pass`
  became mandatory even for `filebeat`, which uses zero vault variables itself. Worth
  remembering: fixing the vault to actually load correctly has this side effect on every
  future command, not just the roles that need secrets.
- `ansible-playbook site.yml --tags filebeat --ask-vault-pass --ask-become-pass --limit
  controller01,mysql01,redis01` ran clean (`failed=0` on all three) — the `dnf install` step
  for `filebeat-8.15.1-1` genuinely took a few minutes (first time any host touched Elastic's
  own repo, not GitHub or RHEL's mirrors), which looked like a hang but wasn't.
- **Confirmed working end-to-end**, not just "service started": `curl
  http://192.168.11.10:9200/_cat/indices?v | grep noclab` showed real documents landing
  (`noclab-filebeat-2026.08.19`, 5,380 docs and climbing) — filebeat → Logstash →
  Elasticsearch genuinely works.
- Rolled out to the remaining four RHEL hosts (nginx01, rabbitmq01, jvmapp01, jvmapp02) the
  same night, all clean on the first try (`failed=0` across all four) — no new bugs, since the
  role and the controller01-side firewall rule were already proven against controller01/mysql01/
  redis01. **Filebeat is now live on all 7 RHEL hosts.**

## Takeaways (round three)

- The vault-decryption-on-every-run behavior is a good example of "fixing a bug can change
  behavior elsewhere in ways worth documenting immediately," not just fixing the one thing that
  was broken — every future command against this playbook now needs `--ask-vault-pass`, forever,
  even for vault-free roles like `filebeat`.
- Long as this session ran, working through the crash saga *before* the credential-vault work
  and *before* the exporter/filebeat work turned out to be the right order — every later piece
  went faster specifically because the infrastructure underneath it was actually stable by then.

## Fourth late addendum — 2026-08-19/20: an actual power outage, full-fleet recovery, and winsrv01's CI/CD runner goes live

Fell asleep mid-setup and the power went out — a genuinely unclean shutdown this time, not the
deliberate emergency shutdown from the disk-full scare earlier in this same saga. Every VM and
the host PC itself lost power with zero chance to flush anything to disk, which made this a real
test of whether the fleet would come back clean.

**Recovery, done deliberately rather than just powering everything back on and hoping:**

- Checked host disk free space first, before touching VMware at all, given the disk-full
  incident earlier this week — came back clean, not the same problem recurring.
- Brought VMs up **one at a time**, starting with `controller01`, and ran the same three checks
  on every single host rather than trusting a green "running" icon: `df -h`, `journalctl -p err
  -b --no-pager`, and a service-specific status check (`mysqld`, `redis`, `rabbitmq-server`,
  `nginx`, `game-service`, plus each host's Prometheus exporter).
- **Result: all 7 RHEL hosts came back completely clean.** No XFS journal-replay failures, no
  emergency-mode boots, no corrupted data. A few benign, worth-remembering findings along the
  way, none of which needed a fix:
  - Every host's root filesystem shows as `/dev/mapper/rhel_nginx01-root` regardless of actual
    hostname — cosmetic leftover from cloning all 7 RHEL VMs from a shared template; RHEL's
    installer bakes the LVM volume group name in at install time and cloning never renames it.
  - `controller01` logged one `firewalld ERROR: NAME_CONFLICT: new_policy_object():
    'docker-forwarding'` on boot — a known Docker/firewalld interaction after a restart.
    Verified harmless rather than assumed: all 5 containers (`logstash`, `kibana`, `grafana`,
    `prometheus`, `elasticsearch`) came up `Up`/`healthy`, Prometheus's `/-/healthy` returned
    `200`, Elasticsearch's root endpoint responded normally.
  - `mysql01`'s MySQL reported `"Server is operational"` on start (InnoDB's own signal that
    crash recovery completed with no issues) and `redis01`'s Redis reloaded its persisted data
    correctly (`dbsize` came back `1`, not `0` — confirms it actually loaded from disk rather
    than starting fresh empty).
  - `jvmapp01`/`jvmapp02` both logged a benign `rsyslogd: imjournal ... state file failed /
    ignoring invalid state file` — rsyslog's own bookmark file (tracking how far it's read into
    the systemd journal) got corrupted by the power loss, so rsyslog just discards it and
    resumes from wherever the journal currently is. No data lost from a monitoring standpoint
    either way, since filebeat reads journald directly, not through rsyslog.
  - `redis_exporter` logged one `LOGGED ONCE ONLY` error trying `LATENCY HISTOGRAM`, a Redis
    subcommand this Redis version doesn't support — the exporter notices, logs it exactly once,
    and moves on; doesn't affect any real metric being scraped.
- `game-service` on both jvmapp hosts reconnected to RabbitMQ on its own after restart, actuator
  registered, node_exporter serving — no manual intervention needed anywhere in the app tier.

**winsrv01 — the one host that actually needed real work, since it was mid-setup when the
outage hit:**

- Confirmed `hostname` first before touching anything, per the standing habit from the earlier
  host-PC-vs-VM mix-up.
- `winget` turned out to not exist on this box at all — Windows Server 2022 doesn't ship the
  Microsoft Store / App Installer package the way client Windows does, so any Windows Server
  automation needs a direct-download fallback rather than assuming `winget` is available.
  Pivoted to downloading Temurin 17 directly from Adoptium's versionless "latest" API
  (`api.adoptium.net/v3/installer/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse`) and
  Maven 3.9.16 directly from `dlcdn.apache.org` as a zip.
- Real gotcha: ran both installs, then immediately tried `java -version`/`mvn -version` in the
  *same* PowerShell window — both came back "not recognized." Neither an MSI's environment-
  variable registration nor a `[Environment]::SetEnvironmentVariable(...,"Machine")` call
  applies to an already-running process, only to new ones spawned afterward. A fresh PowerShell
  window fixed `mvn` immediately (Maven's own launcher resolves Java via the `JAVA_HOME`
  variable, not by searching `PATH`), but `java` itself was still missing — the Temurin MSI's
  `FeatureEnvironment` option set `JAVA_HOME` correctly but didn't actually add java's own `bin`
  folder to the machine `PATH`. Fixed by appending it by hand.
- Registered winsrv01 as a self-hosted GitHub Actions runner. One real stumble along the way:
  went to the personal GitHub account Settings page (Payment info / Emails / SSH keys) looking
  for "Actions," which doesn't exist there — that only lives inside a specific *repository's*
  Settings, a visually near-identical but functionally separate page. The registration itself,
  once on the right page, installed cleanly as a Windows service in one step via `config.cmd`'s
  own interactive "run the runner as a service?" prompt (answered `Y`) — a separate `svc.cmd
  install`/`start` fallback wasn't actually needed and threw a harmless "not recognized" error
  when tried afterward, since the service already existed by then.
- Wrote `.github/workflows/build-game-service.yml`: triggers on push to `app/game-service/**`
  (plus a manual `workflow_dispatch` button for on-demand runs), builds with Maven on the
  self-hosted runner, copies the resulting jar to
  `ansible/roles/jvm_app/files/game-service-{{ app_version }}.jar`, and commits it straight back
  to the repo under the workflow's own `github-actions[bot]` identity. Deliberately scoped the
  trigger path so the commit-back can't retrigger itself — the jar lands under
  `ansible/roles/jvm_app/files/`, a path outside the `app/game-service/**` filter.
- One tooling note worth remembering: the device bridge used throughout this project to write
  files directly into the repo folder refused to write into `.github/workflows/` at all,
  returning "protected file" — a deliberate safety restriction, since workflow files control CI
  execution and secrets and shouldn't be silently writable by a remote automation tool. Worked
  around it by hand-pasting the workflow content into a PowerShell here-string directly on the
  machine instead.
- **First manual trigger via `workflow_dispatch` went green end-to-end on the very first
  attempt** — the runner picked up the job within seconds, Maven built successfully (slower
  than future runs will be, since there's no `.m2` dependency cache yet), and the jar committed
  back automatically. Confirmed for real, not just trusted the green checkmark: pulled on
  `controller01` and found `game-service-1.0.0.jar` (61,204,407 bytes) sitting exactly where
  `ansible/roles/jvm_app/tasks/main.yml`'s deploy task expects it — the exact gap Session 3
  flagged as missing, closed for real.

This is the first time this repo has gone from "push code" to "deployable artifact" without a
manual `scp`/build step. Deploying that jar out to jvmapp01/jvmapp02
(`ansible-playbook site.yml --tags jvm_app --ask-vault-pass`) is still a deliberate manual step,
not auto-triggered — wiring that in would mean putting SSH secrets into GitHub Actions, a call
worth making on purpose later rather than defaulting into tonight.

## Takeaways (round four)

- An unplanned outage turned out to be a better resilience test than the earlier deliberate
  shutdown — every host came back with zero data loss or corruption, a genuinely good signal
  that the systemd units and storage config across the fleet are sound, not just "it worked
  because we were careful."
- Checking every host the same systematic way (`df -h`, `journalctl -p err`, service status)
  instead of eyeballing a "running" icon caught nothing broken this time — but that's exactly
  the discipline that *would* catch it if something had actually gone wrong, same principle as
  verifying credentials manually before trusting them to the vault two nights ago.
- Two tools that both "need Java" can behave completely differently after the identical install,
  depending on whether they search `PATH` or read a specific environment variable — worth
  internalizing as a general debugging instinct, not just a one-off Maven/Java quirk.
- Account-level and repository-level Settings pages on GitHub share near-identical visual chrome
  — the URL itself (`github.com/settings/...` vs. `github.com/<owner>/<repo>/settings`) is the
  fastest way to tell which one you're actually looking at.

## Fifth late addendum — 2026-08-20: deploying the CI/CD jar for real surfaces three more bugs, one vault-editing detour, and a stale-git-checkout plot twist

Ran `ansible-playbook site.yml --tags jvm_app --ask-vault-pass` to actually deploy tonight's
freshly-built jar to jvmapp01/jvmapp02. Both crashed immediately. What followed was a genuinely
long chain of layered problems — worth documenting in full since almost every step taught
something real.

**Bug #1 — DNS.** First crash: `java.net.UnknownHostException: mysql01`. This lab has no real
DNS for its own hostnames — `resolv.conf` points at `8.8.8.8`, which has no idea what `mysql01`
is. Fixed immediately with static `/etc/hosts` entries on both jvmapp hosts for the whole fleet.
This had been silently masked for weeks: the old, long-running `game-service` processes already
had their DB connections established from whenever this last worked, and a live JVM doesn't
re-resolve a hostname for a connection it's already holding — only a fresh restart exposed it.

**Bug #2 — a real gap in the `jvm_app` role, not just this host.** After the DNS fix, a
*different* failure: `Access denied for user 'gameapp'@'...' (using password: YES)`. Tracked
down to: the role's systemd unit template never had an `EnvironmentFile=` line, so it was never
loading `/opt/game-service/game-service.env` — a `.env` file sitting on disk since Session 3
with the app's real DB/Redis/RabbitMQ credentials, completely unmanaged by Ansible. The app was
silently running on whatever placeholder default is baked into the jar's `application-lab.yml`.
This bug had existed since the role was first written; nothing had ever forced a fresh restart
that would have exposed it until tonight.

**Bug #3 — even the real credentials had drifted.** Extracted the actual `DB_PASSWORD` from
`game-service.env` and tested it directly against MySQL — still `Access denied`. Nobody had a
record anywhere of what MySQL's *actual* current `gameapp` password was; `vault_gameapp_db_password`
had never been reconciled into the vault. Treated the `.env` file as source of truth (its
timestamp lined up with the last confirmed end-to-end proof of the whole pipeline) and used
`ALTER USER` to bring MySQL's stored password in line with it.

Applied a **quick manual fix** to unblock both hosts immediately (`sed` in `EnvironmentFile=`
into the live unit, `daemon-reload`, restart) before doing the real fix — same triage-then-root-
cause pattern as every other incident tonight.

**The permanent fix:** added `templates/game-service.env.j2` to the `jvm_app` role (renders
`DB_HOST`/`REDIS_HOST`/`RABBITMQ_HOST` from each group's `ansible_host` IP, not hostnames —
deliberately, since hostname resolution is exactly what broke first tonight), a new task to
template it to `/opt/game-service/game-service.env` (mode `0600`), and wired
`EnvironmentFile=` into `game-service.service.j2`. `jvm_app` now needs the vault too —
`vault_gameapp_db_password` and `vault_rabbitmq_password` joined `vault_redis_password` as
things this role reads.

**Then a real vault-editing detour.** Adding those two new secrets by hand in `vi` went wrong
twice in a row, both caught only because of "verify against the live system, don't trust the
file" discipline from a few nights ago:

- First pass: `vault_redis_password` came out as `Reddis@1984` (double-D) and got flagged as a
  likely typo against the `.env` file's `Redis@1984` (single-D) — but a direct `redis-cli -a
  ... ping` test proved the *opposite*: double-D was correct, and the `.env` file itself was the
  stale one. Good reminder that when two recorded values disagree, neither is automatically
  right — test against the live system.
- Second pass: `vault_mysql_root_password` came out reading the exact same value as
  `vault_gameapp_db_password` — a copy/retype slip, not intentional. A first attempt to verify
  which password was correct used an interactive masked prompt and produced a **wrong** answer
  (concluded root's password was `GameApp2026Secure!`) — later disproven by a clean,
  non-interactive test (`MYSQL_PWD='...' mysql ...`, run for both candidates back-to-back in one
  shot) that conclusively showed the real value was `NewRootPassword2026!` all along. Masked,
  manually-retyped passwords late at night are genuinely unreliable for verification — the
  non-interactive method removed all ambiguity in one shot where several interactive attempts
  hadn't.

**The final twist.** After all of the above was genuinely fixed — vault correct, role template
correct, `.env` file templated with the right values, confirmed byte-for-byte with `od -c` (no
hidden characters) — `game-service` *still* crash-looped with the identical MySQL error. A
direct `mysql` CLI login test from jvmapp01 itself, using the exact same credentials,
**succeeded** — proving the credentials, the grants, and the network path were all fine. The
actual cause: `controller01`'s local git checkout had never pulled the commit containing the
`EnvironmentFile=` fix. `git status` reported "up to date with origin/main," which was true but
misleading — it only reflects `git`'s last-known *local* record of the remote, not the actual
current GitHub state, and nothing had run `git fetch`/`git pull` on `controller01` since before
that fix was pushed. Every `--tags jvm_app` run after the very first one had been silently
redeploying the *old* template, undoing the earlier manual `sed` patch each time. `git pull` on
`controller01`, confirmed the template now had the fix, reran the tag — genuinely fixed this
time, verified with repeated stable-PID checks (no restart) on both hosts over multiple
intervals.

## Takeaways (round five)

- Three independent, real bugs stacked on top of each other, each one hiding the next until
  fixed: a DNS assumption baked into the jar's defaults, a systemd wiring gap in the Ansible role
  itself, and credentials that had quietly drifted out of sync with nobody keeping a record.
  Peeling back one layer just revealed the next — worth expecting this shape of problem after any
  long-idle system finally gets a fresh restart, not assuming the first fix found is the last one.
- Non-interactive credential testing (`MYSQL_PWD='...' mysql ...`, run for multiple candidates in
  one shot) is worth reaching for immediately once a masked interactive prompt has given a
  confusing or contradictory result — it removes typo risk from the test itself, not just from
  the thing being tested.
- `git status`'s "up to date with origin/main" is only ever as fresh as the last `fetch` — it is
  not a live check against GitHub. A machine that isn't the one actively committing (like
  `controller01`, which only ever receives pushes from elsewhere) can go stale silently and keep
  reporting "up to date" the entire time. Get in the habit of an explicit `git pull` before
  trusting any Ansible run's result, especially after debugging something that "should already be
  fixed."
- An Ansible `changed=0` recap means "matches what I would deploy," not "is correct" — those are
  only the same thing if the local playbook/role copy being compared against is actually current.
  Combined with the stale-checkout issue above, this produced real false confidence more than
  once tonight.
- When two independently-recorded copies of the same secret disagree (a config file vs. a typed
  memory, or a vault entry vs. either of those), don't assume either one is the source of truth by
  default — the live system is the only real tiebreaker.

## One more mistake, right at the end: tried to commit from the wrong machine

After getting `game-service` fixed, sent the docs commit to `controller01` first — wrong call.
`controller01` has never had a git author identity configured there (`Author identity unknown`)
and its GitHub access is read-only (`git push` failed with a `403`, "Write access to repository
not granted"). It's only ever been a `git pull` machine — fetches code to run Ansible, never
pushes anything back. The actual commit had to happen on the Windows PC instead, where the
files were already staged via the device bridge from earlier. Worth remembering going forward:
**controller01 pulls, the working PC pushes** — never assume every machine in the loop has the
same git permissions just because it can read the repo fine.

Also recurred one more time on the way out: the device bridge's sandboxed shell left behind a
fresh stale `.git/index.lock` on the Windows PC's checkout, blocking a real `git commit` in
PowerShell, purely from running read-only commands like `git status` against that same mounted
repo. Same fix as every other time tonight — `mv` the lock file out of the way (`rm` fails with
"Operation not permitted" on this mount, `mv` doesn't) — but worth noting this can happen from
completely passive commands, not just `git add`.

## Sixth addendum (2026-08-20): Spring Boot Actuator + Micrometer wiring

With `game-service` finally stable end-to-end, moved on to the other pending Phase 4 item:
actually exposing Prometheus-format metrics from the app itself. `spring-boot-starter-actuator`
had been in `pom.xml` since Session 3, but that alone only gives Actuator's default JSON
endpoints (`/actuator/health`, `/actuator/info`) — it does *not* produce a `/actuator/prometheus`
endpoint. Two things were missing, both app-code (not Ansible):

1. **`app/game-service/pom.xml`** — added `io.micrometer:micrometer-registry-prometheus` as a new
   dependency, right after `spring-boot-starter-actuator`. This is the bridge library that
   translates Micrometer's metrics into the text format Prometheus's scraper expects; without it,
   even a `prometheus`-exposed endpoint would 404.
2. **`app/game-service/src/main/resources/application.yml`** — `management.endpoints.web.exposure.include`
   only listed `health,info`. Added `prometheus` to that list. Without this, the endpoint would
   exist internally (dependency present) but Spring Boot wouldn't actually serve it over HTTP.

Small bonus discovery while in there: `docker/prometheus/prometheus.yml` on controller01 *already*
had a `game_service` scrape job defined (`jvmapp01:8080` and `jvmapp02:8080`, path
`/actuator/prometheus`) — someone (past-me, some earlier session) had already anticipated this and
wired the Prometheus side up front, marked "net-new" and expected to sit `DOWN` until the app
side caught up. So no Prometheus config changes needed tonight — just the two app-code changes
above. Once the new jar with the Micrometer dependency baked in is built and deployed, that
target should flip from `DOWN` to `UP` on its own.

Both files delivered via the usual device-bridge flow and staged with `git add` on the **working
PC** (`app/game-service/pom.xml`, `app/game-service/src/main/resources/application.yml`).
Also noticed an unrelated pending change already sitting in the working tree on the working PC,
untouched by tonight's work: `.github/workflows/build-game-service.yml` shows a one-line diff
that's just a trailing-whitespace/line-ending difference on the last comment line (no actual
content change) — left unstaged, not part of this commit, worth a look later but harmless.

Next real steps once this addendum is committed and pushed from the working PC: trigger a CI
build (push to `app/game-service/**`, or a manual `workflow_dispatch`) to bake the new
dependency into a fresh jar, `git pull` on **controller01 first** (per the hard-learned lesson
from the previous addendum — never trust "up to date" without pulling), then
`ansible-playbook site.yml --tags jvm_app --ask-vault-pass --ask-become-pass` from controller01 to
deploy, then confirm the `game_service` target shows `UP` in Prometheus with real JVM/HTTP
metrics flowing.

## Next up

`game-service` is fully healthy on jvmapp01 and jvmapp02, running the CI-built jar, reading real
reconciled credentials from a properly Ansible-managed `game-service.env`, confirmed stable across
multiple checks. The CI/CD → deploy pipeline is proven end-to-end for real now, not just "the jar
landed in the repo." Two small cleanup items left over from the earlier vault detour, neither
urgent: `vault_mysql_root_password` still needs one more fix to read the actual real value
(`NewRootPassword2026!`), and two stray junk files (`ansible/{censored:`, `ansible/{msg:`) showed
up as untracked on `controller01` — harmless, just need deleting. Actuator/Micrometer app-code
changes are done as of tonight (see addendum above) but **not yet built or deployed** — still need
a fresh CI build and an `--tags jvm_app` run to actually land on jvmapp01/jvmapp02. Remaining real
work after that: Winlogbeat on `winsrv01` (unblocked, not yet wired up). Once both of those land,
Phase 4 is functionally complete and Phase 5 (the AI-driven RCA goal) becomes buildable for real.
