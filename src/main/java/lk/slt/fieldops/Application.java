package lk.slt.fieldops;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
@EnableScheduling
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	public WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				registry.addMapping("/**")
						.allowedOrigins("http://localhost:3000")
						.allowedMethods("GET","POST","PUT","DELETE")
						.allowedHeaders("*")
						.allowCredentials(true);
			}

			// No addResourceHandlers() override here anymore — /uploads/** used to be served
			// unauthenticated straight off disk via a static ResourceHandler (QA_Compliance
			// Consolidated_Report.md, Stage G Minor: "/uploads/** served with no authentication").
			// It's now a real, authorized controller method instead — see
			// UploadServingController — so every request goes through SecurityConfig's
			// jwtAuthFilter and per-file ownership checks like any other endpoint.
		};
	}
}
