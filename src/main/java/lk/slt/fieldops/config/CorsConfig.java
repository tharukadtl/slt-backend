package lk.slt.fieldops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(CorsConfig.CorsProperties.class)
public class CorsConfig {

    private final CorsProperties props;

    public CorsConfig(CorsProperties props) {
        this.props = props;
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        props.getAllowedOrigins().forEach(config::addAllowedOrigin);
        props.getAllowedMethods().forEach(config::addAllowedMethod);
        config.addAllowedHeader(props.getAllowedHeaders());
        config.setAllowCredentials(props.isAllowCredentials());
        config.setMaxAge(props.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    @Data
    @ConfigurationProperties(prefix = "app.cors")
    public static class CorsProperties {
        private List<String> allowedOrigins = new ArrayList<>();
        private List<String> allowedMethods = new ArrayList<>();
        private String allowedHeaders = "*";
        private boolean allowCredentials = true;
        private long maxAge = 3600;
    }
}
