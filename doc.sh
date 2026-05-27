#!/usr/bin/env bash
# Gestione documenti: upload, indicizzazione, summary e stato.
#
# Uso:
#   ./doc.sh <file>                          # upload senza summary
#   ./doc.sh <file> --summary                # upload + richiesta summary (asincrona)
#   ./doc.sh <file> --summary --wait         # upload + summary + attesa completamento
#   ./doc.sh summary <docId>                 # richiede summary per docId già indicizzato
#   ./doc.sh summary <docId> --wait          # summary + polling fino al completamento
#   ./doc.sh status  <docId>                 # controlla stato summary
#   ./doc.sh --interactive                   # modalità guidata interattiva
#
# Variabili d'ambiente:
#   BASE_URL      (default: http://localhost:8080)
#   POLL_INTERVAL (default: 5 secondi tra un polling e l'altro)

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"

# ─── colori ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

info()    { echo -e "${CYAN}${*}${RESET}"; }
ok()      { echo -e "${GREEN}✓ ${*}${RESET}"; }
warn()    { echo -e "${YELLOW}⚠ ${*}${RESET}"; }
error()   { echo -e "${RED}✗ ${*}${RESET}" >&2; }
bold()    { echo -e "${BOLD}${*}${RESET}"; }

# ─── utilità ─────────────────────────────────────────────────────────────────
require_jq() {
  command -v jq &>/dev/null || { error "jq non trovato. Installalo con: sudo apt install jq"; exit 1; }
}

check_server() {
  curl -sf "$BASE_URL/actuator/health" -o /dev/null \
    || { error "Server non raggiungibile su $BASE_URL"; exit 1; }
}

extract_field() {
  # $1=json string  $2=field name
  echo "$1" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$2',''))"
}

# ─── upload documento ─────────────────────────────────────────────────────────
do_upload() {
  local file="$1"

  if [[ ! -f "$file" ]]; then
    error "File non trovato: $file"
    exit 1
  fi

  local mime
  case "${file##*.}" in
    pdf)  mime="application/pdf" ;;
    docx) mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document" ;;
    html|htm) mime="text/html" ;;
    md)   mime="text/markdown" ;;
    *)    mime="application/octet-stream" ;;
  esac

  info "\nCaricamento '$(basename "$file")' su $BASE_URL/docling/parse ..." >&2

  local response
  response=$(curl -sf -X POST "$BASE_URL/docling/parse" \
    -F "file=@${file};type=${mime}" \
    -H "Accept: application/json") \
    || { error "Errore durante l'upload. Verifica che il server sia attivo."; exit 1; }

  local doc_id file_name sections
  doc_id=$(extract_field "$response" "docId")
  file_name=$(extract_field "$response" "fileName")
  sections=$(echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('sections',[])))")

  ok "Documento indicizzato con successo" >&2
  echo "" >&2
  bold "  File     : $file_name" >&2
  bold "  docId    : $doc_id" >&2
  bold "  Sezioni  : $sections" >&2
  echo "" >&2

  echo "$doc_id"   # solo il docId va a stdout — tutto il resto va su stderr
}

# ─── richiesta summary ───────────────────────────────────────────────────────
do_request_summary() {
  local doc_id="$1"

  info "Richiesta summary per docId=$doc_id ..."

  local response
  response=$(curl -sf -X POST "$BASE_URL/summary/${doc_id}" \
    -H "Accept: application/json") \
    || { error "Errore nella richiesta del summary."; exit 1; }

  local status message
  status=$(extract_field "$response" "status")
  message=$(extract_field "$response" "message")

  ok "Summary: $status — $message"
}

# ─── polling stato summary ────────────────────────────────────────────────────
do_wait_summary() {
  local doc_id="$1"
  local dots=0

  info "\nAttesa completamento summary (polling ogni ${POLL_INTERVAL}s) ..."
  info "Premi Ctrl+C per interrompere l'attesa (il processo continua in background).\n"

  while true; do
    local response status
    response=$(curl -sf "$BASE_URL/summary/${doc_id}" -H "Accept: application/json") \
      || { error "Errore nel polling."; exit 1; }

    status=$(extract_field "$response" "status")

    case "$status" in
      COMPLETED)
        ok "Summary completato!\n"
        print_summary "$response"
        return 0
        ;;
      FAILED)
        local err
        err=$(extract_field "$response" "summaryError")
        error "Summary fallito: $err"
        return 1
        ;;
      PENDING|PROCESSING)
        printf "\r${YELLOW}  [%s] Status: %-12s ${RESET}" "$(date +%H:%M:%S)" "$status"
        sleep "$POLL_INTERVAL"
        ;;
      *)
        warn "Stato sconosciuto: $status"
        sleep "$POLL_INTERVAL"
        ;;
    esac
  done
}

# ─── stampa summary ───────────────────────────────────────────────────────────
print_summary() {
  local json="$1"

  local doc_id file_name full_summary created_at n_sections
  doc_id=$(extract_field "$json" "docId")
  file_name=$(extract_field "$json" "fileName")
  created_at=$(extract_field "$json" "createdAt")
  n_sections=$(echo "$json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('sectionSummaries',[])))")

  echo ""
  bold "════════════════════════════════════════════════════════════"
  bold "  SUMMARY — $file_name"
  bold "════════════════════════════════════════════════════════════"
  echo -e "${CYAN}  docId    : ${RESET}$doc_id"
  echo -e "${CYAN}  Creato   : ${RESET}$created_at"
  echo -e "${CYAN}  Sezioni  : ${RESET}$n_sections"
  echo ""

  # Full summary
  bold "── Riassunto completo ───────────────────────────────────────"
  echo "$json" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('fullSummary','(non disponibile)'))
"

  # Section summaries (se presenti)
  local has_sections
  has_sections=$(echo "$json" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('yes' if d.get('sectionSummaries') else 'no')
")
  if [[ "$has_sections" == "yes" ]]; then
    echo ""
    bold "── Riassunti per sezione ────────────────────────────────────"
    echo "$json" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for s in d.get('sectionSummaries', []):
    title = s.get('title') or '(senza titolo)'
    page  = s.get('pageNumber')
    page_str = f' (pag. {page})' if page else ''
    print(f'\n  ▸ {title}{page_str}')
    for line in s.get('summary','').splitlines():
        print(f'    {line}')
"
  fi
  echo ""
  bold "════════════════════════════════════════════════════════════"
  echo ""
}

# ─── stato summary ────────────────────────────────────────────────────────────
do_status() {
  local doc_id="$1"

  local response status
  response=$(curl -sf "$BASE_URL/summary/${doc_id}" -H "Accept: application/json") \
    || { error "Errore nella lettura dello stato."; exit 1; }

  status=$(extract_field "$response" "status")

  case "$status" in
    COMPLETED)
      ok "Summary COMPLETATO"
      print_summary "$response"
      ;;
    FAILED)
      error "Summary FALLITO: $(extract_field "$response" "summaryError")"
      ;;
    NONE)
      warn "Summary non ancora richiesto per questo documento."
      echo "  Usa: ./doc.sh summary $doc_id"
      ;;
    PENDING|PROCESSING)
      warn "Summary in corso: $status"
      echo "  Richiesto il: $(extract_field "$response" "summaryRequestedAt")"
      echo "  Usa --wait per attendere il completamento."
      ;;
    *)
      echo "$response" | python3 -m json.tool 2>/dev/null || echo "$response"
      ;;
  esac
}

# ─── query RAG ──────────────────────────────────────────────────────────────
do_query() {
  local question="$1" doc_id="$2" file_name="$3" chapter="$4"

  local params=""
  [[ -n "$doc_id"    ]] && params+="&docId=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$doc_id")"
  [[ -n "$file_name" ]] && params+="&fileName=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$file_name")"
  [[ -n "$chapter"   ]] && params+="&chapter=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$chapter")"

  local q_enc
  q_enc=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$question")
  local url="$BASE_URL/docling/ask?q=${q_enc}${params}"

  bold "\nDomanda : $question"
  [[ -n "$doc_id"    ]] && echo -e "${CYAN}docId    : ${RESET}$doc_id"
  [[ -n "$file_name" ]] && echo -e "${CYAN}fileName : ${RESET}$file_name"
  [[ -n "$chapter"   ]] && echo -e "${CYAN}chapter  : ${RESET}$chapter"
  echo ""

  local answer
  answer=$(curl -sf "$url") \
    || { error "Errore nella query RAG."; exit 1; }

  bold "── Risposta ─────────────────────────────────────────────────────────"
  echo "$answer"
  echo ""
}

# ─── modalità interattiva ────────────────────────────────────────────────────
interactive_mode() {
  bold "\n═══════════════════════════════════"
  bold "  Upload & Summary — modalità interattiva"
  bold "═══════════════════════════════════\n"

  check_server

  # Selezione file
  local file_path
  while true; do
    read -rp "$(echo -e "${CYAN}Percorso del file da caricare: ${RESET}")" file_path
    # Espandi ~ e variabili
    file_path=$(eval echo "$file_path")
    [[ -f "$file_path" ]] && break
    error "File non trovato: $file_path"
  done

  # Upload
  local doc_id
  doc_id=$(do_upload "$file_path")

  # Chiedi se generare il summary
  echo ""
  local want_summary
  read -rp "$(echo -e "${YELLOW}Vuoi generare il summary del documento? [y/N]: ${RESET}")" want_summary

  if [[ "${want_summary,,}" =~ ^(y|yes|s|si)$ ]]; then
    do_request_summary "$doc_id"

    echo ""
    local want_wait
    read -rp "$(echo -e "${YELLOW}Attendere il completamento? (potrebbe richiedere minuti) [y/N]: ${RESET}")" want_wait

    if [[ "${want_wait,,}" =~ ^(y|yes|s|si)$ ]]; then
      do_wait_summary "$doc_id"
    else
      info "\nPuoi controllare lo stato con:"
      echo "  ./upload.sh status $doc_id"
      echo "  ./upload.sh summary $doc_id --wait"
    fi
  else
    info "\nPuoi richiedere il summary in seguito con:"
    echo "  ./upload.sh summary $doc_id"
  fi
}

# ─── entry point ─────────────────────────────────────────────────────────────
require_jq

if [[ $# -eq 0 ]]; then
  bold "\nUso: ./doc.sh <comando> [opzioni]\n"
  echo "  Comandi disponibili:"
  echo ""
  echo -e "  ${CYAN}<file>${RESET}                         Upload e indicizzazione di un documento"
  echo -e "  ${CYAN}<file> --summary${RESET}               Upload + richiesta summary (asincrona)"
  echo -e "  ${CYAN}<file> --summary --wait${RESET}        Upload + summary + attesa completamento"
  echo ""
  echo -e "  ${CYAN}summary <docId>${RESET}                Richiede summary per un docId già indicizzato"
  echo -e "  ${CYAN}summary <docId> --wait${RESET}         Summary + polling fino al completamento"
  echo ""
  echo -e "  ${CYAN}status <docId>${RESET}                 Controlla stato summary (NONE/PENDING/PROCESSING/COMPLETED/FAILED)"
  echo ""
  echo -e "  ${CYAN}list${RESET}                           Elenca tutti i documenti indicizzati (docId + fileName)"
  echo ""
  echo -e "  ${CYAN}query \"domanda\"${RESET}                  Query RAG su tutti i documenti"
  echo -e "  ${CYAN}query \"domanda\" --doc   <docId>${RESET}   Filtra per documento"
  echo -e "  ${CYAN}query \"domanda\" --file  <fileName>${RESET} Filtra per nome file"
  echo -e "  ${CYAN}query \"domanda\" --chapter <titolo>${RESET} Filtra per sezione/capitolo"
  echo ""
  echo -e "  ${CYAN}delete-summary <docId>${RESET}         Elimina solo il summary (il doc resta indicizzato)"
  echo -e "  ${CYAN}delete <docId>${RESET}                 Elimina documento da tutti gli indici (con conferma)"
  echo -e "  ${CYAN}delete-all${RESET}                     Reset completo di tutti i documenti (con doppia conferma)"
  echo ""
  echo -e "  ${CYAN}--interactive${RESET}                  Modalità guidata interattiva"
  echo ""
  echo "  Variabili d'ambiente:"
  echo -e "    ${YELLOW}BASE_URL${RESET}=$BASE_URL"
  echo -e "    ${YELLOW}POLL_INTERVAL${RESET}=${POLL_INTERVAL}s"
  echo ""
  exit 0
fi

if [[ "${1}" == "--interactive" ]]; then
  interactive_mode
  exit 0
fi

# ─── sottocomando: delete-summary ───────────────────────────────────────────────
if [[ "${1}" == "delete-summary" ]]; then
  DOC_ID="${2:?Specifica docId: ./doc.sh delete-summary <docId>}"
  check_server
  info "Eliminazione summary per docId=$DOC_ID ..."
  response=$(curl -sf -X DELETE "$BASE_URL/summary/${DOC_ID}" -H "Accept: application/json") \
    || { error "Errore durante la cancellazione del summary."; exit 1; }
  ok "$(extract_field "$response" "message")"
  exit 0
fi

# ─── sottocomando: delete ───────────────────────────────────────────────────────────
if [[ "${1}" == "delete" ]]; then
  DOC_ID="${2:?Specifica docId: ./doc.sh delete <docId>}"
  check_server
  warn "Stai per eliminare il documento e tutti i suoi dati (chunk, summary)."
  read -rp "$(echo -e "${RED}Confermi? Digita il docId per confermare: ${RESET}")" CONFIRM
  if [[ "$CONFIRM" != "$DOC_ID" ]]; then
    warn "Cancellazione annullata."
    exit 0
  fi
  info "Eliminazione in corso..."
  response=$(curl -sf -X DELETE "$BASE_URL/docling/${DOC_ID}" -H "Accept: application/json") \
    || { error "Errore durante la cancellazione."; exit 1; }
  ok "$(extract_field "$response" "message")"
  exit 0
fi

# ─── sottocomando: delete-all ──────────────────────────────────────────────────────
if [[ "${1}" == "delete-all" ]]; then
  check_server
  warn "Stai per eliminare TUTTI i documenti da TUTTI gli indici."
  read -rp "$(echo -e "${RED}Sei sicuro? [digita YES per confermare]: ${RESET}")" CONFIRM1
  [[ "$CONFIRM1" != "YES" ]] && { warn "Annullato."; exit 0; }
  read -rp "$(echo -e "${RED}Ultima conferma — operazione irreversibile [YES]: ${RESET}")" CONFIRM2
  [[ "$CONFIRM2" != "YES" ]] && { warn "Annullato."; exit 0; }
  info "Reset completo in corso..."
  response=$(curl -sf -X DELETE "$BASE_URL/admin/all" -H "Accept: application/json") \
    || { error "Errore durante il reset."; exit 1; }
  ok "$(extract_field "$response" "message")"
  exit 0
fi

# ─── sottocomando: query ─────────────────────────────────────────────────────
if [[ "${1}" == "query" ]]; then
  QUESTION="${2:?Specifica la domanda: ./doc.sh query \"domanda\" [--doc <docId>] [--file <fileName>] [--chapter <titolo>]}"
  shift 2
  Q_DOC=""; Q_FILE=""; Q_CHAPTER=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --doc)     Q_DOC="${2:?--doc richiede un valore}";     shift 2 ;;
      --file)    Q_FILE="${2:?--file richiede un valore}";   shift 2 ;;
      --chapter) Q_CHAPTER="${2:?--chapter richiede un valore}"; shift 2 ;;
      *) error "Opzione sconosciuta: $1"; exit 1 ;;
    esac
  done
  check_server
  do_query "$QUESTION" "$Q_DOC" "$Q_FILE" "$Q_CHAPTER"
  exit 0
fi

# ─── sottocomando: list ─────────────────────────────────────────────────────
if [[ "${1}" == "list" ]]; then
  check_server
  RAW=$(curl -sf "$BASE_URL/docling/documents" -H "Accept: application/json") \
    || { error "Errore nel recupero dei documenti."; exit 1; }
  COUNT=$(echo "$RAW" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
  if [[ "$COUNT" -eq 0 ]]; then
    warn "Nessun documento indicizzato."
    exit 0
  fi
  bold "\n$COUNT documento/i indicizzati su $BASE_URL:\n"
  echo "$RAW" | python3 -c "
import sys, json
docs = json.load(sys.stdin)
for i, d in enumerate(docs, 1):
    print(f\"  [{i}] {d['fileName']}\")
    print(f\"       docId : {d['docId']}\")
    print()
"
  exit 0
fi

# ─── sottocomando: summary ────────────────────────────────────────────────────
if [[ "${1}" == "summary" ]]; then
  DOC_ID="${2:?Specifica docId: ./upload.sh summary <docId> [--wait]}"
  check_server
  do_request_summary "$DOC_ID"
  [[ "${3:-}" == "--wait" ]] && do_wait_summary "$DOC_ID"
  exit 0
fi

# ─── sottocomando: status ─────────────────────────────────────────────────────
if [[ "${1}" == "status" ]]; then
  DOC_ID="${2:?Specifica docId: ./upload.sh status <docId>}"
  check_server
  do_status "$DOC_ID"
  exit 0
fi

# ─── upload da riga di comando ────────────────────────────────────────────────
FILE_ARG="$1"; shift
WANT_SUMMARY=false
WANT_WAIT=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --summary) WANT_SUMMARY=true ;;
    --wait)    WANT_WAIT=true ;;
    *) error "Opzione sconosciuta: $1"; exit 1 ;;
  esac
  shift
done

check_server
DOC_ID=$(do_upload "$FILE_ARG")

if $WANT_SUMMARY; then
  do_request_summary "$DOC_ID"
  if $WANT_WAIT; then
    do_wait_summary "$DOC_ID"
  else
    info "Controlla lo stato con: ./upload.sh status $DOC_ID"
  fi
fi
