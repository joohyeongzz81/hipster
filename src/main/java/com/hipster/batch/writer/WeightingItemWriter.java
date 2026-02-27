package com.hipster.batch.writer;

import com.hipster.batch.calculator.WeightingCalculator;
import com.hipster.batch.dto.UserWeightingStatsDto;
import com.hipster.batch.repository.WeightingStatsQueryRepository;

import com.hipster.user.domain.User;
import com.hipster.user.domain.UserWeightStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 청크 단위로 User 목록을 받아 Rating Bulk UPDATE 수행.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeightingItemWriter implements ItemWriter<User> {


    private final WeightingStatsQueryRepository queryRepository;
    private final WeightingCalculator calculator = new WeightingCalculator();

    @Override
    public void write(final Chunk<? extends User> chunk) {
        final List<? extends User> users = chunk.getItems();
        if (users.isEmpty()) return;

        // 1. 유저 ID 목록 추출
        List<Long> userIds = users.stream().map(User::getId).collect(Collectors.toList());

        // 2. 벌크 조회 (N번의 SELECT를 1번의 IN 쿼리로 최적화)
        Map<Long, UserWeightingStatsDto> statsMap = queryRepository.findStatsByUserIds(userIds);

        // 3. 인메모리 가중치 산출 및 DTO 생성
        List<UserWeightStats> weightStatsList = new ArrayList<>();
        
        for (final User user : users) {
            UserWeightingStatsDto stats = statsMap.get(user.getId());
            double newWeight = calculator.calculateWeight(stats, user.getLastActiveDate());
            user.updateWeightingScore(newWeight);
            
            if (stats != null) {
                weightStatsList.add(
                    UserWeightStats.builder()
                        .userId(user.getId())
                        .ratingCount(stats.ratingCount())
                        .ratingVariance(stats.ratingVariance())
                        .reviewCount(stats.reviewCount())
                        .reviewAvgLength(stats.reviewAvgLength())
                        .lastActiveDate(stats.getLastActiveDate(user.getLastActiveDate()))
                        .build()
                );
            }
        }

        // 4. Summary Table 벌크 Upsert (JdbcTemplate Batch Update)
        queryRepository.bulkUpsertUserWeightStats(weightStatsList);



        logHeapUsage("청크 완료 (" + users.size() + "명)");
    }

    private void logHeapUsage(final String label) {
        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        final long usedMB = heap.getUsed() / 1024 / 1024;
        final long maxMB = heap.getMax() / 1024 / 1024;
        log.info("[MEMORY] {} → Heap {}MB / 최대 {}MB", label, usedMB, maxMB);
    }
}
