package eone.ticket.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configurazione centralizzata dell'applicazione, letta da un file di
 * properties esterno al WAR (sopravvive a redeploy/aggiornamenti).
 *
 * Percorso del file: definito dalla system property "ticket.config.path",
 * altrimenti default "/opt/eone/ticket/config.properties" (Linux) o
 * "C:\eone\ticket\config.properties" (Windows) — il primo che esiste viene usato.
 *
 * Se il file non esiste o una chiave non è presente, fallback alle
 * variabili d'ambiente con lo stesso nome (retrocompatibilità con
 * DBConfig/MailService precedenti basati su System.getenv()).
 *
 * Esempio config.properties:
 *   TICKET_DB_URL=jdbc:postgresql://localhost:5432/ticketdb
 *   TICKET_DB_USER=ticket_app
 *   TICKET_DB_PASS=********
 *   MAIL_HOST=smtp.office365.com
 *   MAIL_PORT=587
 *   MAIL_USER=noreply@eone.it
 *   MAIL_PASS=********
 *   MAIL_FROM=noreply@eone.it
 *   MAIL_DRY_RUN=true
 *   APP_BASE_URL=https://ticket.lamplast.it:50000/ticket
 */
public class AppConfig {

    private static Properties props;
    private static String loadedFromPath;

    private static synchronized void ensureLoaded() {
        if (props != null) return;
        props = new Properties();

        for (String candidate : candidatePaths()) {
            Path p = Paths.get(candidate);
            if (Files.exists(p) && Files.isReadable(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    props.load(in);
                    loadedFromPath = candidate;
                    System.out.println("[AppConfig] Configurazione caricata da: " + candidate);
                    return;
                } catch (IOException e) {
                    System.err.println("[AppConfig] Errore lettura " + candidate + ": " + e.getMessage());
                }
            }
        }

        System.err.println("[AppConfig] Nessun file config.properties trovato nei percorsi attesi: "
            + candidatePaths() + " — uso solo variabili d'ambiente come fallback.");
    }

    private static java.util.List<String> candidatePaths() {
        java.util.List<String> list = new java.util.ArrayList<>();
        String override = System.getProperty("ticket.config.path");
        if (override != null && !override.trim().isEmpty()) list.add(override.trim());
        list.add("/opt/eone/ticket/config.properties");
        list.add("C:\\eone\\ticket\\config.properties");
        return list;
    }

    /**
     * Legge una chiave: prima dal file di properties esterno, poi dalle
     * variabili d'ambiente con lo stesso nome, infine dal default fornito.
     */
    public static String get(String key, String defaultValue) {
        ensureLoaded();
        String fromFile = props.getProperty(key);
        if (fromFile != null && !fromFile.trim().isEmpty()) return fromFile.trim();

        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.trim().isEmpty()) return fromEnv.trim();

        return defaultValue;
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String v = get(key, null);
        if (v == null) return defaultValue;
        return "true".equalsIgnoreCase(v.trim());
    }

    /** Per diagnostica: da dove è stata effettivamente caricata la configurazione */
    public static String getLoadedFromPath() {
        ensureLoaded();
        return loadedFromPath;
    }

    /** Forza il ricaricamento (es. dopo modifica manuale del file, senza riavviare Tomcat) */
    public static synchronized void reload() {
        props = null;
        ensureLoaded();
    }
}