#!/usr/bin/env bash
# Restaura un respaldo de backup.sh en una base limpia. BORRA la base destino.
# Uso: ./restore.sh backups/servibox-AAAAMMDD-HHMMSS.dump [base_destino]
# Sin base_destino usa la de produccion (POSTGRES_DB): detiene el backend, borra y recrea la
# base, restaura y vuelve a arrancar el backend. Con otra base (por ejemplo servibox_verif)
# no toca el backend: sirve para comprobar un respaldo.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE=(docker compose --project-directory "$DEPLOY_DIR" -f "$DEPLOY_DIR/docker-compose.prod.yml")

FILE="${1:-}"
if [ -z "$FILE" ] || [ ! -s "$FILE" ]; then
  echo "Uso: $0 ARCHIVO.dump [base_destino]" >&2
  exit 1
fi

PROD_DB="$("${COMPOSE[@]}" exec -T postgres printenv POSTGRES_DB < /dev/null | tr -d '\r')"
TARGET="${2:-$PROD_DB}"
if ! [[ "$TARGET" =~ ^[a-z_][a-z0-9_]*$ ]]; then
  echo "Nombre de base invalido: $TARGET" >&2
  exit 1
fi

# Los exec sin entrada llevan < /dev/null: si no, docker consume la respuesta de la confirmacion.
# Comprueba que el archivo sea un respaldo legible antes de borrar nada.
"${COMPOSE[@]}" exec -T postgres pg_restore --list < "$FILE" > /dev/null

echo "Se BORRARA la base '$TARGET' y se reemplazara con: $FILE"
if [ "$TARGET" = "$PROD_DB" ]; then
  echo "Es la base de produccion: el backend se detendra mientras tanto."
fi
read -r -p "Escribe RESTAURAR $TARGET para continuar: " ANSWER
if [ "$ANSWER" != "RESTAURAR $TARGET" ]; then
  echo "Cancelado." >&2
  exit 1
fi

if [ "$TARGET" = "$PROD_DB" ]; then
  "${COMPOSE[@]}" stop backend < /dev/null
fi

"${COMPOSE[@]}" exec -T postgres sh -c "dropdb -U \"\$POSTGRES_USER\" --if-exists --force '$TARGET' && createdb -U \"\$POSTGRES_USER\" -O \"\$POSTGRES_USER\" '$TARGET'" < /dev/null
"${COMPOSE[@]}" exec -T postgres sh -c "pg_restore -U \"\$POSTGRES_USER\" -d '$TARGET' --no-owner --exit-on-error" < "$FILE"

if [ "$TARGET" = "$PROD_DB" ]; then
  "${COMPOSE[@]}" start backend < /dev/null
fi
echo "Restaurado en '$TARGET'."
