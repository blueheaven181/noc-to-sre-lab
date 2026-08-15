# Session 1 — Repo setup + first VM (`nginx01`) live

**Date:** 2026-08-15
**Goal:** Get the GitHub repo scaffolded and stand up the first RHEL 9.5 host (`nginx01`) end to end — install, register, harden, serve traffic.

## What got built

- GitHub repo `noc-to-sre-lab` created and scaffolded (`app/`, `ansible/`, `terraform/`, `docker/`, `dashboards/`, `docs/postmortems/`), Ansible skeleton committed, `.gitignore` in place before the first commit (Terraform state, vault files, keys excluded from the start — not bolted on later).
- `nginx01`: RHEL 9.8 installed from the official Red Hat ISO, Minimal Install (no GUI — this box is managed over SSH/Ansible, not a desktop), hostname and `ansible` admin user set during install, root login left disabled.
- Registered with Red Hat under the free Developer Subscription, then pinned to release **9.5** to match production, confirming zero package drift from the 9.8 ISO baseline (`dnf update` returned "Nothing to do").
- nginx installed, enabled, firewall opened for HTTP, and verified reachable from a browser on the host machine — not just `localhost` inside the VM.

## What actually went wrong (the useful part)

The friction was almost all in tooling and process, not RHEL itself:

- **Repo initially cloned into `C:\WINDOWS\system32`** — PowerShell had opened there by default. Cleaned up and re-cloned into the user profile instead. Lesson: check `pwd` before cloning anything, especially in a shell that opened somewhere unexpected.
- **A single transposed character in a GitHub username** (`bluheaveen181` vs the actual `blueheaven181`) caused a "repository not found" error that looked like a permissions problem but wasn't. Fixed by copying the URL directly from GitHub instead of retyping it — worth doing every time, not just after getting burned once.
- **The RHEL ISO download failed repeatedly** with a generic "check your internet connection" error in the browser — internet was fine the whole time. Root cause turned out to be **Chrome/Edge's Enhanced Safe Browsing** choking on a large, uncommon file type during its pre-download scan and mis-reporting it as a network failure. Switching to Firefox (which doesn't do the same aggressive scan) fixed it immediately.
- Tried working around the failed browser download with `Start-BitsTransfer` and `curl` — both failed **silently or misleadingly**: BITS left a 0-byte file with no error at all, and curl "succeeded" but had actually just downloaded a 1.2KB "Download Manager Problem" HTML page, not the ISO. Root cause: Red Hat's download link requires an authenticated browser session: a cookie neither tool carries. Lesson worth keeping: always check the actual byte size of a "successful" download before trusting it — a 100% exit code doesn't mean you got the right file.
- **`subscription-manager register` initially failed with "Invalid username or password"** — had used the local Linux username (`ansible`) instead of the actual Red Hat account login. Two completely separate identity systems that happen to share a terminal.
- **`subscription-manager attach --auto` returned "Ignoring the request"** — not a failure, just Simple Content Access (SCA) meaning the org no longer uses the older manual-attach model. Registration alone was already enough.
- **`ping redhat.com` showed 100% packet loss** even though the server was completely healthy — Red Hat blocks ICMP. Confirmed real connectivity with `curl -I` (an actual HTTP request) instead. Directly relevant to the day job: ping failing is one of the most common false alarms in monitoring, and this was a live example of why service checks should test the actual protocol, not just ICMP.

## Takeaways

- Most of today's real problems were **identity and tooling confusion** (which login goes where, which tool actually authenticates), not RHEL or networking concepts. Worth remembering that the "advanced" parts of infrastructure work are often less error-prone than the mundane parts.
- A failure with no error message (BITS, the silent 0-byte file) is more dangerous than a loud one — it looks like success until you check.
- Doing the nginx install manually before automating it with Ansible was the right call: every step (install, enable, firewall rule) is now something I actually did once by hand, not something I'm trusting a playbook to have gotten right.

## Next up

Clone `nginx01` as the base image for `mysql01`, `redis01`, `rabbitmq01`, `jvmapp01`, `jvmapp02`, and `controller01`.
