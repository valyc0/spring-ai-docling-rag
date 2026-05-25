#!/usr/bin/env bash
# Carica pinocc_small.pdf sull'endpoint POST /docling/parse (embed + indicizza su Elastic)
# Uso:
#   ./upload-pinocchio.sh                          # upload
#   ./upload-pinocchio.sh ask "chi è Geppetto?"   # domanda RAG

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILE="$SCRIPT_DIR/esempi/pinocc_small.pdf"
BASE_URL="${BASE_URL:-http://localhost:8080}"

if [[ "${1}" == "ask" ]]; then
  QUESTION="${2:?Specifica la domanda: ./upload-pinocchio.sh ask \"...\"}"
  echo "Domanda: $QUESTION"
  curl -s -G "$BASE_URL/docling/ask" --data-urlencode "q=$QUESTION"
  echo
  exit 0
fi

if [[ ! -f "$FILE" ]]; then
  echo "Errore: file non trovato: $FILE"
  exit 1
fi

echo "Caricamento '$FILE' su $BASE_URL/docling/parse ..."
curl -s -X POST "$BASE_URL/docling/parse" \
  -F "file=@${FILE};type=application/pdf" \
  -H "Accept: application/json" \
  | jq '{docId, fileName, sourceType, sezioni: (.sections | length)}'
