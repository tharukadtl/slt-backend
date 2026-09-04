package lk.slt.fieldops.config;

import lk.slt.fieldops.websocket.LocationWebSocketHandler;
import lk.slt.fieldops.websocket.NotificationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final LocationWebSocketHandler locationWebSocketHandler;
    private final NotificationWebSocketHandler notificationWebSocketHandler;
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    // QA_Compliance_Consolidated_Report.md Stage G Minor — setAllowedOrigins("*") accepted a
    // WebSocket handshake from ANY origin, unlike the real HTTP API which is already restricted
    // to this exact list (see SecurityConfig.corsConfigurationSource()). Mirrored here verbatim
    // rather than shared as a common constant, since these two configs already don't share a
    // common base class and duplicating a short literal is simpler than a cross-cutting refactor.
    private static final String[] ALLOWED_ORIGIN_PATTERNS = {
        "http://localhost:3000", "http://localhost:8081",
        "http://10.0.2.2:*",    "http://192.168.*.*:*",
        "https://*.ngrok-free.app", "https://*.ngrok.io",
        "http://*.ngrok-free.app",  "http://*.ngrok.io"
    };

    @Override
    public void registerWebSocketHandlers(
            WebSocketHandlerRegistry registry) {

        // Location tracking endpoint
        // ws://localhost:8080/ws/location
        registry.addHandler(locationWebSocketHandler, "/ws/location")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);

        // Notifications endpoint
        // ws://localhost:8080/ws/notifications
        registry.addHandler(
                        notificationWebSocketHandler,
                        "/ws/notifications")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);
    }
}
