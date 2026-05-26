package elastic.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final Environment env;

    @Value("${spring.ai.ollama.chat.options.model:—}")
    private String ollamaChatModel;

    @Value("${spring.ai.ollama.embedding.model:—}")
    private String ollamaEmbeddingModel;

    @Value("${spring.ai.openai.chat.options.model:—}")
    private String openAiChatModel;

    @Value("${spring.ai.openai.base-url:—}")
    private String openAiBaseUrl;

    public StartupLogger(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logConfig() {
        String profiles = String.join(", ", env.getActiveProfiles());
        if (profiles.isBlank()) profiles = "default";

        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║              CONFIGURAZIONE ATTIVA                   ║");
        log.info("╠══════════════════════════════════════════════════════╣");
        log.info("║  Profilo        : {}", padRight(profiles, 37) + "║");
        log.info("╠══════════════════════════════════════════════════════╣");

        if (profiles.contains("litellm")) {
            log.info("║  Chat           : OpenAI → LiteLLM proxy             ║");
            log.info("║  Chat model     : {}", padRight(openAiChatModel, 37) + "║");
            log.info("║  Proxy URL      : {}", padRight(openAiBaseUrl, 37) + "║");
            log.info("║  Embedding      : Ollama (nomic-embed-text)           ║");
            log.info("║  Embedding model: {}", padRight(ollamaEmbeddingModel, 37) + "║");
        } else {
            log.info("║  Chat           : Ollama (locale)                     ║");
            log.info("║  Chat model     : {}", padRight(ollamaChatModel, 37) + "║");
            log.info("║  Embedding      : Ollama (locale)                     ║");
            log.info("║  Embedding model: {}", padRight(ollamaEmbeddingModel, 37) + "║");
        }

        log.info("╚══════════════════════════════════════════════════════╝");
    }

    private static String padRight(String s, int n) {
        if (s == null) s = "—";
        return String.format("%-" + n + "s", s.length() > n ? s.substring(0, n - 1) + "…" : s);
    }
}
