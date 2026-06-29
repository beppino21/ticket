package eone.ticket.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Configurazione DataSource PostgreSQL.
 * Il pool viene creato lazy e ricreato automaticamente se shut down.
 */
public class DBConfig {

    private static HikariDataSource dataSource;

    private static synchronized HikariDataSource getDataSource() {
        if (dataSource == null || dataSource.isClosed()) {
            // Forza la registrazione del driver JDBC
            try {
                Class.forName("org.postgresql.Driver");
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Driver PostgreSQL non trovato", e);
            }

            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl (getEnvOrDefault("TICKET_DB_URL",  "jdbc:postgresql://localhost:5432/ticketdb"));
            cfg.setUsername(getEnvOrDefault("TICKET_DB_USER", "ticket_app"));
            cfg.setPassword(getEnvOrDefault("TICKET_DB_PASS", "changeme"));

            cfg.setMaximumPoolSize(10);
            cfg.setMinimumIdle(2);
            cfg.setConnectionTimeout(30_000);
            cfg.setIdleTimeout(600_000);
            cfg.setMaxLifetime(1_800_000);
            cfg.setPoolName("TicketCommentPool");
            cfg.addDataSourceProperty("cachePrepStmts",    "true");
            cfg.addDataSourceProperty("prepStmtCacheSize", "250");

            dataSource = new HikariDataSource(cfg);
            System.out.println("[DBConfig] Pool PostgreSQL inizializzato: " + cfg.getJdbcUrl());
        }
        return dataSource;
    }

    public static Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    private static String getEnvOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }
}