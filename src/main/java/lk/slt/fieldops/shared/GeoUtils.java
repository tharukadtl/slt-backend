package lk.slt.fieldops.shared;

/**
 * GeoUtils — shared Haversine great-circle distance, extracted 2026-08-20 (H1b) from
 * {@code LocationService.calculateDistance} (which was private and only used for nearest-
 * technician lookups) so the nearest-Exchange lookup doesn't duplicate the same formula a
 * second time. Both callers now share this one implementation.
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * H1b: distance beyond which a nearest-Exchange match is flagged low-confidence — the true
     * nearest Exchange is more likely one of the not-yet-geocoded ones than a genuinely sparse
     * area. Data-derived, not guessed: the p95 nearest-neighbor distance measured among the 271
     * already-geocoded Exchanges themselves (19.73km, rounded), i.e. real typical exchange
     * spacing, not an arbitrary round number. Lives here (not on ExchangeService) so both the
     * service that computes the match and FaultDTO's read-time derivation share one constant
     * without FaultDTO having to depend on the service layer.
     */
    public static final double NEAREST_EXCHANGE_LOW_CONFIDENCE_KM = 20.0;

    private GeoUtils() {}

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
