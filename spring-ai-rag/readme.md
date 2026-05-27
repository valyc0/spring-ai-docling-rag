# spring-ai-rag

Pipeline **RAG (Retrieval-Augmented Generation)** con **Summary Service** integrato, costruita con Spring Boot 3, Spring AI 1.0, Elasticsearch e Ollama.

Permette di caricare documenti (PDF, DOCX, HTML…), indicizzarli come vettori su Elasticsearch, interrogarli in linguaggio naturale con un LLM locale e generare riassunti automatici — tutto senza servizi cloud.

---

## Architettura

```
POST /docling/parse
  PDF/HTML/DOCX
       │
       ▼
  Docling Service (FastAPI)
       │ JSON strutturato
       ▼
  DoclingNormalizerService
       │ UnifiedDocumentJson (sezioni semantiche)
       │
       ├──► TokenTextSplitter (400 token) + overlap (200 char)
       │         │
       │         ▼
       │    nomic-embed-text (Ollama)  →  spring-ai-document-index (ES)
       │
       └──► DocumentStoreService  →  document-store (ES)
                                      { UnifiedDocumentJson + summaryStatus }

GET /docling/ask?q=...
  Domanda → nomic-embed-text → kNN topK=8 → gemma4:e2b → Risposta

POST /summary/{docId}          [asincrono]
  DocumentStoreService.findByDocId
       │ UnifiedDocumentJson
       ▼
  SummaryService (@Async)
       │
       ├── STUFFING (testo < 6000 char): tutto in un prompt
       │
       └── MAP-REDUCE (testo > 6000 char):
             Map:    ogni sezione → summary sezione  (via gemma4:e2b)
             Reduce: tutti i section-summary → fullSummary
             (Refine fallback se singola sezione > 3000 char)
       │
       ▼
  summary-index (ES)  +  document-store status → COMPLETED

GET /summary/{docId}
  → status + fullSummary + sectionSummaries[]

DELETE /summary/{docId}
  → rimuove da summary-index + reset summaryStatus=NONE nel document-store

DELETE /docling/{docId}
  → deleteByQuery sul vector store + delete dal document-store + delete dal summary-index

DELETE /admin/all
  → deleteByQuery match_all su tutti e 3 gli indici (schema preservato)
```

### Componenti

| Componente | Tecnologia | Ruolo |
|---|---|---|
| **Spring Boot 3.4** | Java 21 | Orchestrazione REST |
| **Spring AI 1.0** | — | Astrazione embedding, vector store, LLM |
| **Docling** (IBM) | FastAPI + Python | Parsing PDF/DOCX/HTML → JSON strutturato |
| **nomic-embed-text** | Ollama | Embedding 768 dimensioni |
| **gemma4:e2b** | Ollama | Chat / RAG / Summary |
| **Elasticsearch 8.11** | — | 3 indici: vector store, document-store, summary-index |
| **Kibana 8.11** | — | Esplorazione indici (opzionale) |

### Indici Elasticsearch

| Indice | Contenuto |
|---|---|
| `spring-ai-document-index` | Chunk con embedding `dense_vector` 768d — creato all'avvio con `initialize-schema=true` |
| `document-store` | `UnifiedDocumentJson` + stato summary (`NONE/PENDING/PROCESSING/COMPLETED/FAILED`) |
| `summary-index` | `fullSummary` + `sectionSummaries[]` prodotti dall'LLM |

---

## Flusso di ingestione (`POST /docling/parse`)

1. Il client carica un file via `multipart/form-data`
2. `DoclingController` invia il file al microservizio **Docling** (`http://localhost:8001/parse`)
3. Docling restituisce il documento in formato JSON nativo (`texts`, `tables`, `pictures`)
4. `DoclingNormalizerService` trasforma il JSON in `UnifiedDocumentJson`:
   - raggruppa gli elementi per sezione (`section_header` / `title`)
   - accumula i testi (`text`, `paragraph`, `list_item`, `caption`)
   - estrae il numero di pagina da `prov[0].page_no`
5. Ogni sezione diventa un `Document` Spring AI con metadata (`docId`, `fileName`, `title`, `pageNumber`, `sourceType`)
6. `TokenTextSplitter` divide ogni sezione in chunk da **400 token**
7. Viene aggiunto un **overlap di 200 caratteri** tra chunk consecutivi dello stesso documento
8. `ElasticsearchVectorStore` calcola l'embedding di ogni chunk e lo salva in `spring-ai-document-index`
9. `DocumentStoreService` salva l'`UnifiedDocumentJson` completo in `document-store` con `summaryStatus=NONE`

## Flusso di interrogazione (`GET /docling/ask?q=`)

1. La domanda viene inviata al vector store come query kNN (`topK=8`)
2. Elasticsearch restituisce i chunk più simili semanticamente
3. I chunk vengono concatenati come contesto
4. Il contesto + la domanda vengono passati a `gemma4:e2b` con un system prompt che vincola il modello a rispondere **solo** in base al contesto
5. La risposta viene restituita al client

## Flusso di summarization (`POST/GET /summary/{docId}`)

1. `POST /summary/{docId}` — aggiorna `summaryStatus=PENDING` e lancia `SummaryService.generate()` in `@Async`
2. Il service legge `UnifiedDocumentJson` da `document-store` (no re-chiamata a Docling)
3. Seleziona la strategia in base al totale caratteri:
   - **Stuffing** (≤ 6000 char): tutto in un prompt
   - **Map-Reduce** (> 6000 char): riassume ogni sezione → combina i riassunti
   - **Refine** (sezione singola > 3000 char): chunk incrementali prima della fase Map
4. Scrive il risultato in `summary-index` e aggiorna `summaryStatus=COMPLETED`
5. `GET /summary/{docId}` restituisce lo stato o il summary completo se `COMPLETED`

## Flusso di cancellazione

- **`DELETE /summary/{docId}`** — elimina il summary dall'`summary-index` e riporta `summaryStatus=NONE` nel `document-store`. Il documento rimane consultabile via `/docling/ask`.
- **`DELETE /docling/{docId}`** — cancellazione completa: `deleteByQuery` sui chunk nel vector store, delete dal `document-store`, delete dal `summary-index`.
- **`DELETE /admin/all`** — reset di tutti e tre gli indici con `deleteByQuery match_all`. Le mappature ES vengono preservate.

---

## Prerequisiti

- **JDK 21+**
- **Maven 3.9+**
- **Docker / Docker Compose** — per Elasticsearch, Kibana e Docling
- **Ollama** in esecuzione su `localhost:11434` con i modelli:

```bash
ollama pull nomic-embed-text   # embedding 768d
ollama pull llama3.2:3b        # LLM chat
```

---

## Avvio

### 1. Avvia i servizi con Docker Compose

Dalla root del repository (`spring-ai-architecture/`):

```bash
docker compose up -d
```

Avvia: Elasticsearch (`9200`), Kibana (`5601`), Docling service (`8001`).

> **Nota:** Ollama deve girare separatamente come processo host.

### 2. Configura la password Elasticsearch

```bash
export ELASTICSEARCH_PASSWORD=changeme
```

Oppure aggiungila in un file `.env` nella cartella `spring-ai-rag/`.

### 3. Avvia l'applicazione Spring Boot

```bash
cd spring-ai-rag
./start.sh
# oppure
./mvnw spring-boot:run
```

L'app parte su `http://localhost:8080`.

---

## API REST

### Carica e indicizza un documento

```bash
curl -X POST http://localhost:8080/docling/parse \
  -F "file=@/path/to/documento.pdf;type=application/pdf" \
  -H "Accept: application/json"
```

Risposta: `UnifiedDocumentJson` con `docId`, `fileName`, `sourceType`, lista sezioni.

### Interroga i documenti indicizzati

```bash
curl "http://localhost:8080/docling/ask?q=chi+è+Geppetto"
```

Risposta: testo plain con la risposta generata dall'LLM.

### Avvia e leggi un summary

```bash
# Avvia (asincrono, risponde subito con 202)
curl -X POST http://localhost:8080/summary/<docId>

# Polling stato / lettura risultato
curl http://localhost:8080/summary/<docId>
```

### Eliminazione

```bash
# Elimina solo il summary (il doc resta nel vector store)
curl -X DELETE http://localhost:8080/summary/<docId>

# Elimina completamente un documento da tutti gli indici
curl -X DELETE http://localhost:8080/docling/<docId>

# Reset completo: elimina tutti i documenti (mappature preservate)
curl -X DELETE http://localhost:8080/admin/all
```

---

## Configurazione (`application.properties`)

```properties
# Modelli Ollama
spring.ai.ollama.chat.options.model=llama3.2:3b
spring.ai.ollama.chat.options.num-ctx=4096
spring.ai.ollama.embedding.model=nomic-embed-text

# Elasticsearch
spring.elasticsearch.uris=http://localhost:9200
spring.elasticsearch.username=elastic
spring.elasticsearch.password=${ELASTICSEARCH_PASSWORD}
spring.ai.vectorstore.elasticsearch.initialize-schema=true
spring.ai.vectorstore.elasticsearch.dimensions=768

# Docling microservice
docling.service.url=http://localhost:8001
```

---

## Struttura del progetto

```
spring-ai-rag/
├── src/main/java/elastic/rag/
│   ├── DemoApplication.java
│   ├── config/
│   │   ├── AsyncConfig.java           # @EnableAsync per SummaryService
│   │   └── DoclingClientConfig.java   # Bean RestTemplate per Docling
│   ├── docling/
│   │   ├── DoclingController.java     # POST /docling/parse  GET /docling/ask  DELETE /docling/{docId}
│   │   └── DoclingNormalizerService.java
│   ├── model/
│   │   ├── DoclingResponse.java
│   │   ├── DocumentRecord.java        # Record persistito in document-store
│   │   ├── DocumentSection.java
│   │   ├── DocumentSummary.java       # Persistito in summary-index
│   │   ├── SectionSummary.java        # Output fase Map
│   │   ├── SummaryStatus.java         # Enum NONE/PENDING/PROCESSING/COMPLETED/FAILED
│   │   └── UnifiedDocumentJson.java
│   └── summary/
│       ├── AdminController.java       # DELETE /admin/all
│       ├── DocumentStoreService.java  # CRUD document-store + delete multi-indice
│       ├── SummaryController.java     # POST/GET/DELETE /summary/{docId}
│       └── SummaryService.java        # Map-Reduce + Refine asincrono
└── src/main/resources/
    └── application.properties
```

---

## Logging

L'applicazione emette log strutturati con prefissi per tracciare ogni fase:

| Prefisso | Fase |
|---|---|
| `[DOCLING RAW]` | JSON grezzo ricevuto da Docling |
| `[NORMALIZER]` | Distribuzione label e sezioni estratte |
| `[UNIFIED]` | Sezioni nel formato unificato |
| `[CHUNK]` | Ogni chunk prima dell'indicizzazione |
| `[ASK]` | Query ES, chunk ritornati, prompt, risposta AI |


Il progetto implementa un pattern **RAG (Retrieval-Augmented Generation)**:

1. **Ingest** — un PDF viene caricato via REST, suddiviso in chunk con `TokenTextSplitter` e vettorializzato tramite il modello di embedding (Ollama `nomic-embed-text`). I chunk e i relativi vettori vengono salvati su Elasticsearch.
2. **Query** — la domanda dell'utente viene vettorializzata e confrontata con i chunk salvati tramite ricerca kNN. I chunk più simili vengono usati come contesto e passati al modello LLM (Ollama `gemma4`) che genera la risposta finale.

```
PDF ──► TokenTextSplitter ──► nomic-embed-text ──► Elasticsearch (dense_vector 768 dim)
                                                          │
Domanda ──► nomic-embed-text ──► kNN search ─────────────┘
                                      │
                               top-K chunk
                                      │
                               gemma4 (LLM) ──► Risposta
```

## Dipendenze

* JDK 21+
* Maven
* Elasticsearch in esecuzione su `localhost:9200`
* [Ollama](https://ollama.com) in esecuzione su `localhost:11434` con i modelli:
  * `nomic-embed-text` — embedding (768 dimensioni)
  * `gemma4:e2b` — chat / generazione testo

Per scaricare i modelli Ollama:
```bash
ollama pull nomic-embed-text
ollama pull gemma4:e2b
```

## Configurazione (`application.properties`)

```properties
spring.application.name=spring_ai-elasticsearch-vector-search

spring.servlet.multipart.max-file-size=20MB
spring.servlet.multipart.max-request-size=20MB

# Ollama
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.options.model=gemma4:e2b
spring.ai.ollama.embedding.model=nomic-embed-text

# Elasticsearch
spring.elasticsearch.uris=http://localhost:9200
spring.elasticsearch.username=elastic
spring.elasticsearch.password=${ELASTICSEARCH_PASSWORD}
spring.ai.vectorstore.elasticsearch.initialize-schema=true
spring.ai.vectorstore.elasticsearch.dimensions=768
```

La variabile `ELASTICSEARCH_PASSWORD` va impostata come variabile d'ambiente:
```bash
export ELASTICSEARCH_PASSWORD=la_tua_password
```

## Build e avvio

```bash
mvn clean package -DskipTests
java -jar target/spring_ai-elasticsearch-vector-search-1.0.0-SNAPSHOT.jar
```

## Endpoint REST

### Indicizzare un PDF

```bash
curl -X POST http://localhost:8080/rag/ingest \
  -F "file=@/percorso/al/documento.pdf"
```

Risposta attesa: `Done!`

### Interrogare il sistema RAG

```bash
curl "http://localhost:8080/rag/query?question=La+tua+domanda+qui"
```

## Verifica su Elasticsearch

### Lista indici
```bash
curl -u elastic:${ELASTICSEARCH_PASSWORD} \
  "http://localhost:9200/_cat/indices?v"
```

### Contare i documenti indicizzati
```bash
curl -u elastic:${ELASTICSEARCH_PASSWORD} \
  "http://localhost:9200/spring-ai-document-index/_count?pretty"
```

### Leggere i chunk (testo + metadati)
```bash
curl -u elastic:${ELASTICSEARCH_PASSWORD} \
  -H "Content-Type: application/json" \
  "http://localhost:9200/spring-ai-document-index/_search?pretty" \
  -d '{"size": 5, "_source": ["content", "metadata"], "query": {"match_all": {}}}'
```

### Vedere il vettore embedding di un documento
```bash
curl -u elastic:${ELASTICSEARCH_PASSWORD} \
  -H "Content-Type: application/json" \
  "http://localhost:9200/spring-ai-document-index/_search?pretty" \
  -d '{"size": 1, "_source": ["content", "metadata", "embedding"], "query": {"match_all": {}}}'
```

> Il campo `embedding` è un `dense_vector` di 768 dimensioni: Elasticsearch lo esclude dal `_source` per default, va richiesto esplicitamente.
