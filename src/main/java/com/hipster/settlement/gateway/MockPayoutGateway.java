package com.hipster.settlement.gateway;

import com.hipster.settlement.domain.SettlementRequest;
import org.springframework.stereotype.Component;

@Component
public class MockPayoutGateway implements PayoutGateway {

    private static final String PROVIDER_NAME = "mock-payout";
    private static final String FAILURE_TOKEN = "FAIL";
    private static final String UNKNOWN_TOKEN = "UNKNOWN";

    @Override
    public PayoutGatewayResult execute(final SettlementRequest settlementRequest) {
        return PayoutGatewayResult.timeout(PROVIDER_NAME, settlementRequest.getRequestNo());
    }

    @Override
    public PayoutGatewayResult lookup(final SettlementRequest settlementRequest) {
        final String providerReference = settlementRequest.getProviderReference();
        if (providerReference == null || providerReference.isBlank()) {
            return PayoutGatewayResult.timeout(PROVIDER_NAME, null);
        }
        if (providerReference.contains(FAILURE_TOKEN)) {
            return PayoutGatewayResult.failure(PROVIDER_NAME, providerReference, "MOCK_PROVIDER_FAILURE");
        }
        if (providerReference.contains(UNKNOWN_TOKEN)) {
            return PayoutGatewayResult.timeout(PROVIDER_NAME, providerReference);
        }
        return PayoutGatewayResult.success(PROVIDER_NAME, providerReference);
    }
}
