# Running it on-premise

Everything on your own hardware, no cloud. This is the runbook.

## Sizing

One machine, sized by how much you are watching.

| Estate | CPU | RAM | Disk (90 days) |
|---|---|---|---|
| Up to 100 devices, ~1,000 items | 2 cores | 4 GB | 20 GB |
| Up to 500 devices, ~8,000 items | 4 cores | 8 GB | 100 GB |
| Up to 2,000 devices, ~40,000 items | 8 cores | 16 GB | 400 GB |

Disk is the one to watch. A rough figure: **items × (86400 ÷ interval) × 90 days × ~40 bytes**. One thousand items on a one-minute interval is roughly 5 GB a quarter uncompressed, and TimescaleDB compression typically takes 10–20× off that after the first week.

Cameras are cheap to monitor — seven items each at one minute. A thousand cameras is well within a 4-core box.

## Install

```bash
git clone <your-repo> /opt/nms && cd /opt/nms

cp .env.example .env
openssl rand -base64 24   # → NMS_DB_PASSWORD
openssl rand -base64 48   # → NMS_JWT_SECRET
$EDITOR .env

docker compose up -d
docker compose logs -f server    # the admin password is printed once
```

Interface on `http://<host>:3000`.

Set `NMS_BASE_URL` in `.env` to the address staff actually use — it's what notification links point at, and `localhost` in an email helps nobody.

## Put TLS in front of it

The interface serves plain HTTP, so sign-in credentials cross your network in clear. On a flat office LAN that is worth fixing. Easiest route is Caddy, which gets a certificate from your internal CA or Let's Encrypt automatically:

```caddyfile
# /etc/caddy/Caddyfile
monitoring.internal.example.com {
    reverse_proxy localhost:3000
}
```

Then set `NMS_BASE_URL=https://monitoring.internal.example.com` and restart the server so notification links match.

## Back up the database

**This is the part people skip and regret.** The Compose volume has no backups. Monitoring history is not recreatable — when a dispute arises about whether a camera was down last March, the database is the only record.

`deploy/backup.sh` does a compressed dump with rotation. Run it nightly:

```bash
sudo cp /opt/nms/deploy/backup.sh /usr/local/bin/nms-backup
sudo chmod +x /usr/local/bin/nms-backup

# 02:30 daily, keeping 30 days
echo '30 2 * * * root NMS_BACKUP_DIR=/var/backups/nms /usr/local/bin/nms-backup' \
  | sudo tee /etc/cron.d/nms-backup
```

Copy the results off the machine. A backup on the same disk as the database protects against exactly one failure mode, and not the common one.

**Test the restore.** An untested backup is a belief, not a backup:

```bash
docker compose stop server web
gunzip -c /var/backups/nms/nms-2026-09-14.sql.gz \
  | docker compose exec -T db psql -U nms -d nms
docker compose start server web
```

## Upgrading

```bash
cd /opt/nms
/usr/local/bin/nms-backup          # always, before a schema change
git pull
docker compose build
docker compose up -d
docker compose logs -f server      # watch the migrations apply
```

Migrations run automatically at startup and are forward-only. There is no automated downgrade — if an upgrade goes wrong, restore the backup. That is the reason the backup comes first.

## Remote sites

For a second office, put a proxy there rather than opening firewall rules to it.

In the interface: **Proxies → Create**, name it, copy the token (shown once). Then on a machine at that site:

```bash
docker run -d --name nms-proxy --restart unless-stopped \
  --cap-add NET_RAW \
  -e NMS_SERVER_URL=https://monitoring.internal.example.com \
  -e NMS_PROXY_TOKEN='<token>' \
  -e NMS_PROXY_NAME=bristol \
  -v nms-spool:/var/lib/nms/spool \
  nms-proxy:1.0.0
```

Then set each host at that site to be collected by that proxy.

The proxy only dials **out**. If the link drops it buffers to disk and uploads the backlog on reconnect, so an overnight outage costs latency rather than data.

Its container health check reads a heartbeat file the proxy touches whenever it reaches the server — so `docker ps` showing *unhealthy* means "not talking to the server", not merely "process died". That is the failure worth catching.

## Monitoring the monitoring

A monitoring system that cannot report on itself has one blind spot, and it is the one that matters.

- **Proxies page** — online state, last contact, buffered queue depth, clock skew. A rising queue means uploads are failing. Large clock skew makes every timestamp that proxy reports wrong.
- **`/actuator/metrics`** on the server — history buffer depth, values written and dropped, trigger queue depth, alerts sent and failed. A rising buffer means the database is behind; a rising *dropped* count means data is being lost.
- Set up an action that emails you on `DISASTER`, then **test it** by disabling a host's interface. An untested alert path is not an alert path.

## Troubleshooting

**Every ICMP check reports down, but the devices are up.**
`ping` is missing or `NET_RAW` was not granted, so checks fell back to a TCP probe. Check the server log at startup for `No ping binary found`.

**A camera pings but the stream trigger fires.**
That is the template working. RTSP is not answering while ICMP is — usually a hung encoder or an exhausted session limit. Reboot the camera. If it is wrong, check `{$CAMERA.RTSP.PATH}` against the model's actual channel path.

**A proxy shows offline but its container is running.**
It cannot reach the server. Check egress from that site, and `docker logs nms-proxy` for the enrolment line. A rejected token logs explicitly.

**Sign-ins stop working after a restart.**
`NMS_JWT_SECRET` is unset, so a new signing key was generated. Set it in `.env`.

**Disk filling faster than expected.**
Check per-item history retention, and whether TimescaleDB is active:
```bash
docker compose exec db psql -U nms -d nms -c "SELECT extname FROM pg_extension;"
```
No `timescaledb` means the tables are uncompressed and retention is delete-based.

## When to move off a single box

Reconsider the topology when any of these hold:

- The poller queue is persistently non-empty after raising `NMS_POLLER_THREADS`
- The database is the bottleneck and the machine cannot take more disk or RAM
- You need the monitoring to survive that machine failing

At that point the same images run under the Helm chart in `deploy/helm`, against a managed database. The proxies do not change at all — that part of the architecture is identical in both topologies, which is why moving is a deployment change rather than a redesign.
