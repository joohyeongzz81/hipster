package com.hipster.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataSource 라우팅 통합 테스트.
 *
 * 전제: Docker의 mysql-master(3306), mysql-slave(3307)가 실행 중이어야 합니다.
 * 실행: ./gradlew test --tests com.hipster.global.config.DataSourceRoutingTest
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
@ActiveProfiles("local")
class DataSourceRoutingTest {

    @Autowired
    private DataSourceRoutingVerifier verifier;

    @Test
    @DisplayName("readOnly 트랜잭션은 Slave(hipster-mysql-slave)로 라우팅된다")
    void readOnly_transaction_routes_to_slave() throws Exception {
        String hostname = verifier.hostnameInReadOnly();

        System.out.println("[ReadOnly] Connected to: " + hostname);

        assertThat(hostname).contains("slave");
    }

    @Test
    @DisplayName("write 트랜잭션은 Master(hipster-mysql-master)로 라우팅된다")
    void write_transaction_routes_to_master() throws Exception {
        String hostname = verifier.hostnameInWrite();

        System.out.println("[Write] Connected to: " + hostname);

        assertThat(hostname).contains("master");
    }
}
