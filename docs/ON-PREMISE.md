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

## Developing on a Mac, hosting on a Linux server

Nothing is built on the Mac and copied over. The Linux server clones the
repository and builds there, which is what keeps the two in step — and avoids
the trap of building an image on an Apple Silicon Mac that will not run on an
x86 server.

So the Mac's only job is to push code:

```bash
# on the Mac
git push
```

You do not need Docker on the Mac at all for this. If you want to run the stack
locally as well, see [Running it on the Mac](#running-it-on-the-mac) at the
bottom — the install is completely different there, and the commands in this
section will not work.

> Everything in steps 1–4 runs **on the Linux server**, over SSH.

### 1. Install Docker

Docker Engine. The official script handles every mainstream distribution:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
```

That script is Linux-only. Run it on a Mac and it stops with
`Unsupported operating system 'macOS'` — which means you are on the wrong
machine, not that anything is broken.

Add yourself to the `docker` group so you are not typing `sudo` all day. Log out
and back in afterwards, as group membership is only read at login:

```bash
sudo usermod -aG docker "$USER"
```

Check it took:

```bash
docker run --rm hello-world
docker compose version     # must be v2; "docker-compose" with a hyphen is the old one
```

### 2. Install

```bash
git clone <your-repo> /opt/nms && cd /opt/nms

./deploy/init-env.sh             # writes .env with generated secrets

docker compose up -d             # first run builds the images; allow a few minutes
docker compose logs -f server    # the admin password is printed once
```

No `sudo` on any of that. The files belong to you, and running Compose as root
leaves a `.env` and build cache that you then cannot edit.

If `docker compose up` answers with a list of
`required variable ... is missing a value` errors, `.env` was not created or
not filled in — run the script above. If it names `NMS_PROXY_TOKEN`, the
checkout predates the fix for that; `git pull` first.

Interface on `http://<host>:3000`.

Set `NMS_BASE_URL` in `.env` to the address staff actually use — it is what
notification links point at.

### 3. Open the ports

Most server distributions ship with a firewall that will silently swallow this.

```bash
# ufw (Ubuntu/Debian)
sudo ufw allow 3000/tcp

# firewalld (RHEL/Rocky/Alma)
sudo firewall-cmd --permanent --add-port=3000/tcp && sudo firewall-cmd --reload
```

Port 8080 (the API) only needs opening if something outside the server calls it
directly — a proxy at another site, or agents pushing in active mode. The web
interface reaches it inside the Compose network.

### 4. Deploying a change later

```bash
# on the Mac
git push

# on the server
cd /opt/nms
/usr/local/bin/nms-backup      # set up in "Back up the database" below
git pull
docker compose up -d --build
```

### If the server cannot reach Docker Hub

Common on a corporate network. Retag the four upstream images into your own
registry and point the build at them — no tracked file needs editing, so
`git pull` stays clean:

```properties
# .env
NMS_DB_IMAGE=registry.internal/timescale/timescaledb:latest-pg16
NMS_MAVEN_IMAGE=registry.internal/maven:3.9-eclipse-temurin-21
NMS_JRE_IMAGE=registry.internal/eclipse-temurin:21-jre-jammy
NMS_NODE_IMAGE=registry.internal/node:22-alpine
NMS_NGINX_IMAGE=registry.internal/nginx:1.27-alpine
```

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

**`git pull` says `cannot open '.git/FETCH_HEAD': Permission denied`.**
Something was run with `sudo` earlier in this directory, and it left files
inside `.git/` owned by root. Git is not asking for privileges — it cannot
write to its own metadata. Take the checkout back:

```bash
sudo chown -R "$(id -un):$(id -gn)" .
git pull
```

Check for the same damage elsewhere while you are there — `ls -la` for anything
owned by `root`, particularly `.env`, which the server needs to read and you
need to edit.

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

## Running it on the Mac

Only needed if you want the stack running locally as well as on the server. The
deployment does not require it.

macOS cannot run Linux containers directly, so anything you install here is a
Linux VM with Docker inside it. That is why `get.docker.com` refuses to run —
there is no Docker Engine for macOS to install.

**Colima** is the lighter of the two options, and has no licensing conditions:

```bash
brew install colima docker docker-compose

# Compose is a CLI plugin and Homebrew does not wire it up for you.
# Without this, `docker compose` reports "is not a docker command".
mkdir -p ~/.docker/cli-plugins
ln -sfn "$(brew --prefix)/opt/docker-compose/bin/docker-compose" \
        ~/.docker/cli-plugins/docker-compose

# The VM's limits are its own, not the Mac's. The defaults (2 CPU, 2 GB) are
# too small -- the Maven build inside the server image will be killed.
colima start --cpus 4 --memory 8 --disk 60

docker compose version
```

Then the same commands as the server:

```bash
./deploy/init-env.sh
docker compose up -d
```

Colima does not survive a reboot by default; `colima start` again, or
`brew services start colima`.

**Docker Desktop** is the alternative — download the Apple Silicon or Intel
build from docker.com. It is heavier and requires a paid subscription for
larger companies, so check that before installing it at work.

Two caveats on a Mac, both consequences of the VM rather than faults:

- `network_mode: host` does not work for the proxy. Containers sit behind the
  VM's network, so a proxy running on the Mac cannot reach LAN devices by their
  real addresses. Run proxies on Linux.
- Images built here are `arm64` on Apple Silicon and will not run on an x86
  server. Let the server build its own, as above.

## When to move off a single box

Reconsider the topology when any of these hold:

- The poller queue is persistently non-empty after raising `NMS_POLLER_THREADS`
- The database is the bottleneck and the machine cannot take more disk or RAM
- You need the monitoring to survive that machine failing

At that point the same images run under the Helm chart in `deploy/helm`, against a managed database. The proxies do not change at all — that part of the architecture is identical in both topologies, which is why moving is a deployment change rather than a redesign.
