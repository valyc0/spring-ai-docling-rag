#!/bin/bash
BASE_URL="${APP_URL:-http://localhost:8080}"

curl -sf "$BASE_URL/docling/documents" | python3 -m json.tool
