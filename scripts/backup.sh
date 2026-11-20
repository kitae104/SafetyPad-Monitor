#!/usr/bin/env bash
# Encrypted logical backup of the SafetyPad database (pg_dump custom format + AES-256, PBKDF2).
# Writes backups/safetypad-<UTC timestamp>.dump.enc and a .sha256 next to it. Nothing is uploaded or deleted:
# copy the files to independent storage (e.g. an S3 bucket) with your own approved tooling, and keep the
# passphrase somewhere OTHER than the backup location. Retention is an operator decision (see docs/OPEN_DECISIONS.md).
#
#   BACKUP_PASSPHRASE_FILE=deploy/.backup-passphrase scripts/backup.sh
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE="${COMPOSE_CMD:-docker compose -p safetypad-prod -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod}"
PASS_FILE="${BACKUP_PASSPHRASE_FILE:?set BACKUP_PASSPHRASE_FILE (a file containing the passphrase)}"
[ -s "$PASS_FILE" ] || { echo "passphrase file is empty: $PASS_FILE" >&2; exit 1; }
DB_NAME="${DB_NAME:-safetypad}"; DB_USER="${DB_USER:-safetypad}"
OUT_DIR="${BACKUP_DIR:-backups}"; mkdir -p "$OUT_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$OUT_DIR/safetypad-$STAMP.dump.enc"
START=$(date +%s)
$COMPOSE exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc --no-owner \
  | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass "file:$PASS_FILE" -out "$OUT"
( cd "$OUT_DIR" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256" )
chmod 600 "$OUT"
echo "backup written: $OUT ($(du -h "$OUT" | cut -f1)) in $(( $(date +%s) - START )) s"
echo "sha256: $(cut -d' ' -f1 "$OUT.sha256")"
