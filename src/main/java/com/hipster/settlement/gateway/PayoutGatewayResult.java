package com.hipster.settlement.gateway;

public record PayoutGatewayResult(
        String providerName,
        String providerReference,
        boolean succeeded,
        boolean timeout,
        String errorCode
) {
    public static PayoutGatewayResult success(final String providerName, final String providerReference) {
        return new PayoutGatewayResult(providerName, providerReference, true, false, null);
    }

    public static PayoutGatewayResult timeout(final String providerName, final String providerReference) {
        return new PayoutGatewayResult(providerName, providerReference, false, true, "TIMEOUT");
    }

    public static PayoutGatewayResult failure(final String providerName,
                                              final String providerReference,
                                              final String errorCode) {
        return new PayoutGatewayResult(providerName, providerReference, false, false, errorCode);
    }
}
