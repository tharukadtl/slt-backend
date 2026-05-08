package lk.slt.fieldops.config;

import com.fasterxml.jackson.databind
        .ObjectMapper;
import com.fasterxml.jackson.databind
        .SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310
        .JavaTimeModule;
import org.springframework.context.annotation
        .Bean;
import org.springframework.context.annotation
        .Configuration;
import org.springframework.context.annotation
        .Primary;

@Configuration
public class AppConfig {

    /**
     * Global ObjectMapper configured with:
     * - JavaTimeModule for LocalDateTime
     * - Disabled WRITE_DATES_AS_TIMESTAMPS
     *   so dates are ISO strings not arrays
     *
     * This is used by:
     * - Spring MVC for REST responses
     * - WebSocket message serialization
     * - Report service JSON processing
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Register Java 8 date/time module
        mapper.registerModule(
                new JavaTimeModule());

        // Write dates as ISO strings
        // not as numeric arrays
        mapper.disable(
                SerializationFeature
                        .WRITE_DATES_AS_TIMESTAMPS);

        return mapper;
    }
}