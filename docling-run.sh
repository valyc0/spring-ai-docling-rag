#!/usr/bin/env bash
# Avvia il container docling-service da Docker Hub (valyc1/docling-service:latest)
# Uso: ./docling-run.sh
#
# Variabili d'ambiente opzionali:
#   DOCLING_PORT           porta host (default: 8001)
#   DOCLING_THREADS        thread CPU per modello (default: 4)
#   DOCLING_MAX_CONCURRENT conversioni simultanee (default: 1)
#   DOCLING_TIMEOUT_SEC    timeout conversione in secondi (default: 300)
#   DOCLING_MAX_FILE_MB    dimensione massima file in MB (default: 100)
#   DOCLING_DEVICE         dispositivo: cpu | cuda (default: cpu)

set -euo pipefail

PORT="${DOCLING_PORT:-8001}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HF_MODELS_DIR="${SCRIPT_DIR}/docling-service/hf-models"

docker run -d \
  --name docling-service \
  --restart unless-stopped \
  -p "${PORT}:8001" \
  -e DOCLING_THREADS="${DOCLING_THREADS:-4}" \
  -e DOCLING_MAX_CONCURRENT="${DOCLING_MAX_CONCURRENT:-1}" \
  -e DOCLING_TIMEOUT_SEC="${DOCLING_TIMEOUT_SEC:-300}" \
  -e DOCLING_MAX_FILE_MB="${DOCLING_MAX_FILE_MB:-100}" \
  -e DOCLING_DEVICE="${DOCLING_DEVICE:-cpu}" \
  -v "${HF_MODELS_DIR}:/root/.cache/huggingface" \
  valyc1/docling-service:latest

echo "Docling service avviato su http://localhost:${PORT}"
echo "Health check: curl http://localhost:${PORT}/health"
