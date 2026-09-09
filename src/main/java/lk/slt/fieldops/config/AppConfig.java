package lk.slt.fieldops.config;

import com.fasterxml.jackson.databind
        .ObjectMapper;
import org.springframework.context.annotation
        .Bean;
import org.springframework.context.annotation
        .Configuration;
import org.springframework.context.annotation
        .Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    /** Used to call the Flask AI module (predictions/clusters) for REP-06/REP-07 report export. */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        return new RestTemplate(factory);
    }

    /**
     * Global ObjectMapper, built from Spring Boot's own auto-configured
     * {@link Jackson2ObjectMapperBuilder} rather than a bare {@code new ObjectMapper()} —
     * KPI-003: the previous hand-built mapper ignored every {@code spring.jackson.*}
     * property in application.yml (deserialization.fail-on-unknown-properties=false,
     * serialization.write-dates-as-timestamps=false, time-zone), so an unrecognised field in
     * an otherwise-valid request body (e.g. the Admin portal's {@code isGroupTarget} JSON key
     * against Lombok's {@code groupTarget} property) was rejected with 400 before ever
     * reaching the service, despite the setting meant to allow exactly that. The builder
     * already applies all of those properties and auto-registers JavaTimeModule (and any
     * other module on the classpath) itself.
     *
     * This is used by:
     * - Spring MVC for REST responses
     * - WebSocket message serialization
     * - Report service JSON processing
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.build();
    }
}