#!/usr/bin/env bash
# Respaldo de la base de ServiBox: pg_dump en formato custom (comprimido) con fecha.
# Borra los respaldos de mas de 14 dias. Pensado para cron (ver docs/deploy/VPS.md).
# Uso: ./backup.sh            (guarda en deploy/backups/)
#      BACKUP_DIR=/ruta ./backup.sh
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$DEPLOY_DIR/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
COMPOSE=(docker compose --project-directory "$DEPLOY_DIR" -f "$DEPLOY_DIR/docker-compose.prod.yml")

umask 077
mkdir -p "$BACKUP_DIR"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
FILE="$BACKUP_DIR/servibox-$STAMP.dump"

# Las credenciales salen del propio contenedor; nada se escribe en la linea de comandos.
"${COMPOSE[@]}" exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -Z 6' < /dev/null > "$FILE.partial"

if [ ! -s "$FILE.partial" ]; then
  rm -f "$FILE.partial"
  echo "$(date -u +%FT%TZ) ERROR: respaldo vacio" >&2
  exit 1
fi
mv "$FILE.partial" "$FILE"

find "$BACKUP_DIR" -maxdepth 1 -name 'servibox-*.dump' -type f -mtime "+$((RETENTION_DAYS - 1))" -delete
find "$BACKUP_DIR" -maxdepth 1 -name 'servibox-*.dump.partial' -type f -mmin +60 -delete

echo "$(date -u +%FT%TZ) OK: $FILE ($(du -h "$FILE" | cut -f1))"
