package com.paymentsystem.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CloudPostgresEnvironmentPostProcessor Unit Tests")
class CloudPostgresEnvironmentPostProcessorTest {

    private final CloudPostgresEnvironmentPostProcessor processor = new CloudPostgresEnvironmentPostProcessor();

    @Test
    @DisplayName("Normalizes Neon postgres:// URI with credentials and sslmode")
    void testNormalizeNeonPostgresUrl() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "DATABASE_URL", "postgres://neon_user:secret_pass123@ep-cool-fog-1234.us-east-2.aws.neon.tech:5432/neondb?sslmode=require"
        )));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("jdbc:postgresql://ep-cool-fog-1234.us-east-2.aws.neon.tech:5432/neondb?sslmode=require", env.getProperty("spring.datasource.url"));
        assertEquals("neon_user", env.getProperty("spring.datasource.username"));
        assertEquals("secret_pass123", env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("Normalizes Supabase postgresql:// URI on pooler port 6543")
    void testNormalizeSupabaseUrl() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "DATABASE_URL", "postgresql://postgres.myproject:P@ssw0rd!@aws-0-us-west-1.pooler.supabase.com:6543/postgres"
        )));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("jdbc:postgresql://aws-0-us-west-1.pooler.supabase.com:6543/postgres", env.getProperty("spring.datasource.url"));
        assertEquals("postgres.myproject", env.getProperty("spring.datasource.username"));
        assertEquals("P@ssw0rd!", env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("Normalizes Render Postgres internal URL without port")
    void testNormalizeRenderPostgresUrl() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "SPRING_DATASOURCE_URL", "postgres://render_usr:render_pass@dpg-abc1234-a.oregon-postgres.render.com/apexpay_db"
        )));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("jdbc:postgresql://dpg-abc1234-a.oregon-postgres.render.com:5432/apexpay_db", env.getProperty("spring.datasource.url"));
        assertEquals("render_usr", env.getProperty("spring.datasource.username"));
        assertEquals("render_pass", env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("Ignores standard jdbc: URLs")
    void testIgnoreJdbcUrl() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "DATABASE_URL", "jdbc:postgresql://localhost:5432/payment_db"
        )));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertNull(env.getProperty("spring.datasource.username"));
    }

    @Test
    @DisplayName("Handles missing or empty DATABASE_URL safely")
    void testMissingDatabaseUrl() {
        StandardEnvironment env = new StandardEnvironment();
        assertDoesNotThrow(() -> processor.postProcessEnvironment(env, new SpringApplication()));
    }
}
