package com.hipster.global.config;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

/**
 * DataSource 라우팅 검증용 헬퍼.
 * 트랜잭션 readOnly 여부에 따라 어느 DB에 접속되는지 hostname을 반환한다.
 */
@Component
class DataSourceRoutingVerifier {

    private final DataSource dataSource;

    DataSourceRoutingVerifier(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Transactional(readOnly = true)
    public String hostnameInReadOnly() throws Exception {
        return queryHostname();
    }

    @Transactional
    public String hostnameInWrite() throws Exception {
        return queryHostname();
    }

    private String queryHostname() throws Exception {
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.createStatement().executeQuery("SELECT @@hostname")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
