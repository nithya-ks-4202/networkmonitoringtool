#!/usr/bin/env bash
#
# Backs up the monitoring database.
#
# Monitoring history cannot be recreated. When a dispute arises months later
# about whether a camera was down on a particular night, this is the only
# record -- so this script is the difference between answering that question and
# not.
#
# Usage:
#   NMS_BACKUP_DIR=/var/backups/nms deploy/backup.sh
#
# Environment:
#   NMS_BACKUP_DIR    where dumps are written        (default ./backups)
#   NMS_KEEP_DAYS     dumps to retain                (default 30)
#   NMS_COMPOSE_DIR   directory holding the compose file (default the repo root)
#   NMS_DB_NAME       database name                  (default nms)
#   NMS_DB_USER       database user                  (default nms)
#
# Runs against the Compose deployment by default. For a database elsewhere, set
# PGHOST/PGUSER/PGPASSWORD and it will use pg_dump directly.

set -euo pipefail

BACKUP_DIR="${NMS_BACKUP_DIR:-./backups}"
KEEP_DAYS="${NMS_KEEP_DAYS:-30}"
DB_NAME="${NMS_DB_NAME:-nms}"
DB_USER="${NMS_DB_USER:-nms}"
COMPOSE_DIR="${NMS_COMPOSE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

STAMP=$(date +%Y-%m-%d-%H%M%S)
mkdir -p "$BACKUP_DIR"

# Written to a .partial name first and renamed on success, so a dump
# interrupted halfway cannot be mistaken for a complete one -- which is how a
# backup that was never going to restore gets discovered during an incident.
TARGET="$BACKUP_DIR/nms-$STAMP.sql.gz"
PARTIAL="$TARGET.partial"

cleanup() {
    rm -f "$PARTIAL"
}
trap cleanup EXIT

echo "Backing up '$DB_NAME' to $TARGET"

if [[ -n "${PGHOST:-}" ]]; then
    # A database outside Compose -- managed, or on another host.
    pg_dump --username="${PGUSER:-$DB_USER}" --dbname="$DB_NAME" \
        --format=plain --no-owner --no-privileges \
        | gzip -9 > "$PARTIAL"
else
    # Through Compose. `exec -T` avoids allocating a TTY, which would corrupt
    # the stream with control characters when run from cron.
    ( cd "$COMPOSE_DIR" && docker compose exec -T db \
        pg_dump --username="$DB_USER" --dbname="$DB_NAME" \
                --format=plain --no-owner --no-privileges ) \
        | gzip -9 > "$PARTIAL"
fi

# An empty or trivially small dump means pg_dump failed in a way that still
# exited zero. Better to fail loudly now than to discover it at restore time.
SIZE=$(stat -c %s "$PARTIAL" 2>/dev/null || stat -f %z "$PARTIAL")
if [[ "$SIZE" -lt 1024 ]]; then
    echo "Dump is only ${SIZE} bytes -- treating as failed." >&2
    exit 1
fi

mv "$PARTIAL" "$TARGET"
trap - EXIT

echo "Wrote $TARGET ($(numfmt --to=iec "$SIZE" 2>/dev/null || echo "${SIZE}B"))"

# Rotation happens only after a successful dump, so a run of failures can never
# age out the last good backup.
DELETED=$(find "$BACKUP_DIR" -name 'nms-*.sql.gz' -type f -mtime "+$KEEP_DAYS" -print -delete | wc -l)
if [[ "$DELETED" -gt 0 ]]; then
    echo "Removed $DELETED backup(s) older than $KEEP_DAYS days"
fi

# Said plainly, because a backup on the same disk as the database protects
# against exactly one failure mode, and not the one that usually happens.
echo "Reminder: copy $BACKUP_DIR off this machine, and test a restore."
