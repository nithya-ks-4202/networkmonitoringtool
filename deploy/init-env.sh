#!/bin/sh
#
# Creates .env with generated secrets, ready for `docker compose up -d`.
#
# Exists because the alternative -- "copy the example, then open it in an
# editor and paste two generated values in" -- fails in more ways than it
# looks. $EDITOR is unset on a stock macOS shell, so the documented command
# expands to nothing and reports "command not found"; and a half-edited file
# produces a wall of Compose interpolation errors that name the variables
# rather than the mistake.
#
# Usage:
#   ./deploy/init-env.sh              # create .env, refuse if one exists
#   ./deploy/init-env.sh --force      # overwrite an existing .env
#
# Safe to read before running: it writes one file in the repository root and
# changes nothing else.

set -eu

REPO_ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
EXAMPLE="$REPO_ROOT/.env.example"
TARGET="$REPO_ROOT/.env"

FORCE=0
for arg in "$@"; do
    case "$arg" in
        --force) FORCE=1 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done

if [ ! -f "$EXAMPLE" ]; then
    echo "Cannot find .env.example. Run this from a checkout of the repository." >&2
    exit 1
fi

# Never silently overwritten: .env holds the database password for an install
# that may already have history in it, and regenerating it would leave the
# server unable to open its own database.
if [ -f "$TARGET" ] && [ "$FORCE" -eq 0 ]; then
    echo ".env already exists. Leaving it alone."
    echo "Pass --force to replace it -- but note that changing NMS_DB_PASSWORD"
    echo "after the database has been created locks the server out of it."
    exit 0
fi

# openssl is on macOS and every mainstream Linux, but not inside every minimal
# container, so there is a fallback that needs nothing but the kernel.
random_base64() {
    bytes=$1
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 "$bytes" | tr -d '\n'
    else
        # tr -dc is deliberate: base64 of /dev/urandom can wrap, and the
        # padding characters are dropped rather than risk a value that a .env
        # parser reads as something else.
        LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c $((bytes * 4 / 3))
    fi
}

DB_PASSWORD=$(random_base64 24)
JWT_SECRET=$(random_base64 48)

# Rewritten line by line rather than with `sed -i`, whose in-place flag takes
# an argument on macOS and does not on Linux -- the single most reliable way to
# write a script that works on one and corrupts the file on the other.
TMP="$TARGET.tmp.$$"
trap 'rm -f "$TMP"' EXIT

while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
        NMS_DB_PASSWORD=*)  printf 'NMS_DB_PASSWORD=%s\n' "$DB_PASSWORD" ;;
        NMS_JWT_SECRET=*)   printf 'NMS_JWT_SECRET=%s\n' "$JWT_SECRET" ;;
        *)                  printf '%s\n' "$line" ;;
    esac
done < "$EXAMPLE" > "$TMP"

# Readable only by its owner before anything is written into it: this file ends
# up holding the credentials to the monitoring database.
chmod 600 "$TMP"
mv "$TMP" "$TARGET"
trap - EXIT

echo "Wrote $TARGET with generated secrets (mode 600)."
echo
echo "Next:"
echo "  docker compose up -d"
echo "  docker compose logs -f server    # the admin password is printed once"
echo
echo "Optional, before starting: set NMS_BASE_URL to the address staff will use."
echo "It is what notification links point at, and 'localhost' in an email helps"
echo "nobody."
