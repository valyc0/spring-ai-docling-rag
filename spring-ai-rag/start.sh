#!/bin/bash
set -e

# Verifica che Elasticsearch sia raggiungibile prima di avviare
ES_URL="${ELASTICSEARCH_URI:-http://localhost:9200}"
echo "Verifica connessione a Elasticsearch: $ES_URL ..."
if ! curl -sf "$ES_URL" > /dev/null 2>&1; then
  echo "ERRORE: Elasticsearch non raggiungibile su $ES_URL. Avvialo prima di eseguire start.sh."
  exit 1
fi
echo "Elasticsearch OK."

./mvnw spring-boot:run
