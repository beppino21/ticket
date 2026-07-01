package eone.ticket.config;

/**
 * Classe di configurazione per il servizio oData SAP
 */
public class SAPODataConfig {
    
    // URL base del servizio
	private static final String BASE_URL = "http://newton.domain.eonegroup.it:8001";
	//private static final String BASE_URL = "https://newton.domain.eonegroup.it:5201";
    
    //====================================================================================//
    // Se BTP si deve usare il nome della "Destination" configurata sul BTP!
	//private static final String BASE_URL = "Ticket" + "/sap/opu/odata/tkm/cc_web_srv";
    // Poi c'è tutto il problema della sicurezza http/https e quali attività sono consentite
    // di default dal BTP, chi lo sa, chi lo dovrebbe sapere, chi lo potrebbe configurare?
    //====================================================================================//    
    
    // Endpoints
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

    public static final String SAP_CLIENT = "300";
    // Credenziali Basic Auth per il servizio SAP
    // NOTA: In produzione, gestire queste credenziali in modo sicuro
    private static final String BASIC_AUTH_USER = "EONE";
    private static final String BASIC_AUTH_PASSWORD = "thebest";    
    
//    public static final String SAP_CLIENT = "100"; 
//    // Credenziali Basic Auth per il servizio SAP
//    // NOTA: In produzione, gestire queste credenziali in modo sicuro
//    private static final String BASIC_AUTH_USER = "GLINI";
//    private static final String BASIC_AUTH_PASSWORD = "dicembre2012";    
    
    /**
     * Genera l'header Authorization per Basic Auth
     * @return String con il valore dell'header Authorization
     */
    public static String getBasicAuthHeader() {
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