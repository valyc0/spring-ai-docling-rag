#!/usr/bin/env bash
# Carica pinocc_small.pdf sull'endpoint POST /docling/parse

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILE="$SCRIPT_DIR/esempi/pinocc_small.pdf"
URL="${1:-http://localhost:8080}/docling/parse"

if [[ ! -f "$FILE" ]]; then
  echo "Errore: file non trovato: $FILE"
  exit 1
fi

echo "Caricamento '$FILE' su $URL ..."

curl -s -X POST "$URL" \
  -F "file=@${FILE};type=application/pdf" \
  -H "Accept: application/json" \
  | jq .
