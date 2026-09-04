package lk.slt.fieldops.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final RateLimitService rateLimitService;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider, RateLimitService rateLimitService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.rateLimitService = rateLimitService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/auth/**",
                    "/swagger-ui/**", "/swagger-ui.html",
                    "/v3/api-docs/**", "/actuator/health"
                ).permitAll()
                // /uploads/** deliberately NOT permitAll — see UploadServingController and the
                // query-param token fallback in jwtAuthFilter below (an <img>/<Image> tag can't
                // attach an Authorization header, so this path authenticates via ?token= instead).
                // Falls through to anyRequest().authenticated() plus that controller's own
                // per-file ownership check for CLIENT callers.
                // Only /actuator/health is public (permitAll above); every other actuator
                // endpoint (env, metrics, etc.) leaks operational/config details and must
                // be admin-only, not just "any authenticated user".
                .requestMatchers("/actuator/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                // §B-6 (QA_Compliance_Consolidated_Report.md) — a blanket "/api/opmcs/**" matcher
                // here ran before method security and blocked TEAM_LEAD on every OPMC endpoint,
                // including GET /api/opmcs and GET /api/opmcs/{id}, which OpmcController's own
                // @PreAuthorize already grants TEAM_LEAD (:100, :127) — dead grants no request could
                // ever reach. Narrowed to only the write verbs, mirroring the exact pattern already
                // used below for exchanges/cabs/dps/circuits: GET now falls through to
                // anyRequest().authenticated() and is governed by each method's own @PreAuthorize,
                // while every write verb (create/update/activate/deactivate/delete) stays gated here
                // at ADMIN-or-above and is narrowed further to SUPER_ADMIN-only at the method level,
                // exactly as the Stage F #1 decision left it — unchanged by this fix.
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/opmcs/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/opmcs/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/api/opmcs/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/opmcs/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/workgroups/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/workgroups/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/api/workgroups/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                    "/api/exchanges/**", "/api/cabs/**", "/api/dps/**", "/api/circuits/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.PUT,
                    "/api/exchanges/**", "/api/cabs/**", "/api/dps/**", "/api/circuits/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.DELETE,
                    "/api/exchanges/**", "/api/cabs/**", "/api/dps/**", "/api/circuits/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(
                    "/api/users/admin/**", "/api/faults/*/assign", "/api/faults/*/reassign",
                    "/api/faults/bulk-assign", "/api/payments/*/approve", "/api/payments/*/reject",
                    "/api/inventory/requests/*/approve"
                ).hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers(
                    "/api/jobs/bod", "/api/jobs/eod",
                    "/api/jobs/*/assign-technician",
                    "/api/faults/*/self-assign", "/api/faults/*/transfer-to-admin",
                    "/api/payments", "/api/inventory/requests"
                ).hasAnyRole("TEAM_LEAD", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/resource-plans/confirm").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/resource-plans/lookup").hasAnyRole("TEAM_LEAD", "ADMIN", "SUPER_ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/resource-allocations")
                    .hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/resource-allocations/**")
                    .hasAnyRole("TEAM_LEAD", "ADMIN", "SUPER_ADMIN")
                .anyRequest().authenticated()
            )
            // Without an explicit entry point Spring Security falls back to
            // Http403ForbiddenEntryPoint, so a missing/invalid/tampered JWT was answered with 403
            // instead of 401. Only UNAUTHENTICATED requests reach the entry point; an authenticated
            // caller denied by role still yields 403 via the default AccessDeniedHandler /
            // GlobalExceptionHandler.
            .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint()))
            .addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"status\":401,\"error\":\"Unauthorized\","
                    + "\"message\":\"Invalid or missing authentication token.\"}");
        };
    }

    @Bean
    public OncePerRequestFilter jwtAuthFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req,
                    HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {

                // Populated for the lifetime of this request so service-layer audit-trail
                // writes (fault history, payment approvals, user changes, stock txns) can
                // record the caller's IP without threading it through every method signature.
                String clientIp = resolveClientIp(req);
                lk.slt.fieldops.shared.RequestContext.setClientIp(clientIp);
                try {
                    String header = req.getHeader("Authorization");
                    Long   userId = null;
                    String role   = null;
                    boolean tokenValid = false;

                    if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
                        String token = header.substring(7);
                        if (jwtTokenProvider.validateToken(token)) {
                            tokenValid = true;
                            userId = jwtTokenProvider.getUserIdFromToken(token);
                            role   = jwtTokenProvider.getRoleFromToken(token);
                        }
                    }

                    // Fallback for requests that can't set a custom header at all — namely
                    // <img>/RN <Image> tags loading /uploads/**, which the browser/RN image
                    // loader fetches itself with no way for app code to attach Authorization.
                    // Only consulted when no (or no valid) Bearer header was present, so this
                    // never weakens a request that already authenticated normally.
                    if (!tokenValid) {
                        String queryToken = req.getParameter("token");
                        if (StringUtils.hasText(queryToken) && jwtTokenProvider.validateToken(queryToken)) {
                            tokenValid = true;
                            userId = jwtTokenProvider.getUserIdFromToken(queryToken);
                            role   = jwtTokenProvider.getRoleFromToken(queryToken);
                        }
                    }

                    // QA_Compliance_Consolidated_Report.md Stage G Major — the same limitation
                    // as above, but for /ws/location and /ws/notifications: a browser's native
                    // WebSocket() API cannot attach an Authorization header either, and the JWT
                    // lives in localStorage, not a cookie, so it was structurally impossible for
                    // a real browser client to authenticate this handshake at all (confirmed via
                    // a live Chrome DevTools Protocol test). The one piece of custom data a
                    // browser CAN send at connect time is the Sec-WebSocket-Protocol list, via
                    // new WebSocket(url, [token]) — checked here as a third fallback, and echoed
                    // back verbatim by WebSocketAuthInterceptor so the browser accepts the
                    // handshake. Chosen over a ?token= query param for this path specifically
                    // because query strings land in access logs; this header generally doesn't.
                    if (!tokenValid) {
                        String subprotocolHeader = req.getHeader("Sec-WebSocket-Protocol");
                        if (StringUtils.hasText(subprotocolHeader)) {
                            String wsToken = subprotocolHeader.split(",")[0].trim();
                            if (jwtTokenProvider.validateToken(wsToken)) {
                                tokenValid = true;
                                userId = jwtTokenProvider.getUserIdFromToken(wsToken);
                                role   = jwtTokenProvider.getRoleFromToken(wsToken);
                            }
                        }
                    }

                    // Rate-limit EVERY request, not just ones carrying a valid Bearer token —
                    // pre-auth endpoints (login, register, OTP) are exactly what brute-force /
                    // credential-stuffing attacks target, so they must be capped too. Authenticated
                    // requests are keyed per-user (NFR 7.1.3); everything else is keyed per-IP.
                    String rateLimitKey = tokenValid ? "user:" + userId : "ip:" + clientIp;
                    if (!rateLimitService.tryAcquire(rateLimitKey)) {
                        res.setStatus(429); // Too Many Requests
                        res.setContentType("application/json");
                        res.getWriter().write(
                            "{\"error\":\"Rate limit exceeded. Maximum 100 requests per minute.\"}");
                        return;
                    }

                    if (tokenValid) {
                        List<SimpleGrantedAuthority> auths =
                            List.of(new SimpleGrantedAuthority("ROLE_" + role));
                        SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(userId, null, auths));
                    }
                    chain.doFilter(req, res);
                } finally {
                    lk.slt.fieldops.shared.RequestContext.clear();
                }
            }

            /** Prefers X-Forwarded-For (set by a reverse proxy/load balancer) over the raw socket address. */
            private String resolveClientIp(HttpServletRequest req) {
                String xff = req.getHeader("X-Forwarded-For");
                if (StringUtils.hasText(xff)) {
                    return xff.split(",")[0].trim();
                }
                return req.getRemoteAddr();
            }
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOriginPatterns(List.of(
            "http://localhost:3000", "http://localhost:8081",
            "http://10.0.2.2:*",    "http://192.168.*.*:*",
            "https://*.ngrok-free.app", "https://*.ngrok.io",
            "http://*.ngrok-free.app",  "http://*.ngrok.io"));
        c.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setAllowCredentials(true);
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }
}
