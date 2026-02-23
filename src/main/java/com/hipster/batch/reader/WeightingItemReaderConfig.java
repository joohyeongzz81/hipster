package com.hipster.batch.reader;

import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class WeightingItemReaderConfig {

    private final UserRepository userRepository;

    @Bean
    public RepositoryItemReader<User> weightingItemReader() {
        return new RepositoryItemReaderBuilder<User>()
                .name("weightingItemReader")
                .repository(userRepository)
                .methodName("findAll")
                .pageSize(100)
                .sorts(Map.of("id", Sort.Direction.ASC))
                .build();
    }
}
