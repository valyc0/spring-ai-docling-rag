# Summary Service — Design e Considerazioni

## Contesto: architettura RAG esistente

Il flusso attuale del progetto `spring-ai-rag`:

```
PDF → Docling (microservizio) → DoclingNormalizerService
    → UnifiedDocumentJson (sezioni semantiche)
    → TokenTextSplitter + overlap
    → ElasticsearchVectorStore (indice: spring-ai-document-index)
```

`DoclingNormalizerService` divide il documento in `DocumentSection` in base ai tag
`section_header` / `title` del JSON Docling: ogni sezione ha titolo, numero di pagina e testo.
Questo split semantico è già l'unità ideale per il summarization.

---

## Strategia di summarization

### Scelta della strategia in base alla lunghezza

| Caso | Strategia |
|---|---|
| Testo < context window | **Stuffing** — tutto in un singolo prompt |
| Testo > context window | **Map-Reduce** (scalabile, parallelizzabile) |
| Sezione singola > context window | **Refine** su quella sezione, poi Map-Reduce |

### Map-Reduce semantico (strategia principale per libri)

Le sezioni di `UnifiedDocumentJson` sono già unità semantiche: non serve rechunking.

```
Map:    sezione 1 → summary sezione 1  ─┐
        sezione 2 → summary sezione 2  ─┤ (parallelizzabile con Virtual Threads)
        sezione N → summary sezione N  ─┘
                                        ↓
Reduce: [tutti i summary di sezione] → summary finale del documento
```

**Fallback Refine** (se una singola sezione supera la context window):
```
chunk 1 della sezione → summary parziale
chunk 2 + summary parziale → summary aggiornato
...
→ summary della sezione → entra nella fase Map
```

### Considerazioni pratiche sul chunking

- Preferire split **semantico** (per paragrafo/sezione) rispetto a split fisso a N token
- Aggiungere ~10-15% di overlap tra chunk per non perdere contesto ai bordi
- I LLM fanno bene il riassunto **astrattivo** (riformulazione), non solo estrattivo
- Specificare sempre nel prompt il formato e lo scopo del riassunto

---

## Dove persistere i dati

### JSON da salvare: UnifiedDocumentJson (normalizzato), non il raw Docling

| Criterio | Raw Docling | UnifiedDocumentJson |
|---|---|---|
| Dimensione | Grande (bounding box, coordinate OCR, tabelle) | Compatto (solo testo strutturato) |
| Riutilizzo | Richiede ri-normalizzazione | Pronto per Map-Reduce |
| Stabilità | Dipende dalla versione Docling | Contratto interno stabile |
| Utilità per summary | Bassa (dati geometrici inutili) | Alta (sezioni, titoli, pagine) |

### Storage: Elasticsearch — indice dedicato `document-store`

Motivazioni:
- Già nell'infrastruttura, zero dipendenze nuove
- Gestisce JSON nativo senza ORM o mapping relazionale
- Queryabile per `docId`, `fileName`, `summaryStatus`
- Migrabile a PostgreSQL in futuro (già citato nell'architettura) senza impatto sul codice applicativo

H2 richiederebbe JPA/Hibernate e mapping relazionale per un dato nativamente JSON.
PostgreSQL è la scelta migliore a lungo termine ma aggiunge una dipendenza non ancora presente.

### Stato del summary: nel document-store stesso

Lo stato è un campo del documento, non un'entità separata.
Una sola query risponde a: *"questo doc è indicizzato? ha un summary? è pronto?"*

```json
{
  "docId": "a1b2c3-uuid",
  "fileName": "pinocchio.pdf",
  "sourceType": "PDF",
  "indexedAt": "2026-05-27T10:00:00Z",
  "summaryStatus": "NONE",
  "summaryRequestedAt": null,
  "summaryCompletedAt": null,
  "summaryError": null,
  "unifiedDocument": {
    "docId": "...",
    "fileName": "...",
    "sourceType": "PDF",
    "sections": [ /* DocumentSection[] */ ]
  }
}
```

**Ciclo di vita dello stato:**
```
NONE → PENDING → PROCESSING → COMPLETED
                            → FAILED (+ summaryError)
```

---

## Flusso completo

### Fase 1 — Indicizzazione (modifica al flusso esistente)

```
POST /docling/parse
   │
   ├─ [esistente] Docling → normalize → vectorStore.add(chunks)
   │
   └─ [NUOVO] DocumentStoreService.save(docId, unified)
              ES document-store: { summaryStatus: NONE, unifiedDocument: {...} }
```

### Fase 2 — Richiesta summary (nuova API)

```
POST /summary/{docId}
   │
   ├─ DocumentStoreService.findByDocId(docId)   → verifica esistenza
   ├─ updateSummaryStatus(docId, PENDING)        → aggiorna document-store
   ├─ @Async SummaryService.generate(docId)
   │     ├─ updateSummaryStatus → PROCESSING
   │     ├─ legge unifiedDocument da document-store
   │     ├─ [Map]    riassume ogni DocumentSection via ChatClient
   │     ├─ [Reduce] riassume tutti i section-summary in un fullSummary
   │     ├─ scrive su ES summary-index: { docId, fullSummary, sectionSummaries[] }
   │     └─ updateSummaryStatus → COMPLETED (o FAILED + errore)
   │
   └─ risponde subito: { docId, status: "PENDING" }
```

### Fase 3 — Lettura summary (nuova API)

```
GET /summary/{docId}
   ├─ legge document-store → { summaryStatus, summaryRequestedAt, ... }
   └─ se COMPLETED: legge summary-index → ritorna fullSummary + sectionSummaries[]
```

---

## Indici Elasticsearch

| Indice | Contenuto | Chiave |
|---|---|---|
| `spring-ai-document-index` | chunk embeddings per RAG | id chunk |
| `document-store` *(nuovo)* | UnifiedDocumentJson + stato summary | docId |
| `summary-index` *(nuovo)* | fullSummary + sectionSummaries[] | docId |

---

## Nuovi file da creare

| File | Ruolo |
|---|---|
| `model/SummaryStatus.java` | enum: NONE, PENDING, PROCESSING, COMPLETED, FAILED |
| `model/DocumentRecord.java` | record: docId, fileName, summaryStatus, indexedAt, unifiedDocument, ... |
| `model/SectionSummary.java` | record: sectionId, title, pageNumber, summary |
| `model/DocumentSummary.java` | record: docId, fileName, fullSummary, List\<SectionSummary\>, createdAt |
| `summary/DocumentStoreService.java` | save / findByDocId / updateSummaryStatus su `document-store` |
| `summary/SummaryService.java` | logica Map-Reduce @Async con ChatClient |
| `summary/SummaryController.java` | POST /summary/{docId}, GET /summary/{docId}, GET /summary (list) |

## Modifiche a file esistenti

| File | Modifica |
|---|---|
| `DoclingController.java` | +1 riga: `documentStoreService.save(docId, unified)` dopo `vectorStore.add()` |
| `application.properties` | +2 property: `summary.document-store-index`, `summary.index-name` |
| `pom.xml` | nessuna nuova dipendenza (ES client già presente) |

---

## Struttura package risultante

```
elastic/rag/
├── DemoApplication.java
├── StartupLogger.java
├── config/
│   └── DoclingClientConfig.java
├── docling/
│   ├── DoclingController.java        ← modifica minore
│   └── DoclingNormalizerService.java
├── model/
│   ├── DoclingResponse.java
│   ├── DocumentInfo.java
│   ├── DocumentSection.java
│   ├── UnifiedDocumentJson.java
│   ├── SummaryStatus.java            ← nuovo
│   ├── DocumentRecord.java           ← nuovo
│   ├── SectionSummary.java           ← nuovo
│   └── DocumentSummary.java          ← nuovo
└── summary/
    ├── DocumentStoreService.java     ← nuovo
    ├── SummaryService.java           ← nuovo
    └── SummaryController.java        ← nuovo
```
