package elastic.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Abilita il supporto @Async di Spring, necessario per la generazione
 * asincrona dei summary in SummaryService.
 */
@Configuration
@EnableAsync
public class AsyncConfig {}
