package com.hipster.global.config;

import com.hipster.global.logging.context.MdcTaskDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Slf4j
@EnableAsync
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("AsyncThread-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    @Bean(name = "ratingSummaryExecutor")
    public Executor ratingSummaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(10000);
        executor.setThreadNamePrefix("RatingSummary-");
        executor.setTaskDecorator(new MdcTaskDecorator()); // MDC 로깅 컨텍스트 상속
        executor.initialize();
        log.info("Initialized ratingSummaryExecutor ThreadPoolTaskExecutor.");
        return executor;
    }
}
