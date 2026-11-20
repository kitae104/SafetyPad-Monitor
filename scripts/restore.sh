#!/usr/bin/env bash
# Restores an encrypted backup into a NEW scratch database and verifies it. It never touches the live database.
# To recover production for real, follow docs/OPERATIONS_MANUAL.md (stop app -> restore into a fresh DB -> switch DB_NAME).
#
#   BACKUP_PASSPHRASE_FILE=deploy/.backup-passphrase scripts/restore.sh backups/safetypad-XXXX.dump.enc [scratch_db_name]
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE="${COMPOSE_CMD:-docker compose -p safetypad-prod -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod}"
FILE="${1:?usage: restore.sh <backup.dump.enc> [scratch_db_name]}"
SCRATCH="${2:-safetypad_restore_check}"
PASS_FILE="${BACKUP_PASSPHRASE_FILE:?set BACKUP_PASSPHRASE_FILE}"
DB_NAME="${DB_NAME:-safetypad}"; DB_USER="${DB_USER:-safetypad}"
[ "$SCRATCH" != "$DB_NAME" ] || { echo "refusing to restore over the live database '$DB_NAME'" >&2; exit 2; }
if [ -f "$FILE.sha256" ]; then ( cd "$(dirname "$FILE")" && sha256sum -c "$(basename "$FILE").sha256" ) || { echo "checksum mismatch" >&2; exit 3; }; fi
psqlx() { $COMPOSE exec -T postgres psql -U "$DB_USER" -v ON_ERROR_STOP=1 -At "$@"; }
START=$(date +%s)
RESTORED=0
# A failed restore (wrong passphrase, corrupt archive) must not leave a half-built scratch database behind.
trap 'if [ "$RESTORED" -ne 1 ]; then psqlx -d postgres -c "DROP DATABASE IF EXISTS \"$SCRATCH\"" >/dev/null 2>&1 || true; fi' EXIT
psqlx -d postgres -c "DROP DATABASE IF EXISTS \"$SCRATCH\"" -c "CREATE DATABASE \"$SCRATCH\""
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$PASS_FILE" -in "$FILE" \
  | $COMPOSE exec -T postgres pg_restore -U "$DB_USER" -d "$SCRATCH" --no-owner --exit-on-error
RESTORED=1
ELAPSED=$(( $(date +%s) - START ))
echo "restored into '$SCRATCH' in ${ELAPSED}s"
echo "== verification (live vs restored)"
rc=0
for t in devices device_credentials device_events device_locations admin_users audit_logs flyway_schema_history; do
  a=$(psqlx -d "$DB_NAME" -c "select count(*) from $t"); b=$(psqlx -d "$SCRATCH" -c "select count(*) from $t")
  if [ "$a" = "$b" ]; then echo "  OK   $t: $b rows"; else echo "  DIFF $t: live=$a restored=$b (rows written after the backup are expected to differ)"; fi
done
trg=$(psqlx -d "$SCRATCH" -c "select count(*) from pg_trigger where tgname in ('trg_device_events_immutable','trg_audit_logs_append_only')")
[ "$trg" = "2" ] && echo "  OK   immutability triggers present" || { echo "  FAIL immutability triggers missing ($trg/2)"; rc=1; }
mig=$(psqlx -d "$SCRATCH" -c "select max(version::int) from flyway_schema_history where success")
echo "  schema version: $mig"
echo "scratch database '$SCRATCH' kept for inspection; drop it with:"
echo "  $COMPOSE exec -T postgres psql -U $DB_USER -d postgres -c 'DROP DATABASE \"$SCRATCH\"'"
exit $rc
