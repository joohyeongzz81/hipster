package com.hipster.batch.processor;

import com.hipster.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * 단건 User를 받아 가중치를 계산하고 User에 반영.
 * Batch Fetch(IN절)는 Writer에서 청크 단위로 처리하므로,
 * 여기서는 개별 조회로 처리 (DB 부하 최소화를 원하면 Writer로 이동 가능).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeightingItemProcessor implements ItemProcessor<User, User> {

    @Override
    public User process(final User user) {
        // 단건 DB 병목을 제거하기 위해 로직을 ItemWriter(Bulk) 계층으로 이관함.
        // 현재는 단순히 user를 통과(Pass-through)시킵니다.
        return user;
    }
}
