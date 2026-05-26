#!/bin/bash
ES_URL="${ELASTICSEARCH_URI:-http://localhost:9200}"
INDEX="${1:-spring-ai-document-index}"

echo "Cancellazione indice '$INDEX' su $ES_URL ..."
curl -sf -X DELETE "$ES_URL/$INDEX" | python3 -m json.tool
