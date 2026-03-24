package com.hipster.settlement.domain;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementConstraintJpaTest {

    @Test
    @DisplayName("정산 요청 엔티티는 사용자당 열린 요청 1건 제약을 매핑한다")
    void settlementRequestDeclaresOpenRequestUniqueConstraint() {
        final Table table = SettlementRequest.class.getAnnotation(Table.class);
        final Map<String, UniqueConstraint> constraints = Arrays.stream(table.uniqueConstraints())
                .collect(Collectors.toMap(UniqueConstraint::name, Function.identity()));

        assertThat(constraints).containsKey("uk_settlement_request_open_user");
        assertThat(constraints.get("uk_settlement_request_open_user").columnNames())
                .containsExactly("open_request_user_id");
    }

    @Test
    @DisplayName("정산 할당 엔티티는 active allocation 중복 금지 제약을 매핑한다")
    void settlementAllocationDeclaresActiveAllocationUniqueConstraint() {
        final Table table = SettlementAllocation.class.getAnnotation(Table.class);
        final Map<String, UniqueConstraint> constraints = Arrays.stream(table.uniqueConstraints())
                .collect(Collectors.toMap(UniqueConstraint::name, Function.identity()));

        assertThat(constraints).containsKey("uk_settlement_allocation_active_reward_entry");
        assertThat(constraints.get("uk_settlement_allocation_active_reward_entry").columnNames())
                .containsExactly("active_reward_ledger_entry_id");
    }
}
