#!/usr/bin/env bash
# Uso: ./docling-parse.sh <file> [--md] [--ocr] [--tables] [--pages N]
#   <file>      : documento da convertire (PDF, DOCX, HTML, …)
#   --md        : ritorna Markdown invece del JSON nativo Docling
#   --ocr       : abilita OCR (utile per PDF scansionati)
#   --tables    : abilita rilevamento struttura tabelle
#   --pages N   : limita la conversione alle prime N pagine

set -euo pipefail

BASE_URL="${DOCLING_URL:-http://localhost:8001}"

if [[ $# -lt 1 ]]; then
  echo "Uso: $0 <file> [--md] [--ocr] [--tables] [--pages N]" >&2
  exit 1
fi

FILE="$1"
shift

MD_FLAG=0
OCR_FLAG=0
TABLES_FLAG=0
PAGES_VAL=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --md)     MD_FLAG=1 ;;
    --ocr)    OCR_FLAG=1 ;;
    --tables) TABLES_FLAG=1 ;;
    --pages)
      shift
      [[ $# -eq 0 ]] && { echo "Errore: --pages richiede un numero" >&2; exit 1; }
      PAGES_VAL="$1"
      ;;
    *) echo "Flag sconosciuto: $1" >&2; exit 1 ;;
  esac
  shift
done

if [[ ! -f "$FILE" ]]; then
  echo "Errore: file non trovato: $FILE" >&2
  exit 1
fi

QUERY=""
[[ $MD_FLAG     -eq 1 ]] && QUERY="${QUERY}&md"
[[ $OCR_FLAG    -eq 1 ]] && QUERY="${QUERY}&ocr"
[[ $TABLES_FLAG -eq 1 ]] && QUERY="${QUERY}&tables"
[[ -n "$PAGES_VAL"   ]] && QUERY="${QUERY}&pages=${PAGES_VAL}"
QUERY="${QUERY#&}"

URL="${BASE_URL}/parse"
[[ -n "$QUERY" ]] && URL="${URL}?${QUERY}"

curl -fsS -X POST "$URL" -F "file=@${FILE}"
