# spring-ai-architecture

Sistema **RAG (Retrieval-Augmented Generation)** completamente locale: nessun servizio cloud, nessuna API key. I documenti vengono analizzati, vettorializzati e interrogati in linguaggio naturale usando modelli LLM che girano sulla macchina.

---

## Panoramica

```
                        ┌──────────────────────────────┐
                        │         Client / curl         │
                        └──────────┬───────────────────┘
                                   │ POST /docling/parse
                                   │ GET  /docling/ask
                                   ▼
                   ┌───────────────────────────────┐
                   │        spring-ai-rag           │
                   │     (Spring Boot 3 / Java)     │
                   │                               │
                   │  • riceve il file             │
                   │  • chiama Docling             │
                   │  • normalizza le sezioni      │
                   │  • chunking + overlap         │
                   │  • embedding → Elasticsearch  │
                   │  • kNN search + LLM answer    │
                   └────┬──────────────┬───────────┘
                        │              │
           POST /parse  │              │  embedding + kNN
                        ▼              ▼
          ┌─────────────────┐   ┌──────────────────┐
          │ docling-service │   │  Elasticsearch   │
          │  (FastAPI/Python│   │  (vector store)  │
          │   IBM Docling)  │   │  768 dimensioni  │
          └─────────────────┘   └──────────────────┘
                                        │
                              ┌─────────┴──────────┐
                              │       Ollama        │
                              │  nomic-embed-text   │
                              │  llama3.2:3b        │
                              └─────────────────────┘
```

---

## Componenti

### `docling-service/`
Microservizio Python (FastAPI) che espone `POST /parse`.  
Riceve un file (PDF, DOCX, HTML, PPTX…), lo elabora con **Docling** (IBM) e restituisce il documento in formato JSON strutturato con testi, tabelle e metadati di layout.  
Supporta OCR opzionale, rilevamento struttura tabelle e limite pagine.

### `spring-ai-rag/`
Applicazione Spring Boot che orchestra l'intera pipeline RAG:
- **Ingestione**: riceve il PDF → lo invia a Docling → normalizza le sezioni → splitta in chunk con overlap → calcola embedding → salva su Elasticsearch
- **Interrogazione**: riceve una domanda → embedding → kNN search → costruisce il contesto → genera la risposta con l'LLM

### `compose.yaml`
Docker Compose con tutti i servizi infrastrutturali:

| Servizio | Porta | Descrizione |
|---|---|---|
| Elasticsearch 8.11 | 9200 | Vector store, indice `spring-ai-document-index` |
| Kibana 8.11 | 5601 | UI per esplorare i chunk indicizzati |
| docling-service | 8001 | Parsing documenti |

> Ollama gira come processo host separato (`localhost:11434`).

---

## Avvio rapido

```bash
# 1. Avvia i servizi
docker compose up -d

# 2. Scarica i modelli Ollama (una tantum)
ollama pull nomic-embed-text
ollama pull llama3.2:3b

# 3. Avvia l'app Spring
cd spring-ai-rag
export ELASTICSEARCH_PASSWORD=changeme
./start.sh

# 4. Carica un documento
./upload-pinocchio.sh

# 5. Fai una domanda
./upload-pinocchio.sh ask "chi è Geppetto?"

# 6. Genera e indicizza 10 documenti HTML di test
./generate-and-index-docs.sh
```

---

## Formato dati interno

Tutti i documenti transitano nel formato `UnifiedDocumentJson`, indipendente dalla sorgente:

```json
{
  "docId": "uuid",
  "fileName": "pinocchio.pdf",
  "sourceType": "PDF",
  "sections": [
    {
      "sectionId": "section-0",
      "title": "CAPITOLO 1",
      "pageNumber": 5,
      "text": "..."
    }
  ]
}
```

Questo contratto permette di aggiungere in futuro altri tipi di sorgente (video con trascrizione, DOCX, HTML) senza modificare il layer di embedding e retrieval.

---

## API REST

### Ingestione

```bash
# Carica e indicizza un file (PDF, HTML, DOCX…)
POST /docling/parse
Content-Type: multipart/form-data
  file=@documento.pdf
```

### Ricerca vettoriale con filtri

```bash
# Domanda globale su tutti i documenti
GET /docling/ask?q=chi+è+Geppetto

# Filtrata per documento specifico
GET /docling/ask?q=chi+è+Geppetto&docId=<uuid>

# Filtrata per capitolo
GET /docling/ask?q=importo&docId=<uuid>&chapter=Allegato+A

# Filtri su metadati arbitrari (prefisso meta.)
GET /docling/ask?q=contratto&meta.sourceType=PDF
```

Tutti i filtri sono opzionali e combinati in AND tramite `FilterExpressionBuilder` (type-safe, nessuna injection).

### Documenti indicizzati

```bash
# Lista distinct docId + fileName
GET /docling/documents
# → [{"docId":"uuid","fileName":"doc.pdf"}, …]
```

---

## Struttura repository

```
spring-ai-architecture/
├── compose.yaml            # Infrastruttura Docker
├── docling-service/        # Microservizio parsing (Python/FastAPI)
│   ├── main.py
│   ├── Dockerfile
│   └── requirements.txt
├── spring-ai-rag/          # App principale Spring Boot (RAG)
│   └── src/…
├── esempi/                 # PDF di test (non in git)
├── upload-pinocchio.sh     # Carica pinocchio e fa domande RAG
├── list-docs.sh            # Lista tutti i documenti indicizzati
├── delete-index.sh         # Cancella l'indice Elasticsearch
└── generate-and-index-docs.sh  # Genera 10 HTML di test e li indicizza
```

---

## Gestione immagine Docker di `docling-service`

Il `compose.yaml` dichiara sia `image` che `build`, così puoi scegliere il comportamento:

```bash
# Avvia usando l'immagine pubblica dal registry (default)
docker compose up -d

# Ricostruisce l'immagine dal Dockerfile locale e la tagga come valyc1/docling-service:latest
docker compose build docling-service

# Ricostruisce e avvia in un solo comando
docker compose up -d --build docling-service
```

### Pubblicare una nuova versione su Docker Hub

```bash
# Ricostruisce dal Dockerfile
docker compose build docling-service

# Login (una tantum)
docker login

# Push dell'immagine su Docker Hub
docker push valyc1/docling-service:latest
```

---

## Reset completo

Rimuove tutti i container, le immagini e i volumi del progetto (compresi i dati Elasticsearch):

```bash
docker compose down --volumes --rmi all
```

Poi per ripartire da zero scaricando tutto dal registry:

```bash
docker compose up -d
```

