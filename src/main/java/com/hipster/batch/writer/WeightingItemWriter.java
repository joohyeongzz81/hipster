package com.hipster.batch.writer;

import com.hipster.rating.repository.RatingRepository;
import com.hipster.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * 청크 단위로 User 목록을 받아 Rating Bulk UPDATE 수행.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeightingItemWriter implements ItemWriter<User> {

    private final RatingRepository ratingRepository;

    @Override
    public void write(final Chunk<? extends User> chunk) {
        final List<? extends User> users = chunk.getItems();

        for (final User user : users) {
            ratingRepository.bulkUpdateWeightedScoreByUserId(user.getId(), user.getWeightingScore());
        }

        logHeapUsage("청크 완료 (" + users.size() + "명)");
    }

    private void logHeapUsage(final String label) {
        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        final long usedMB = heap.getUsed() / 1024 / 1024;
        final long maxMB = heap.getMax() / 1024 / 1024;
        log.info("[MEMORY] {} → Heap {}MB / 최대 {}MB", label, usedMB, maxMB);
    }
}
