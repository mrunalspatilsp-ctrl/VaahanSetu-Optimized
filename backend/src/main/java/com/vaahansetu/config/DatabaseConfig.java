package com.vaahansetu.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.net.URI;

@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Value("${spring.datasource.url:${DATABASE_URL:${DB_URL:jdbc:postgresql://localhost:5432/vaahansetu}}}")
    private String rawUrl;

    @Value("${spring.datasource.username:${DATABASE_USERNAME:${DB_USER:postgres}}}")
    private String defaultUsername;

    @Value("${spring.datasource.password:${DATABASE_PASSWORD:${DB_PASSWORD:postgres}}}")
    private String defaultPassword;

    @Bean
    @Primary
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();

        String jdbcUrl = rawUrl;
        String username = defaultUsername;
        String password = defaultPassword;

        // Parse postgres:// or postgresql:// URIs commonly supplied by Render / Neon / Heroku / Supabase
        if (rawUrl != null && (rawUrl.startsWith("postgres://") || rawUrl.startsWith("postgresql://"))) {
            try {
                URI uri = new URI(rawUrl);
                String userInfo = uri.getUserInfo();
                if (userInfo != null && !userInfo.isEmpty()) {
                    String[] userParts = userInfo.split(":", 2);
                    username = userParts[0];
                    if (userParts.length > 1) {
                        password = userParts[1];
                    }
                }

                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                String path = uri.getPath(); // leading slash included
                String query = uri.getQuery();

                StringBuilder sb = new StringBuilder("jdbc:postgresql://")
                        .append(host)
                        .append(":")
                        .append(port)
                        .append(path != null ? path : "/vaahansetu");

                if (query != null && !query.isEmpty()) {
                    sb.append("?").append(query);
                } else if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) {
                    sb.append("?sslmode=require");
                }

                jdbcUrl = sb.toString();
                log.info("Parsed cloud database URI into JDBC format for host: {}", host);
            } catch (Exception e) {
                log.warn("Failed to parse database URI as standard URI; falling back to prefix replacement: {}", e.getMessage());
                if (rawUrl.startsWith("postgres://")) {
                    jdbcUrl = "jdbc:postgresql://" + rawUrl.substring("postgres://".length());
                } else if (rawUrl.startsWith("postgresql://")) {
                    jdbcUrl = "jdbc:postgresql://" + rawUrl.substring("postgresql://".length());
                }
            }
        }

        // Ensure remote cloud instances have sslmode=require if not specified
        if (jdbcUrl != null && !jdbcUrl.contains("localhost") && !jdbcUrl.contains("127.0.0.1") && !jdbcUrl.contains("sslmode=")) {
            jdbcUrl += (jdbcUrl.contains("?") ? "&" : "?") + "sslmode=require";
        }

        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");

        // Cloud-resilient HikariCP connection pool settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(120000);
        config.setMaxLifetime(300000);
        config.setKeepaliveTime(30000);
        config.setConnectionTestQuery("SELECT 1");
        config.setPoolName("VaahanSetuHikariPool");

        log.info("Initializing HikariDataSource for JDBC URL: {}", maskUrl(jdbcUrl));
        return new HikariDataSource(config);
    }

    private String maskUrl(String url) {
        if (url == null) return "null";
        return url.replaceAll(":[^/@:]+@", ":***@");
    }

    @Bean
    public ApplicationRunner databaseAutoMigrationRunner(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        return args -> {
            try {
                log.info("Checking database schema status...");

                // 1. Check if 'routes' table exists
                Integer tableCount = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'routes'",
                        Integer.class
                );

                if (tableCount == null || tableCount == 0) {
                    log.info("Empty database detected. Running automatic schema and seed bootstrap...");
                    ClassPathResource schemaResource = new ClassPathResource("db/vaahansetu_schema.sql");
                    ClassPathResource seedResource = new ClassPathResource("db/vaahansetu_seed.sql");

                    if (schemaResource.exists() && seedResource.exists()) {
                        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
                        populator.addScript(schemaResource);
                        populator.addScript(seedResource);
                        populator.setContinueOnError(true);
                        populator.execute(dataSource);
                        log.info("Database schema and seed bootstrap completed successfully.");
                    } else {
                        log.warn("Database init scripts not bundled in classpath (db/vaahansetu_schema.sql / seed).");
                    }
                } else {
                    log.info("Existing database schema detected (routes table exists).");
                }

                // 2. Ensure allocation_assignments contains peak_demand and occupancy_percent
                jdbcTemplate.execute("ALTER TABLE allocation_assignments ADD COLUMN IF NOT EXISTS peak_demand INTEGER NULL DEFAULT 0");
                jdbcTemplate.execute("ALTER TABLE allocation_assignments ADD COLUMN IF NOT EXISTS occupancy_percent INTEGER NULL DEFAULT 0");
                log.info("Schema columns peak_demand and occupancy_percent verified.");
            } catch (Exception e) {
                log.warn("Non-fatal database migration check note: {}", e.getMessage());
            }
        };
    }
}
