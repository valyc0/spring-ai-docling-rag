package elastic.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class DoclingClientConfig {

    /**
     * RestTemplate per le chiamate multipart al microservizio Docling.
     */
    @Bean
    public RestTemplate doclingRestTemplate() {
        return new RestTemplate();
    }
}
