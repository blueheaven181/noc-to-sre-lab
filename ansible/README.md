# Ansible — NOC-to-SRE Lab

Config management for the lab: RHEL 9.5 app/data/messaging tiers + one Windows
host, matching the "each server has its own purpose" pattern from production.

## Layout

```
ansible/
├── ansible.cfg
├── requirements.yml        ← Galaxy collections, install before anything else
├── site.yml                 ← master playbook, one play per tier
├── inventory/
│   ├── hosts.yml             ← one group per role: nginx_hosts, mysql_hosts, etc.
│   └── group_vars/
│       ├── all.yml            ← shared vars (app_version, timezone)
│       ├── linux_rhel.yml      ← RHEL connection + policy vars
│       ├── windows.yml          ← WinRM connection vars
│       └── vault.yml             ← YOU create this (ansible-vault), never commit plaintext
├── roles/
│   ├── common/                ← baseline hardening + node_exporter, every RHEL host
│   ├── nginx/                  ← reverse proxy, upstream built from jvm_app_hosts
│   ├── mysql/
│   ├── redis/
│   ├── rabbitmq/
│   ├── jvm_app/                 ← deploys the versioned jar from roles/jvm_app/files/
│   └── windows_common/           ← windows_exporter + firewall
└── files/
    └── bootstrap-winrm.ps1        ← run manually, once, on the Windows box
```

## One-time manual steps (before Ansible can do anything)

1. **Register every RHEL host** and pin the minor release to match production:
   ```
   sudo subscription-manager register --username <you>
   sudo subscription-manager attach --auto
   sudo subscription-manager release --set=9.5
   sudo dnf clean all
   ```
2. **Bootstrap WinRM on the Windows host** — RDP in and run `files/bootstrap-winrm.ps1` once.
3. **Create the vault file** with the secrets every role expects:
   ```
   ansible-vault create inventory/group_vars/vault.yml
   ```
   It needs: `vault_mysql_root_password`, `vault_gameapp_db_password`,
   `vault_redis_password`, `vault_rabbitmq_password`, `vault_windows_admin_password`.

## Running it

```bash
ansible-galaxy collection install -r requirements.yml
ansible-playbook site.yml --ask-vault-pass          # everything
ansible-playbook site.yml --limit mysql_hosts --ask-vault-pass   # one tier only
```

## Why it's structured this way

- **A group per service, not a generic "app servers" group** — deliberately
  mirrors "each server has its own purpose." Nobody accidentally installs
  MySQL and Redis on the same box because they were lumped into one group.
- **Immutable, versioned artifact deploy** (`jvm_app` role) — the jar is built
  once by CI, dropped into `roles/jvm_app/files/`, and this role just ships
  and symlinks it. No `mvn build` happening on production hosts.
- **SELinux stays enforcing** — the `common` role asserts this rather than
  disabling it; each service role adds the specific boolean/port context it
  needs instead of turning security off to make things "just work."
- **Firewalld rules are scoped to the lab subnet**, not `0.0.0.0/0` — same
  habit you'd want in real prod, cheap to practice here.

## Next up

This gets you config management. Still to layer in: the CI/CD pipeline that
builds and drops the versioned jar automatically (GitHub Actions), Terraform
for the Azure sandbox, and the chaos/postmortem phase.
