# Observability stack — Prometheus + Grafana + ELK

Runs on `controller01`. See `docs/architecture.md` ("Phase 4") for the full
design rationale — this is just the how-to-run-it.

## Layout

```
docker/
├── docker-compose.yml
├── .env                        ← image versions, retention, Grafana admin password
├── prometheus/
│   └── prometheus.yml          ← scrape targets, matches ansible/inventory/hosts.yml
├── grafana/provisioning/
│   ├── datasources/            ← Prometheus + Elasticsearch, auto-wired on boot
│   └── dashboards/             ← provider config; actual dashboard JSON lives in ../dashboards
├── logstash/
│   ├── pipeline/logstash.conf  ← beats input → elasticsearch output
│   └── config/logstash.yml
```

## One-time manual steps (before `docker compose up` can do anything)

These aren't automated yet — same category as the WinRM bootstrap step in
`ansible/README.md`, do them once by hand on `controller01`:

1. **Install Docker CE + the compose plugin.** RHEL 9.5 doesn't carry these in
   its own repos:
   ```bash
   sudo dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
   sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
   sudo systemctl enable --now docker
   ```
2. **Raise `vm.max_map_count`** — Elasticsearch refuses to start below 262144:
   ```bash
   sudo sysctl -w vm.max_map_count=262144
   echo "vm.max_map_count=262144" | sudo tee /etc/sysctl.d/99-elasticsearch.conf
   ```
3. **Open firewalld ports**, scoped to the lab subnet only (not yet an Ansible
   role — do this manually for now, or fold into the `monitoring_agents` role
   when that's written):
   ```bash
   sudo firewall-cmd --permanent --add-port={9090,3000,5601}/tcp --zone=<lab-subnet-zone>
   sudo firewall-cmd --reload
   ```

## Running it

```bash
cd docker
docker compose up -d
docker compose ps          # everything should report healthy/running
```

## Verifying

- **Prometheus targets:** `http://controller01:9090/targets` — `node_exporter`
  and `windows_exporter` jobs should be UP immediately (already deployed via
  Ansible). Every other job will show DOWN until its exporter is deployed —
  expected at this stage, not a bug. See `docs/architecture.md` "Rollout
  order" for the deployment sequence.
- **Grafana:** `http://controller01:3000` (`admin` / value of
  `GF_SECURITY_ADMIN_PASSWORD` in `.env`) — Prometheus and Elasticsearch
  datasources should already show as connected under Connections → Data
  sources, no manual setup needed.
- **Elasticsearch:** `curl controller01:9200/_cluster/health` → `"status":"green"`
  (single-node, so green just means the one node is healthy).
- **Kibana:** `http://controller01:5601` — won't have any data/index patterns
  until Filebeat is actually shipping logs (next step, not in this compose file).

## Why it's structured this way

- **Config files checked into git, not clicked together in the UI** — same
  reasoning as the Ansible roles: `docker compose down -v` and rebuild should
  get you back to the identical stack, not "mostly the same, I think."
- **`xpack.security` off on the Elastic stack** — this is a single-node lab
  behind firewalld, not a production cluster reachable from anywhere.
  Deliberate lab-only shortcut, called out explicitly in the compose file so
  it isn't copy-pasted somewhere that needs real auth later.
- **Dashboards mount from `../dashboards` at the repo root**, not from inside
  `docker/` — keeps dashboard JSON discoverable as its own top-level concern,
  matching how `ansible/` and `terraform/` are already separated.

## Next up

Net-new exporters (nginx, mysqld, redis, RabbitMQ's prometheus plugin, the
game-service Actuator endpoint) and Filebeat/Winlogbeat still need an Ansible
role (`monitoring_agents` or similar) to actually deploy them — this compose
file is the receiving end, not the whole pipeline. Then: build the actual
Grafana dashboard JSON per tier, then log parsing in `logstash.conf` beyond
the current pass-through.
