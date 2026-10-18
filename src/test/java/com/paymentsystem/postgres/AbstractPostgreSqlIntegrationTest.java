package com.paymentsystem.postgres;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Reusable Base Infrastructure for PostgreSQL 16 Integration Testing with Testcontainers.
 *
 * Implements the Singleton Container pattern:
 * - A single genuine PostgreSQL 16 container is started once per test JVM execution.
 * - Stays running across all test suites to prevent port shifts, container recreation overhead,
 *   and Spring ApplicationContext connection cache invalidations.
 * - Container resource cleanup on JVM termination is automatically handled by Testcontainers Ryuk.
 */
@SpringBootTest
@ActiveProfiles({"dev", "postgres-test"})
public abstract class AbstractPostgreSqlIntegrationTest {

    public static final PostgreSQLContainer<?> postgreSqlContainer;

    static {
        postgreSqlContainer = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("apexpay_test_db")
                .withUsername("test_user")
                .withPassword("test_password");
        postgreSqlContainer.start();
    }

    @DynamicPropertySource
    static void configurePostgreSqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSqlContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgreSqlContainer::getUsername);
        registry.add("spring.datasource.password", postgreSqlContainer::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
