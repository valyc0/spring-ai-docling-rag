#!/usr/bin/env bash
# Interroga il RAG in modo interattivo o da riga di comando.
#
# Uso:
#   ./query.sh                                         # modalità interattiva
#   ./query.sh list                                    # elenca i documenti
#   ./query.sh "domanda"                               # query globale
#   ./query.sh "domanda" --doc   <docId>               # filtro per docId
#   ./query.sh "domanda" --file  <fileName>            # filtro per nome file
#   ./query.sh "domanda" --chapter <titolo-sezione>    # filtro per capitolo
#
# Variabili d'ambiente:
#   BASE_URL   (default: http://localhost:8080)

BASE_URL="${BASE_URL:-http://localhost:8080}"

urlencode() {
  python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$1"
}

do_ask() {
  local question="$1" doc_id="$2" file_name="$3" chapter="$4"
  local params=""
  [[ -n "$doc_id"    ]] && params+="&docId=$(urlencode "$doc_id")"
  [[ -n "$file_name" ]] && params+="&fileName=$(urlencode "$file_name")"
  [[ -n "$chapter"   ]] && params+="&chapter=$(urlencode "$chapter")"

  local url="$BASE_URL/docling/ask?q=$(urlencode "$question")${params}"

  echo ""
  echo "Domanda : $question"
  [[ -n "$doc_id"    ]] && echo "docId   : $doc_id"
  [[ -n "$file_name" ]] && echo "fileName: $file_name"
  [[ -n "$chapter"   ]] && echo "chapter : $chapter"
  echo "---"
  curl -sf "$url"
  echo ""
}

fetch_docs() {
  curl -sf "$BASE_URL/docling/documents"
}

# ─── modalità interattiva (nessun argomento) ─────────────────────────────────
if [[ $# -eq 0 ]]; then
  echo "Recupero documenti da $BASE_URL ..."
  RAW=$(fetch_docs) || { echo "Errore: server non raggiungibile."; exit 1; }

  # Legge i doc in array paralleli
  mapfile -t DOC_IDS   < <(echo "$RAW" | python3 -c "import sys,json; [print(d['docId'])   for d in json.load(sys.stdin)]")
  mapfile -t DOC_FILES < <(echo "$RAW" | python3 -c "import sys,json; [print(d['fileName']) for d in json.load(sys.stdin)]")

  COUNT=${#DOC_IDS[@]}
  if [[ $COUNT -eq 0 ]]; then
    echo "Nessun documento indicizzato."
    exit 0
  fi

  echo ""
  echo "Documenti indicizzati:"
  for i in "${!DOC_IDS[@]}"; do
    printf "  [%d] %s\n      id: %s\n" $((i+1)) "${DOC_FILES[$i]}" "${DOC_IDS[$i]}"
  done
  echo "  [0] Tutti i documenti (nessun filtro)"
  echo ""

  # Selezione documento
  while true; do
    read -rp "Seleziona documento (0-$COUNT): " SEL
    if [[ "$SEL" =~ ^[0-9]+$ ]] && [[ "$SEL" -ge 0 ]] && [[ "$SEL" -le "$COUNT" ]]; then
      break
    fi
    echo "  Inserisci un numero tra 0 e $COUNT."
  done

  SELECTED_DOC=""
  SELECTED_FILE=""
  if [[ "$SEL" -gt 0 ]]; then
    IDX=$((SEL-1))
    SELECTED_DOC="${DOC_IDS[$IDX]}"
    SELECTED_FILE="${DOC_FILES[$IDX]}"
    echo "  → Selezionato: $SELECTED_FILE"
  else
    echo "  → Query su tutti i documenti"
  fi

  # Capitolo opzionale
  echo ""
  read -rp "Capitolo/sezione (lascia vuoto per tutti): " CHAPTER

  # Domanda
  echo ""
  read -rp "Domanda: " QUESTION
  [[ -z "$QUESTION" ]] && { echo "Domanda vuota, uscita."; exit 1; }

  do_ask "$QUESTION" "$SELECTED_DOC" "" "$CHAPTER"
  exit 0
fi

# ─── list ────────────────────────────────────────────────────────────────────
if [[ "${1}" == "list" ]]; then
  RAW=$(fetch_docs) || { echo "Errore: server non raggiungibile."; exit 1; }
  echo "$RAW" | python3 -c "
import sys, json
docs = json.load(sys.stdin)
if not docs:
    print('  (nessun documento)')
else:
    for i, d in enumerate(docs, 1):
        print(f\"  [{i}] {d['fileName']}\")
        print(f\"       id: {d['docId']}\")
"
  exit 0
fi

# ─── ask da riga di comando ───────────────────────────────────────────────────
QUESTION="${1}"; shift
DOC_ID=""; FILE_NAME=""; CHAPTER=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --doc)     DOC_ID="$2";    shift 2 ;;
    --file)    FILE_NAME="$2"; shift 2 ;;
    --chapter) CHAPTER="$2";   shift 2 ;;
    *) echo "Opzione sconosciuta: $1"; exit 1 ;;
  esac
done

do_ask "$QUESTION" "$DOC_ID" "$FILE_NAME" "$CHAPTER"
