package elastic.rag.docling;

import elastic.rag.model.DoclingResponse;
import elastic.rag.model.UnifiedDocumentJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Controller POC per il flusso: upload file → Docling → normalizzazione → UnifiedDocumentJson.
 * In futuro questo endpoint scatenerà il ChunkEmbedService per l'indicizzazione vettoriale.
 */
@RestController
@RequestMapping("/docling")
public class DoclingController {

    private static final Logger log = LoggerFactory.getLogger(DoclingController.class);

    private final RestTemplate            doclingRestTemplate;
    private final String                   doclingUrl;
    private final DoclingNormalizerService normalizer;

    public DoclingController(RestTemplate doclingRestTemplate,
                             @org.springframework.beans.factory.annotation.Value("${docling.service.url}") String doclingUrl,
                             DoclingNormalizerService normalizer) {
        this.doclingRestTemplate = doclingRestTemplate;
        this.doclingUrl          = doclingUrl;
        this.normalizer          = normalizer;
    }

    /**
     * POST /docling/parse
     * Riceve un file, lo invia al servizio Docling, logga la risposta grezza
     * e restituisce il documento normalizzato nel formato UnifiedDocumentJson.
     */
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UnifiedDocumentJson> parse(
            @RequestPart("file") MultipartFile file) throws IOException {

        String originalName = file.getOriginalFilename();
        log.info("File ricevuto: '{}' ({} bytes)", originalName, file.getSize());

        // Costruisce il body multipart per la chiamata a Docling
        // ByteArrayResource con getFilename() override è necessario affinché
        // FormHttpMessageConverter includa il filename nel Content-Disposition
        ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return originalName;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        log.info("Invio file a Docling service...");
        Map<String, Object> rawMap = doclingRestTemplate.exchange(
                doclingUrl + "/parse",
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        ).getBody();

        DoclingResponse doclingResponse = new DoclingResponse(rawMap, null);

        // Log della risposta grezza
        log.info("Risposta Docling ricevuta — chiavi JSON: {}", doclingResponse.doclingJson().keySet());
        log.info("Numero elementi 'texts': {}", countTexts(doclingResponse));
        if (doclingResponse.markdown() != null) {
            int previewLen = Math.min(500, doclingResponse.markdown().length());
            log.debug("Markdown preview ({}c):\n{}", previewLen,
                    doclingResponse.markdown().substring(0, previewLen));
        }

        // Normalizzazione nel formato unificato
        String docId = UUID.randomUUID().toString();
        UnifiedDocumentJson unified = normalizer.normalize(doclingResponse, docId, originalName);

        log.info("Normalizzazione completata — {} sezioni, docId={}", unified.sections().size(), docId);

        return ResponseEntity.ok(unified);
    }

    private int countTexts(DoclingResponse response) {
        Object texts = response.doclingJson().get("texts");
        if (texts instanceof java.util.List<?> list) return list.size();
        return 0;
    }
}
