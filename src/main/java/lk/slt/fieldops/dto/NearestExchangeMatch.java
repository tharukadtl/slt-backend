package lk.slt.fieldops.dto;

/**
 * NearestExchangeMatch — result of {@code ExchangeService.findNearestGeocoded}. Not a full
 * DTO returned from an endpoint; a small value object so the distance computed while finding
 * the match doesn't need to be recomputed by the caller.
 */
public class NearestExchangeMatch {
    private final Long exchangeId;
    private final double distanceKm;

    public NearestExchangeMatch(Long exchangeId, double distanceKm) {
        this.exchangeId = exchangeId;
        this.distanceKm = distanceKm;
    }

    public Long getExchangeId() { return exchangeId; }
    public double getDistanceKm() { return distanceKm; }
}
