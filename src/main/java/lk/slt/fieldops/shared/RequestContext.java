package lk.slt.fieldops.shared;

/**
 * Request-scoped holder for the caller's client IP, populated once per request by the
 * JWT auth filter and read by service-layer code that writes audit trail entries —
 * avoids threading an `ipAddress` parameter through every controller → service call.
 */
public final class RequestContext {

    private static final ThreadLocal<String> CLIENT_IP = new ThreadLocal<>();

    private RequestContext() {}

    public static void setClientIp(String ip) {
        CLIENT_IP.set(ip);
    }

    public static String getClientIp() {
        return CLIENT_IP.get();
    }

    public static void clear() {
        CLIENT_IP.remove();
    }
}
