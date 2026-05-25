package elastic.rag.docling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import elastic.rag.model.DoclingResponse;
import elastic.rag.model.UnifiedDocumentJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/docling")
public class DoclingController {

    private static final Logger log = LoggerFactory.getLogger(DoclingController.class);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Dimensione chunk in token (nomic-embed-text: contex 8192, usiamo 400 per lasciare spazio all'overlap) */
    private static final int CHUNK_TOKENS   = 400;
    /** Overlap in caratteri tra chunk consecutivi dello stesso documento (~20-30 token) */
    private static final int OVERLAP_CHARS  = 200;

    private final RestTemplate             doclingRestTemplate;
    private final String                   doclingUrl;
    private final DoclingNormalizerService normalizer;
    private final ElasticsearchVectorStore vectorStore;
    private final ChatClient               chatClient;

    public DoclingController(RestTemplate doclingRestTemplate,
                             @Value("${docling.service.url}") String doclingUrl,
                             DoclingNormalizerService normalizer,
                             ElasticsearchVectorStore vectorStore,
                             ChatClient.Builder chatClientBuilder) {
        this.doclingRestTemplate = doclingRestTemplate;
        this.doclingUrl          = doclingUrl;
        this.normalizer          = normalizer;
        this.vectorStore         = vectorStore;
        this.chatClient          = chatClientBuilder.build();
    }

    /**
     * POST /docling/parse
     * upload file → Docling → UnifiedDocumentJson → embedding → Elasticsearch
     */
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UnifiedDocumentJson> parse(
            @RequestPart("file") MultipartFile file) throws IOException {

        String originalName = file.getOriginalFilename();
        log.info("File ricevuto: '{}' ({} bytes)", originalName, file.getSize());

        ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
            @Override public String getFilename() { return originalName; }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        log.info("Invio file a Docling service...");
        Map<String, Object> rawMap = doclingRestTemplate.exchange(
                doclingUrl + "/parse",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        ).getBody();

        DoclingResponse doclingResponse = new DoclingResponse(rawMap, null);
        log.info("[DOCLING RAW] chiavi top-level: {}", doclingResponse.doclingJson().keySet());
        log.info("[DOCLING RAW] totale texts: {}", countTexts(doclingResponse));
        if (log.isDebugEnabled()) {
            try {
                log.debug("[DOCLING RAW] JSON completo:\n{}", JSON.writeValueAsString(doclingResponse.doclingJson()));
            } catch (Exception e) { log.warn("Impossibile serializzare JSON Docling", e); }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawTexts = (List<Map<String, Object>>) doclingResponse.doclingJson().get("texts");
        if (rawTexts != null) {
            rawTexts.stream().limit(3).forEach(t ->
                log.info("[DOCLING RAW] text sample — label='{}' text='{}'",
                        t.get("label"), truncate((String) t.get("text"), 120)));
        }

        String docId = UUID.randomUUID().toString();
        UnifiedDocumentJson unified = normalizer.normalize(doclingResponse, docId, originalName);
        log.info("[UNIFIED] {} sezioni per docId={}", unified.sections().size(), docId);
        if (log.isDebugEnabled()) {
            try {
                log.debug("[UNIFIED] JSON trasformato:\n{}", JSON.writeValueAsString(unified));
            } catch (Exception e) { log.warn("Impossibile serializzare UnifiedDocumentJson", e); }
        }
        unified.sections().forEach(s ->
            log.info("[UNIFIED] sezione idx={} title='{}' page={} textLen={}",
                    s.sectionId(), s.title(), s.pageNumber(),
                    s.text() != null ? s.text().length() : 0));

        List<Document> documents = unified.sections().stream()
                .filter(s -> s.text() != null && !s.text().isBlank())
                .map(s -> Document.builder()
                        .id(s.sectionId())
                        .text(s.text())
                        .metadata("docId",      docId)
                        .metadata("fileName",   originalName)
                        .metadata("title",      s.title() != null ? s.title() : "")
                        .metadata("pageNumber", s.pageNumber() != null ? s.pageNumber() : 0)
                        .metadata("sourceType", unified.sourceType())
                        .build())
                .toList();

        List<Document> chunks = splitWithOverlap(documents);
        log.info("[CHUNKS] {} chunk pronti per l'indicizzazione", chunks.size());
        chunks.forEach(c ->
            log.info("[CHUNK] id={} title='{}' page={} len={} preview='{}'",
                    c.getId(),
                    c.getMetadata().get("title"),
                    c.getMetadata().get("pageNumber"),
                    c.getText().length(),
                    truncate(c.getText(), 100)));
        vectorStore.add(chunks);
        log.info("[CHUNKS] indicizzati {} chunk su Elasticsearch per docId={}", chunks.size(), docId);

        return ResponseEntity.ok(unified);
    }

    /**
     * GET /docling/ask?q=...
     * Ricerca RAG sul vector store con risposta generata da LLM.
     */
    @GetMapping("/ask")
    public ResponseEntity<String> ask(@RequestParam("q") String question) {
        log.info("[ASK] domanda: '{}'", question);

        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(8)
                .similarityThreshold(0.0)
                .build();
        log.info("[ASK] query Elasticsearch — topK={} threshold={} query='{}'",
                searchRequest.getTopK(), searchRequest.getSimilarityThreshold(), question);

        List<Document> chunks = vectorStore.similaritySearch(searchRequest);
        log.info("[ASK] {} chunk ritornati da Elasticsearch", chunks.size());
        chunks.forEach(c -> {
            double score = c.getScore() != null ? c.getScore() : -1;
            log.info("[ASK] chunk id={} title='{}' page={} score={} preview='{}'",
                    c.getId(),
                    c.getMetadata().get("title"),
                    c.getMetadata().get("pageNumber"),
                    String.format("%.4f", score),
                    truncate(c.getText(), 120));
        });

        String context = chunks.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n---\n" + b);

        String userPrompt = "CONTESTO:\n" + context + "\n\nDOMANDA: " + question;
        log.info("[ASK] prompt utente ({} chars): '{}'", userPrompt.length(), truncate(userPrompt, 300));

        String answer = chatClient
                .prompt()
                .system("""
                        Sei un assistente che risponde a domande sui documenti indicizzati.
                        Usa ESCLUSIVAMENTE le informazioni presenti nel CONTESTO fornito per rispondere.
                        Rispondi nella stessa lingua della domanda.
                        Se il contesto non contiene informazioni sufficienti, dì semplicemente che non lo sai.
                        """)
                .user(userPrompt)
                .call()
                .content();

        log.info("[ASK] risposta AI: '{}'", truncate(answer, 300));
        return ResponseEntity.ok(answer);
    }

    /**
     * Splitta i documenti in chunk da CHUNK_TOKENS token, poi aggiunge
     * OVERLAP_CHARS caratteri finali del chunk precedente come prefisso del successivo
     * (solo tra chunk dello stesso documento).
     */
    private List<Document> splitWithOverlap(List<Document> docs) {
        TokenTextSplitter splitter = new TokenTextSplitter(CHUNK_TOKENS, 50, 5, 10000, true);
        List<Document> raw = splitter.apply(docs);

        List<Document> result = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Document cur = raw.get(i);
            if (i > 0) {
                Document prev  = raw.get(i - 1);
                String prevDoc = (String) prev.getMetadata().get("docId");
                String curDoc  = (String) cur.getMetadata().get("docId");
                if (prevDoc != null && prevDoc.equals(curDoc)) {
                    String tail    = prev.getText();
                    String overlap = tail.length() > OVERLAP_CHARS
                            ? tail.substring(tail.length() - OVERLAP_CHARS)
                            : tail;
                    cur = Document.builder()
                            .id(cur.getId())
                            .text(overlap + " " + cur.getText())
                            .metadata(cur.getMetadata())
                            .build();
                }
            }
            result.add(cur);
        }
        return result;
    }

    private int countTexts(DoclingResponse response) {
        Object texts = response.doclingJson().get("texts");
        if (texts instanceof List<?> list) return list.size();
        return 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
