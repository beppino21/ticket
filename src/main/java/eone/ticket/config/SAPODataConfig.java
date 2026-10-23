package eone.ticket.config;

/**
 * Configurazione per il servizio OData SAP.
 *
 * Come DBConfig/MailService, i valori sono letti da AppConfig (file
 * config.properties esterno al WAR, con fallback alle variabili
 * d'ambiente omonime), usando come default gli ultimi valori noti
 * funzionanti — così l'app continua a operare invariata anche se il
 * file di configurazione non è ancora stato aggiornato con le chiavi SAP.
 *
 * Chiavi lette da config.properties:
 *   SAP_BASE_URL   - es. http://newton.domain.eonegroup.it:8001
 *   SAP_CLIENT     - mandante SAP, es. 300
 *   SAP_USER       - utente Basic Auth   (OBBLIGATORIO, nessun default)
 *   SAP_PASS       - password Basic Auth (OBBLIGATORIO, nessun default)
 *
 * NOTA migrazione BTP: se in futuro si passa a SAP BTP, SAP_BASE_URL
 * andrà valorizzato con il nome della Destination configurata sul BTP
 * (es. "Ticket/sap/opu/odata/tkm/cc_web_srv"), da coordinare con la
 * gestione http/https e i permessi consentiti di default dal BTP.
 */
public class SAPODataConfig {

    // URL base del servizio — default = ultimo valore noto funzionante
    private static final String BASE_URL =
        AppConfig.get("SAP_BASE_URL", "http://newton.domain.eonegroup.it:8001");

    // Endpoints (calcolati da BASE_URL al caricamento della classe)
    public static final String LOGON_ENDPOINT = BASE_URL + "/sap/opu/odata/tkm/cc_web_srv/CCWebLogonRequestSet";
    public static final String LIST_OF_TICKETS_ENDPOINT = BASE_URL + "/sap/opu/odata/tkm/cc_web_srv/CCListOfTicketsSet";

    // Headers
    public static final String HEADER_CSRF_TOKEN = "x-csrf-token";
    public static final String HEADER_CSRF_FETCH = "fetch";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_SAP_CLIENT = "sap-client";
    public static final String HEADER_ACCEPT = "Accept";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String CONTENT_TYPE_JSON = "application/json";

    // Mandante SAP — default = mandante attuale.
    // Pubblico: è già referenziato direttamente (SAPODataConfig.SAP_CLIENT)
    // da altre classi (es. SAPLogonService), non solo tramite getSapClient().
    public static final String SAP_CLIENT = AppConfig.get("SAP_CLIENT", "300");

    // Credenziali Basic Auth — SOLO da config.properties (o variabili
    // d'ambiente). Nessun valore di default nel codice: una credenziale
    // scritta nel sorgente finisce nel WAR e nel repository Git.
    private static final String BASIC_AUTH_USER     = AppConfig.get("SAP_USER", null);
    private static final String BASIC_AUTH_PASSWORD = AppConfig.get("SAP_PASS", null);

    static {
        if (BASIC_AUTH_USER == null || BASIC_AUTH_PASSWORD == null) {
            System.err.println("[SAPODataConfig] ❌ SAP_USER e/o SAP_PASS non configurati in config.properties "
                + "(" + AppConfig.getLoadedFromPath() + "): le chiamate OData a SAP falliranno.");
        }
    }

    /**
     * Genera l'header Authorization per Basic Auth
     * @return String con il valore dell'header Authorization
     * @throws IllegalStateException se le credenziali non sono configurate
     */
    public static String getBasicAuthHeader() {
        if (BASIC_AUTH_USER == null || BASIC_AUTH_PASSWORD == null) {
            throw new IllegalStateException("Credenziali SAP non configurate: impostare SAP_USER e SAP_PASS in config.properties");
        }
        String credentials = BASIC_AUTH_USER + ":" + BASIC_AUTH_PASSWORD;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    /**
     * Restituisce l'URL Base
     */
    public static String getBaseUrl() {
        return BASE_URL;
    }

    /**
     * Restituisce l'URL del logon endpoint
     */
    public static String getLogonEndpoint() {
        return LOGON_ENDPOINT;
    }

    /**
     * Restituisce l'URL completo per l'endpoint dei ticket
     * @return URL endpoint tickets
     */
    public static String getTicketsEndpoint() {
        return LIST_OF_TICKETS_ENDPOINT;
    }

    /**
     * Restituisce il numero di mandante SAP.
     * Aggiunto per compatibilità con SAPTicketService.
     */
    public static String getSapClient() {
        return SAP_CLIENT;
    }
}