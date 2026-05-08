package lk.slt.fieldops.config;

import lk.slt.fieldops.websocket.LocationWebSocketHandler;
import lk.slt.fieldops.websocket.NotificationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final LocationWebSocketHandler locationWebSocketHandler;
    private final NotificationWebSocketHandler notificationWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(
            WebSocketHandlerRegistry registry) {

        // Location tracking endpoint
        // ws://localhost:8080/ws/location
        registry.addHandler(locationWebSocketHandler, "/ws/location")
                .addInterceptors(new HttpSessionHandshakeInterceptor())
                .setAllowedOrigins("*");

        // Notifications endpoint
        // ws://localhost:8080/ws/notifications
        registry.addHandler(
                        notificationWebSocketHandler,
                        "/ws/notifications")
                .addInterceptors(new HttpSessionHandshakeInterceptor())
                .setAllowedOrigins("*");
    }
}