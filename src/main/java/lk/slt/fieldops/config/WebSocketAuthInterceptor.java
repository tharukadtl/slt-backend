package lk.slt.fieldops.config;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

/**
 * QA_Compliance_Consolidated_Report.md Stage G Major — the notification/location WebSocket
 * handshake had no real way for a browser client to authenticate. A native browser
 * {@code WebSocket} can't attach a custom {@code Authorization} header, and the JWT lives in
 * {@code localStorage}, not a cookie, so there was no path from "the app has a valid token" to
 * "the server can see it during the handshake" — confirmed by a real Chrome DevTools Protocol
 * test earlier this session, which reproduced the handshake dying every time.
 *
 * <p><b>Design, investigated before implementing.</b> The standard fix for this exact browser
 * limitation is {@code new WebSocket(url, [token])} — the one piece of custom data a browser's
 * native WebSocket API can send at connect time, carried as the {@code Sec-WebSocket-Protocol}
 * request header. Chosen over a {@code ?token=} query parameter specifically because query
 * strings land in browser history and most reverse-proxy/access-log formats, while request
 * headers generally do not.</p>
 *
 * <p><b>Where validation happens.</b> {@code SecurityConfig}'s {@code jwtAuthFilter} already
 * validates the {@code Authorization} header (and, for {@code /uploads/**}, a {@code ?token=}
 * fallback) before this interceptor ever runs — it now also checks {@code Sec-WebSocket-Protocol}
 * as a third source, so by the time a request reaches here, {@code SecurityContextHolder} already
 * holds a real {@code Authentication} if any of the three carried a valid token. This interceptor
 * does not re-parse the JWT — it only (a) rejects with 401 if no Authentication is present
 * (defense-in-depth at the WebSocket-specific layer too, matching this codebase's established
 * two-layer pattern), and (b) echoes the exact requested subprotocol back on the handshake
 * response.</p>
 *
 * <p><b>The one correctness trap this exists to avoid.</b> Per the WebSocket spec, if the client
 * sends {@code Sec-WebSocket-Protocol} values and the server's {@code 101} response doesn't echo
 * one back, real browsers abort the connection outright — this is not optional behaviour to skip.
 * {@code Sec-WebSocket-Protocol} is a comma-separated list; the token is sent as the sole entry
 * so there's exactly one value to echo back, not a real protocol negotiation.</p>
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }

        // Echo the requested subprotocol back verbatim — required for the browser to accept
        // the handshake at all when it sent one. jwtAuthFilter validated it as the real token;
        // here it's just the negotiated "protocol" value being confirmed, not re-parsed.
        List<String> requested = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (requested != null && !requested.isEmpty()) {
            response.getHeaders().set("Sec-WebSocket-Protocol", requested.get(0));
        }

        attributes.put("userId", auth.getPrincipal());
        attributes.put("role", auth.getAuthorities().stream().findFirst()
            .map(Object::toString).orElse(null));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // No-op.
    }
}
