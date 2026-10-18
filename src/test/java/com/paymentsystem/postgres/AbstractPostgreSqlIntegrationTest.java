package com.paymentsystem.postgres;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reusable Base Infrastructure for PostgreSQL 16 Integration Testing with Testcontainers.
 *
 * Features:
 * - Managed Singleton Container lifecycle: starts a genuine PostgreSQL 16 container once per test JVM run.
 * - Reused across all PostgreSQL test suites to eliminate container spin-up overhead.
 * - Dynamic Spring DataSource binding using @ServiceConnection and @DynamicPropertySource.
 * - Isolated test configuration via "postgres-test" profile (hikari pool size: 50, PostgreSQL dialect).
 * - Automatic cleanup on test suite completion handled by Testcontainers.
 */
@SpringBootTest
@ActiveProfiles({"dev", "postgres-test"})
@Testcontainers
public abstract class AbstractPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    public static final PostgreSQLContainer<?> postgreSqlContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("apexpay_test_db")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void configurePostgreSqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSqlContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgreSqlContainer::getUsername);
        registry.add("spring.datasource.password", postgreSqlContainer::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
}
