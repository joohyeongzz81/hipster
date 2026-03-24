package com.hipster.settlement.gateway;

import com.hipster.settlement.domain.SettlementRequest;

public interface PayoutGateway {

    PayoutGatewayResult execute(SettlementRequest settlementRequest);

    PayoutGatewayResult lookup(SettlementRequest settlementRequest);
}
