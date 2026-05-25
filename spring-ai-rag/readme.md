# spring-ai-rag

Pipeline **RAG (Retrieval-Augmented Generation)** costruita con Spring Boot 3, Spring AI 1.0, Elasticsearch e Ollama.

Permette di caricare documenti PDF, indicizzarli come vettori su Elasticsearch tramite un modello di embedding locale, e interrogarli in linguaggio naturale con un LLM locale — tutto senza servizi cloud.

---

## Architettura

```
┌─────────────────────────────────────────────────────────────────┐
│                        POST /docling/parse                       │
│                                                                  │
│  PDF ──► Docling Service ──► UnifiedDocumentJson                 │
│              (FastAPI)           │                               │
│                         TokenTextSplitter (400 token)            │
│                         + overlap (200 char)                     │
│                                  │                               │
│                         nomic-embed-text (Ollama)                │
│                                  │                               │
│                         Elasticsearch (dense_vector 768d)        │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        GET /docling/ask?q=                       │
│                                                                  │
│  Domanda ──► nomic-embed-text ──► kNN search (topK=8)           │
│                                         │                        │
│                                    top-K chunk                   │
│                                         │                        │
│                                   llama3.2:3b ──► Risposta       │
└─────────────────────────────────────────────────────────────────┘
```

### Componenti

| Componente | Tecnologia | Ruolo |
|---|---|---|
| **Spring Boot 3.4** | Java 21 | Orchestrazione REST |
| **Spring AI 1.0** | — | Astrazione embedding, vector store, LLM |
| **Docling** (IBM) | FastAPI + Python | Parsing PDF → JSON strutturato |
| **nomic-embed-text** | Ollama | Embedding 768 dimensioni |
| **llama3.2:3b** | Ollama | Chat / generazione risposta |
| **Elasticsearch 8.11** | — | Vector store (dense_vector kNN) |
| **Kibana 8.11** | — | Esplorazione indici (opzionale) |

---

## Flusso di ingestione (`POST /docling/parse`)

1. Il client carica un PDF via `multipart/form-data`
2. `DoclingController` invia il file al microservizio **Docling** (`http://localhost:8001/parse`)
3. Docling restituisce il documento in formato JSON nativo con `texts`, `tables`, `pictures`
4. `DoclingNormalizerService` trasforma il JSON in `UnifiedDocumentJson`:
   - raggruppa gli elementi per sezione (`section_header` / `title`)
   - accumula i testi (`text`, `paragraph`, `list_item`, `caption`)
   - estrae il numero di pagina da `prov[0].page_no`
5. Ogni sezione viene convertita in un `Document` Spring AI con metadata (`docId`, `fileName`, `title`, `pageNumber`, `sourceType`)
6. `TokenTextSplitter` divide ogni sezione in chunk da **400 token**
7. Viene aggiunto un **overlap di 200 caratteri** tra chunk consecutivi dello stesso documento
8. `ElasticsearchVectorStore` calcola l'embedding di ogni chunk (`nomic-embed-text`) e lo salva su Elasticsearch

## Flusso di interrogazione (`GET /docling/ask?q=`)

1. La domanda viene inviata al vector store come query kNN (`topK=8`)
2. Elasticsearch restituisce i chunk più simili semanticamente
3. I chunk vengono concatenati come contesto
4. Il contesto + la domanda vengono passati a `llama3.2:3b` con un system prompt che vincola il modello a rispondere **solo** in base al contesto
5. La risposta viene restituita al client

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

### Script di test (Pinocchio)

```bash
# Indicizza pinocc_small.pdf
./upload-pinocchio.sh

# Fai una domanda
./upload-pinocchio.sh ask "che attrezzo prese Mastr'Antonio?"
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
│   ├── DemoApplication.java          # Entry point + RagService + RagController legacy
│   ├── config/
│   │   └── DoclingClientConfig.java  # Bean RestTemplate per Docling
│   ├── docling/
│   │   ├── DoclingController.java    # POST /docling/parse  GET /docling/ask
│   │   └── DoclingNormalizerService.java  # JSON Docling → UnifiedDocumentJson
│   └── model/
│       ├── DoclingResponse.java      # Risposta raw Docling
│       ├── UnifiedDocumentJson.java  # Formato normalizzato comune
│       └── DocumentSection.java     # Singola sezione/capitolo
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
