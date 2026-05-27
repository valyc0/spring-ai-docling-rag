# spring-ai-architecture

Sistema **RAG (Retrieval-Augmented Generation)** completamente locale: nessun servizio cloud, nessuna API key. I documenti vengono analizzati, vettorializzati e interrogati in linguaggio naturale usando modelli LLM che girano sulla macchina.

---

## Panoramica

```
                        ┌──────────────────────────────────────┐
                        │           Client / curl               │
                        └──┬──────────────┬──────────────┬──────┘
                           │              │              │
                    /parse │       /ask   │    /summary  │
                           ▼              ▼              ▼
                   ┌──────────────────────────────────────────┐
                   │              spring-ai-rag                │
                   │          (Spring Boot 3 / Java 21)        │
                   │                                          │
                   │  DoclingController   SummaryController   │
                   │  • parsing + embed   • richiesta async   │
                   │  • chunking          • polling stato     │
                   │  • vector store      • lettura risultato │
                   │  • kNN ask                               │
                   │                                          │
                   │  DocumentStoreService  SummaryService    │
                   │  • salva UnifiedDoc    • Map-Reduce       │
                   │  • stato summary       • Refine fallback  │
                   └───┬──────────┬──────────┬────────────────┘
                       │          │          │
          POST /parse  │   kNN    │  JSON    │  JSON
                       ▼          ▼          ▼
        ┌──────────────┐  ┌───────────────────────────────────┐
        │   docling-   │  │           Elasticsearch            │
        │   service    │  │                                   │
        │  (FastAPI)   │  │  spring-ai-document-index (kNN)   │
        └──────────────┘  │  document-store  (UnifiedDoc)     │
                          │  summary-index   (fullSummary)    │
                          └────────────────┬──────────────────┘
                                           │
                                  ┌────────┴────────┐
                                  │     Ollama       │
                                  │ nomic-embed-text │
                                  │   gemma4:e2b     │
                                  └──────────────────┘
```

---

## Componenti

### `docling-service/`
Microservizio Python (FastAPI) che espone `POST /parse`.  
Riceve un file (PDF, DOCX, HTML, PPTX…), lo elabora con **Docling** (IBM) e restituisce il documento in formato JSON strutturato con testi, tabelle e metadati di layout.  
Supporta OCR opzionale, rilevamento struttura tabelle e limite pagine.

### `spring-ai-rag/`
Applicazione Spring Boot che orchestra l'intera pipeline RAG:
- **Ingestione** (`POST /docling/parse`): riceve il file → lo invia a Docling → normalizza le sezioni → chunking con overlap → embedding → salva su Elasticsearch (3 indici: vector store, document-store, summary)
- **Interrogazione** (`GET /docling/ask`): embedding della domanda → kNN search → costruisce il contesto → risposta LLM
- **Summary** (`POST/GET /summary/{docId}`): strategia Map-Reduce semantica asincrona sui capitoli del documento

### `compose.yaml`
Docker Compose con tutti i servizi infrastrutturali:

| Servizio | Porta | Descrizione |
|---|---|---|
| Elasticsearch 8.11 | 9200 | Vector store (`spring-ai-document-index`), document-store, summary-index |
| Kibana 8.11 | 5601 | UI per esplorare i chunk indicizzati |
| docling-service | 8001 | Parsing documenti (PDF, DOCX, HTML, PPTX…) |

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

## API REST — Curl completi

### 1. Carica e indicizza un documento

```bash
curl -X POST http://localhost:8080/docling/parse \
  -F "file=@/path/to/documento.pdf;type=application/pdf" \
  -H "Accept: application/json"
```

Risposta: `UnifiedDocumentJson` con `docId`, `fileName`, `sourceType`, array `sections`.

```json
{
  "docId": "bc7de48b-c24e-46de-b61b-24490a18dd10",
  "fileName": "documento.pdf",
  "sourceType": "PDF",
  "sections": [
    { "sectionId": "section-0", "title": "Capitolo 1", "pageNumber": 3, "text": "..." }
  ]
}
```

> Formati supportati: `application/pdf`, `text/html`, `text/markdown`,
> `application/vnd.openxmlformats-officedocument.wordprocessingml.document` (DOCX)

---

### 2. Lista documenti indicizzati

```bash
curl http://localhost:8080/docling/documents
```

Risposta: array di `{ docId, fileName }`.

---

### 3. Interroga i documenti (RAG)

```bash
# Domanda globale su tutti i documenti
curl "http://localhost:8080/docling/ask?q=chi+è+Geppetto"

# Limita la ricerca a un documento specifico
curl "http://localhost:8080/docling/ask?q=chi+è+Geppetto&docId=bc7de48b-c24e-46de-b61b-24490a18dd10"

# Filtra per nome file (alternativa a docId)
curl "http://localhost:8080/docling/ask?q=chi+è+Geppetto&fileName=pinocchio.pdf"

# Limita la ricerca a un capitolo/sezione specifica
curl "http://localhost:8080/docling/ask?q=importo&docId=<uuid>&chapter=Allegato+A"

# Filtri su metadati arbitrari (prefisso meta.)
curl "http://localhost:8080/docling/ask?q=contratto&meta.sourceType=PDF"

# Combinazione di più filtri (AND implicito)
curl "http://localhost:8080/docling/ask?q=trama&docId=<uuid>&chapter=Capitolo+3&meta.sourceType=PDF"
```

Risposta: testo plain con la risposta generata dall'LLM basata esclusivamente sul contesto recuperato.

---

### 4. Richiedi la generazione del summary

Avvia la generazione in background (asincrona). Risponde subito con `202 Accepted`.

```bash
curl -X POST http://localhost:8080/summary/bc7de48b-c24e-46de-b61b-24490a18dd10 \
  -H "Accept: application/json"
```

Risposta:
```json
{
  "docId": "bc7de48b-c24e-46de-b61b-24490a18dd10",
  "status": "PENDING",
  "message": "Generazione summary avviata in background"
}
```

Se il summary è già in corso (`PENDING` / `PROCESSING`) non viene rilanciato un nuovo job.
Può essere rilasciato di nuovo se lo stato era `COMPLETED` o `FAILED`.

---

### 5. Leggi lo stato / il risultato del summary

```bash
curl http://localhost:8080/summary/bc7de48b-c24e-46de-b61b-24490a18dd10
```

**Se non ancora completato** — risposta con solo i metadati di stato:
```json
{
  "docId": "bc7de48b-...",
  "fileName": "pinocchio.pdf",
  "status": "PROCESSING",
  "summaryRequestedAt": "2026-05-27T20:05:00Z",
  "summaryError": ""
}
```

**Se completato** — risposta con il summary completo:
```json
{
  "docId": "bc7de48b-...",
  "fileName": "pinocchio.pdf",
  "status": "COMPLETED",
  "fullSummary": "1. Introduzione\nIl documento raccoglie...",
  "sectionSummaries": [
    {
      "sectionId": "section-0",
      "title": "Capitolo 1",
      "pageNumber": 3,
      "summary": "- Punto chiave 1\n- Punto chiave 2"
    }
  ],
  "createdAt": "2026-05-27T20:08:45Z"
}
```

Ciclo di vita `status`: `NONE` → `PENDING` → `PROCESSING` → `COMPLETED` / `FAILED`

---

### 6. Elimina solo il summary di un documento

Rimuove il riassunto dall'`summary-index` e riporta `summaryStatus=NONE` nel `document-store`.  
Il documento rimane indicizzato nel vector store e può essere interrogato con `/docling/ask`.

```bash
curl -X DELETE http://localhost:8080/summary/bc7de48b-c24e-46de-b61b-24490a18dd10
```

Risposta:
```json
{
  "docId": "bc7de48b-c24e-46de-b61b-24490a18dd10",
  "message": "Summary eliminato. Status reimpostato a NONE."
}
```

---

### 7. Elimina completamente un documento

Rimuove i chunk dal vector store, il record dal `document-store` e il summary dall'`summary-index`.

```bash
curl -X DELETE http://localhost:8080/docling/bc7de48b-c24e-46de-b61b-24490a18dd10
```

Risposta:
```json
{
  "docId": "bc7de48b-c24e-46de-b61b-24490a18dd10",
  "message": "Documento eliminato da vector store, document-store e summary-index"
}
```

---

### 8. Reset completo (tutti i documenti)

Elimina tutto il contenuto dai tre indici con `deleteByQuery match_all`.  
Le mappature e lo schema degli indici vengono preservati (nessun restart necessario).

```bash
curl -X DELETE http://localhost:8080/admin/all
```

Risposta:
```json
{
  "message": "Reset completato: tutti i documenti eliminati da vector store, document-store e summary-index",
  "note": "Le mappature degli indici sono state preservate (nessun restart necessario)"
}
```

---

### Ricerca vettoriale con filtri

Tutti i filtri sono opzionali e combinati in AND tramite `FilterExpressionBuilder` (type-safe, nessuna injection SQL/JNDI).

---

## Indici Elasticsearch

| Indice | Tipo | Contenuto |
|---|---|---|
| `spring-ai-document-index` | `dense_vector` 768d | Chunk con embedding per kNN search |
| `document-store` | JSON | `UnifiedDocumentJson` + stato summary per ogni documento |
| `summary-index` | JSON | `fullSummary` + `sectionSummaries[]` prodotti dall'LLM |

---

## Struttura repository

```
spring-ai-architecture/
├── compose.yaml              # Infrastruttura Docker
├── docling-service/          # Microservizio parsing (Python/FastAPI)
│   ├── main.py
│   ├── Dockerfile
│   └── requirements.txt
├── spring-ai-rag/            # App principale Spring Boot (RAG + Summary)
│   └── src/…
├── esempi/                   # File di test (non in git)
├── doc.sh                    # Gestione documenti: upload + summary + status (script principale)
├── upload-pinocchio.sh       # Upload rapido Pinocchio + RAG
├── query.sh                  # Interrogazione RAG interattiva
├── list-docs.sh              # Lista tutti i documenti indicizzati
├── delete-index.sh           # Cancella un indice Elasticsearch
└── generate-and-index-docs.sh  # Genera 10 HTML di test e li indicizza
```

---

## Script shell

```bash
# Upload interattivo: chiede il file, poi se generare il summary
./doc.sh --interactive

# Mostra tutte le opzioni disponibili
./doc.sh

# Upload diretto senza summary
./doc.sh /path/to/documento.pdf

# Upload + richiesta summary (asincrona)
./doc.sh /path/to/documento.pdf --summary

# Upload + summary + attende e stampa il risultato a schermo
./doc.sh /path/to/documento.pdf --summary --wait

# Richiedi summary per un documento già indicizzato
./doc.sh summary <docId>

# Summary + polling automatico fino al completamento
./doc.sh summary <docId> --wait

# Controlla stato summary (NONE/PENDING/PROCESSING/COMPLETED/FAILED)
./doc.sh status <docId>

# Lista documenti indicizzati
./doc.sh list

# Interrogazione RAG
./doc.sh query "chi è Geppetto?"
./doc.sh query "chi è Geppetto?" --doc <docId>
./doc.sh query "importo" --file fattura.pdf --chapter "Allegato A"

# Elimina solo il summary (il documento resta nel vector store)
./doc.sh delete-summary <docId>

# Elimina completamente un documento da tutti gli indici (chiede conferma)
./doc.sh delete <docId>

# Reset completo di tutti i documenti (doppia conferma)
./doc.sh delete-all

# Cancella un indice (operazione bassa livello)
./delete-index.sh spring-ai-document-index
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

