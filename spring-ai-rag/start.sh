#!/bin/bash
set -e

# Carica variabili dal file .env se presente
if [[ -f ".env" ]]; then
  echo "Carico .env ..."
  set -a
  # shellcheck source=.env
  source .env
  set +a
fi

# Verifica che Elasticsearch sia raggiungibile prima di avviare
ES_URL="${ELASTICSEARCH_URI:-http://localhost:9200}"
echo "Verifica connessione a Elasticsearch: $ES_URL ..."
if ! curl -sf "$ES_URL" > /dev/null 2>&1; then
  echo "ERRORE: Elasticsearch non raggiungibile su $ES_URL. Avvialo prima di eseguire start.sh."
  exit 1
fi
echo "Elasticsearch OK."
echo "Profilo attivo: ${SPRING_PROFILES_ACTIVE:-ollama}"

SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-ollama}" ./mvnw spring-boot:run
