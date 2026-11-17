package com.paymentsystem.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Automatically normalizes cloud PostgreSQL connection strings (from Neon, Supabase, Render, Heroku)
 * provided via DATABASE_URL or SPRING_DATASOURCE_URL in postgres:// or postgresql:// URI format
 * into standard Spring Boot JDBC properties (spring.datasource.url, username, password).
 * Handles passwords containing special characters (e.g. '@', '!', '#').
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CloudPostgresEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(CloudPostgresEnvironmentPostProcessor.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String dbUrl = environment.getProperty("DATABASE_URL");
        if (dbUrl == null || dbUrl.isBlank()) {
            dbUrl = environment.getProperty("SPRING_DATASOURCE_URL");
        }

        if (dbUrl == null || dbUrl.isBlank()) {
            return;
        }

        dbUrl = dbUrl.trim();

        // If it's already a standard JDBC URL, leave it untouched
        if (dbUrl.startsWith("jdbc:")) {
            return;
        }

        if (dbUrl.startsWith("postgres://") || dbUrl.startsWith("postgresql://")) {
            try {
                int protoEnd = dbUrl.indexOf("://");
                String remainder = dbUrl.substring(protoEnd + 3);

                // Split path and query from authority
                int slashIdx = remainder.indexOf('/');
                String authority = slashIdx != -1 ? remainder.substring(0, slashIdx) : remainder;
                String pathAndQuery = slashIdx != -1 ? remainder.substring(slashIdx) : "/";

                String username = null;
                String password = null;
                String hostPort = authority;

                // UserInfo is separated from hostPort by the LAST '@'
                int lastAt = authority.lastIndexOf('@');
                if (lastAt != -1) {
                    String userInfo = authority.substring(0, lastAt);
                    hostPort = authority.substring(lastAt + 1);

                    int colon = userInfo.indexOf(':');
                    if (colon != -1) {
                        username = userInfo.substring(0, colon);
                        password = userInfo.substring(colon + 1);
                    } else {
                        username = userInfo;
                    }
                }

                String host;
                int port = 5432;
                int colonIdx = hostPort.lastIndexOf(':');
                if (colonIdx != -1 && colonIdx < hostPort.length() - 1) {
                    host = hostPort.substring(0, colonIdx);
                    try {
                        port = Integer.parseInt(hostPort.substring(colonIdx + 1));
                    } catch (NumberFormatException ignored) {
                        host = hostPort;
                    }
                } else {
                    host = hostPort;
                }

                String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + pathAndQuery;

                Map<String, Object> props = new HashMap<>();
                props.put("spring.datasource.url", jdbcUrl);

                if (username != null && !username.isBlank()) {
                    props.put("spring.datasource.username", username);
                }
                if (password != null) {
                    props.put("spring.datasource.password", password);
                }

                // Register high-priority property source
                environment.getPropertySources().addFirst(new MapPropertySource("cloudPostgresProperties", props));
                logger.info("[CLOUD-POSTGRES] Configured JDBC datasource from DATABASE_URL for host: {}:{}", host, port);
            } catch (Exception ex) {
                logger.warn("[CLOUD-POSTGRES] Failed to parse DATABASE_URL, using as-is: {}", ex.getMessage());
            }
        }
    }
}
