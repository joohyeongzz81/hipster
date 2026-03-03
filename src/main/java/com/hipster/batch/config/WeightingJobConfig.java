package com.hipster.batch.config;

import com.hipster.batch.processor.WeightingItemProcessor;
import com.hipster.batch.reader.WeightingItemReaderConfig;
import com.hipster.batch.writer.WeightingItemWriter;
import com.hipster.user.domain.User;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class WeightingJobConfig {

    private final JobRepository jobRepository;
    private final EntityManagerFactory entityManagerFactory;
    private final WeightingItemReaderConfig readerConfig;
    private final WeightingItemProcessor processor;
    private final WeightingItemWriter writer;

    /**
     * 45일 주기 유저 가중치 재계산 Job.
     * release_rating_summary 재집계는 Anti-Entropy Full 배치(매일 새벽 03:00)에 위임.
     */
    @Bean
    public Job weightingRecalculationJob() {
        return new JobBuilder("weightingRecalculationJob", jobRepository)
                .start(weightingStep())
                .build();
    }

    @Bean
    public Step weightingStep() {
        return new StepBuilder("weightingStep", jobRepository)
                .<User, User>chunk(100, new JpaTransactionManager(entityManagerFactory))
                .reader(readerConfig.weightingItemReader())
                .processor(processor)
                .writer(writer)
                .listener(entityManagerClearListener())
                .build();
    }

    /**
     * 청크 완료 후 EntityManager 1차 캐시를 비워 메모리 누적 방지.
     */
    @Bean
    public ChunkListener entityManagerClearListener() {
        return new ChunkListener() {
            @Override
            public void afterChunk(final ChunkContext context) {
                entityManagerFactory.createEntityManager().clear();
                log.debug("[BATCH] 청크 완료 후 EntityManager 1차 캐시 초기화");
            }
        };
    }
}
