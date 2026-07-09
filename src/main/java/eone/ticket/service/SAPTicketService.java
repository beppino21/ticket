package eone.ticket.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.CookieStore;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import eone.ticket.config.SAPODataConfig;
import eone.ticket.model.Ticket;

/**
 * Service per recuperare i ticket tramite servizio OData SAP
 */
public class SAPTicketService {

    private CookieStore cookieStore;
    private CloseableHttpClient httpClient;

    /**
     * Costruttore - inizializza il client HTTP con cookie store
     */
    public SAPTicketService() {
        this.cookieStore = new BasicCookieStore();
        this.httpClient = HttpClients.custom().setDefaultCookieStore(cookieStore).build();
    }

    /**
     * Recupera la lista dei ticket dal servizio SAP OData
     * 
     * @param kunnr Codice cliente (opzionale, se null recupera tutti)
     * @param reqid ID richiedente (opzionale)
     * @param ownAll Flag per indicare se recuperare tutti i ticket del cliente ('ALL' o altro)
     * @param fromDate Data inizio (formato YYYYMMDD, opzionale)
     * @param toDate Data fine (formato YYYYMMDD, opzionale)
     * @return TicketResponse con la lista dei ticket
     * @throws Exception In caso di errore durante la chiamata
     */
    public TicketResponse getTickets(String kunnr, String reqid, String ownAll,
                                     String fromDate, String toDate) throws Exception {
        return getTickets(kunnr, reqid, ownAll, fromDate, toDate, null);
    }

    /**
     * Versione estesa con filtro opzionale per stato ticket (rstat).
     * rstat="CLO"    -> Rstat eq 'CLO'   (solo chiusi, filtro SAP)
     * rstat="ne:CLO" -> escludi CLO lato client (SAP non supporta 'ne' in questo servizio)
     */
    public TicketResponse getTickets(String kunnr, String reqid, String ownAll,
                                     String fromDate, String toDate, String rstatFilter) throws Exception {
        TicketResponse ticketResponse = new TicketResponse();

        System.out.println("================================================================================");
        System.out.println("[TICKETS] Inizio recupero ticket");
        System.out.println("================================================================================");

        try {
            String baseUrl = SAPODataConfig.getTicketsEndpoint();
            List<String> filters = new ArrayList<>();

            if (kunnr != null && !kunnr.isEmpty()) {
                filters.add("Kunnr eq '" + kunnr + "'");
            }
            if (reqid != null && !reqid.isEmpty()) {
                filters.add("Reqid eq '" + reqid + "'");
            }
            if (fromDate != null && !fromDate.isEmpty()) {
                filters.add("Erdat ge '" + fromDate + "'");
            }
            if (toDate != null && !toDate.isEmpty()) {
                filters.add("Erdat le '" + toDate + "'");
            }
            if (rstatFilter != null && !rstatFilter.isEmpty()) {
                if (rstatFilter.startsWith("ne:")) {
                    // 'ne' non supportato da questo SAP Gateway — filtro gestito client-side
                    System.out.println("[TICKETS] Filtro ne:" + rstatFilter.substring(3) +
                                       " applicato client-side (SAP non supporta 'ne')");
                } else {
                    filters.add("Rstat eq '" + rstatFilter + "'");
                }
            }

            StringBuilder queryString = new StringBuilder();
            if (!filters.isEmpty()) {
                String filterExpression = String.join(" and ", filters);
                String encodedFilter = filterExpression.replace(" ", "%20");
                queryString.append("$filter=").append(encodedFilter);
                queryString.append("&$format=json");
            } else {
                queryString.append("$format=json");
            }
            
            // Costruisci URL finale
            String url = baseUrl + "?" + queryString.toString();
            System.out.println("[TICKETS] URL: " + url);

            HttpGet request = new HttpGet(url);

            // Imposta gli headers necessari
            String authHeader = SAPODataConfig.getBasicAuthHeader();
            request.setHeader(SAPODataConfig.HEADER_AUTHORIZATION, authHeader);
            request.setHeader(SAPODataConfig.HEADER_ACCEPT, SAPODataConfig.CONTENT_TYPE_JSON);
            request.setHeader(SAPODataConfig.HEADER_SAP_CLIENT, SAPODataConfig.getSapClient());

            System.out.println("[TICKETS] Headers inviati:");
            for (Header header : request.getAllHeaders()) {
                if (header.getName().equals("Authorization")) {
                    System.out.println("  - Authorization: [REDACTED]");
                } else {
                    System.out.println("  - " + header.getName() + ": " + header.getValue());
                }
            }

            System.out.println("[TICKETS] Esecuzione chiamata HTTP GET...");

            // Esegue la richiesta
            HttpResponse response = httpClient.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

            System.out.println("[TICKETS] ==========================================");
            System.out.println("[TICKETS] RISPOSTA RICEVUTA");
            System.out.println("[TICKETS] ==========================================");
            System.out.println("[TICKETS] Status Code: " + statusCode);
            System.out.println("[TICKETS] Response Body (primi 1000 char): " 
                + responseBody.substring(0, Math.min(1000, responseBody.length())));

            // Elabora la risposta
            ticketResponse.setStatusCode(statusCode);
            ticketResponse.setSuccess(statusCode >= 200 && statusCode < 300);
            ticketResponse.setResponseBody(responseBody);

            if (ticketResponse.isSuccess()) {
                System.out.println("[TICKETS] ✅ Recupero ticket RIUSCITO!");

                // Parsing della risposta JSON OData
                try {
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    ticketResponse.setJsonResponse(jsonResponse);
                    
                    // Estrai la lista dei ticket dall'oggetto "d" -> "results"
                    List<Ticket> tickets = parseTicketsFromResponse(jsonResponse);
                    ticketResponse.setTickets(tickets);
                    
                    System.out.println("[TICKETS] ✅ Ticket trovati: " + tickets.size());
                    
                } catch (Exception e) {
                    System.err.println("[TICKETS] ⚠️ Parsing JSON fallito: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                String errorMsg = "Recupero ticket fallito. Status code: " + statusCode;
                ticketResponse.setErrorMessage(errorMsg);
                System.err.println("[TICKETS] ❌ " + errorMsg);
            }

        } catch (IOException e) {
            String errorMsg = "Errore di connessione al servizio SAP: " + e.getMessage();
            ticketResponse.setSuccess(false);
            ticketResponse.setErrorMessage(errorMsg);
            System.err.println("[TICKETS] ❌ " + errorMsg);
            e.printStackTrace();
            throw new Exception("Errore durante il recupero dei ticket", e);
        }
        // Nota: niente this.close() qui — l'istanza di SAPTicketService (e il suo httpClient/
        // connection pool) viene riusata per più chiamate successive sulla stessa pagina
        // (es. click ripetuti su "Aggiorna"). Chiudere qui rompeva ogni chiamata successiva
        // alla prima con "Connection pool shut down". La close() va invocata quando la pagina
        // Ticket List viene abbandonata (vedi TicketListUI.backToMenu()/logout()).

        System.out.println("================================================================================");
        return ticketResponse;
    }

    /**
     * Metodo semplificato per recuperare TUTTI i ticket (senza filtri)
     */
    public TicketResponse getAllTickets() throws Exception {
        return getTickets(null, null, null, null, null);
    }

    /**
     * Metodo per recuperare i ticket di un cliente specifico
     */
    public TicketResponse getTicketsByKunnr(String kunnr) throws Exception {
        return getTickets(kunnr, null, null, null, null);
    }

    /**
     * Overload con filtro aggiuntivo arbitrario (es. "Tickt eq '14'").
     * Usato internamente da getTicketById.
     */
    private TicketResponse getTickets(String kunnr, String reqid, String ownAll,
                                      String fromDate, String toDate,
                                      String rstatFilter, String extraFilter) throws Exception {
        // Costruisce i filtri normali e aggiunge extraFilter
        java.util.List<String> filters = new java.util.ArrayList<>();
        if (kunnr != null && !kunnr.isEmpty()) filters.add("Kunnr eq '" + kunnr + "'");
        if (reqid != null && !reqid.isEmpty()) filters.add("Reqid eq '" + reqid + "'");
        if (fromDate != null && !fromDate.isEmpty()) filters.add("Erdat ge '" + fromDate + "'");
        if (toDate != null && !toDate.isEmpty()) filters.add("Erdat le '" + toDate + "'");
        if (rstatFilter != null && !rstatFilter.isEmpty() && !rstatFilter.startsWith("ne:"))
            filters.add("Rstat eq '" + rstatFilter + "'");
        if (extraFilter != null && !extraFilter.isEmpty()) filters.add(extraFilter);

        String baseUrl = SAPODataConfig.getTicketsEndpoint();
        StringBuilder qs = new StringBuilder();
        if (!filters.isEmpty()) {
            String expr = String.join(" and ", filters).replace(" ", "%20");
            qs.append("$filter=").append(expr).append("&$format=json");
        } else {
            qs.append("$format=json");
        }
        String url = baseUrl + "?" + qs.toString();
        System.out.println("[TICKETS] getTicketById URL: " + url);

        org.apache.http.impl.client.CloseableHttpClient client =
            org.apache.http.impl.client.HttpClients.createDefault();
        org.apache.http.client.methods.HttpGet request = new org.apache.http.client.methods.HttpGet(url);
        request.setHeader(SAPODataConfig.HEADER_AUTHORIZATION, SAPODataConfig.getBasicAuthHeader());
        request.setHeader(SAPODataConfig.HEADER_ACCEPT, SAPODataConfig.CONTENT_TYPE_JSON);
        request.setHeader(SAPODataConfig.HEADER_SAP_CLIENT, SAPODataConfig.getSapClient());

        try (org.apache.http.client.methods.CloseableHttpResponse response = client.execute(request)) {
            int sc = response.getStatusLine().getStatusCode();
            String body = org.apache.http.util.EntityUtils.toString(response.getEntity(), "UTF-8");
            TicketResponse tr = new TicketResponse();
            tr.setStatusCode(sc);
            tr.setSuccess(sc >= 200 && sc < 300);
            if (tr.isSuccess()) {
                org.json.JSONObject json = new org.json.JSONObject(body);
                tr.setTickets(parseTicketsFromResponse(json));
            }
            return tr;
        }
    }

    /**
     * Normalizza un numero ticket nel formato usato realmente dal servizio
     * OData per il campo Tickt.
     *
     * ATTENZIONE — comportamento controintuitivo confermato empiricamente:
     * internamente in SAP (tabella, SE16) il campo è NUMC(10) con zero-padding
     * (es. "0000000003"), ma il servizio OData custom lo ESPONE senza padding
     * (la risposta JSON contiene "Tickt":"3", non "Tickt":"0000000003"), e la
     * lettura per chiave composita Mandt+Tickt richiede anch'essa il valore
     * SENZA padding. Un tentativo precedente di questo metodo faceva
     * l'opposto (zero-padding a 10 cifre) partendo dall'assunzione — sbagliata
     * — che l'OData rispecchiasse il formato NUMC interno: qui invece
     * togliamo eventuali zeri iniziali, così l'utente può scrivere sia "3"
     * che "0000000003" e in entrambi i casi arriviamo al valore che SAP
     * realmente si aspetta/restituisce.
     */
    public static String normalizeTicktNumber(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.matches("\\d+")) {
            try {
                return String.valueOf(Long.parseLong(v)); // toglie eventuali zeri iniziali
            } catch (NumberFormatException e) {
                return v; // troppo lungo per un long, lascialo inalterato
            }
        }
        return v;
    }

    /**
     * Legge un singolo ticket SAP per numero (Tickt).
     * Usato dal Dispatcher per verificare richiedente e data prima della fusione.
     */
    public Ticket getTicketById(String tickt) throws Exception {
        return getTicketById(tickt, null);
    }

    /**
     * Come {@link #getTicketById(String)}, ma se si conosce già il Kunnr del
     * cliente (es. quello del DRAFT da fondere) lo usa per restringere la
     * ricerca lato SAP invece di scaricare l'intero elenco ticket.
     *
     * Necessario perché il filtro $filter=Tickt eq '...' viene ignorato dal
     * servizio OData custom (confermato via test diretto su GetEntitySet e
     * $metadata — GetEntity per chiave composita Mandt+Tickt non è nemmeno
     * implementato lato ABAP). Kunnr invece risulta correttamente applicato
     * (già usato altrove nell'app), quindi lo sfruttiamo come pre-filtro
     * "vero" prima di cercare la corrispondenza esatta su Tickt lato client.
     * Da rimuovere/semplificare se in futuro l'ABAP implementa il filtro
     * su Tickt o il metodo GetEntity.
     */
    public Ticket getTicketById(String tickt, String kunnrHint) throws Exception {
        if (tickt == null || tickt.trim().isEmpty()) return null;
        String target = normalizeTicktNumber(tickt.trim());

        String extraFilter = "Tickt eq '" + target + "'"; // lasciato nell'URL per quando/se verrà onorato lato SAP

        Ticket found = null;
        if (kunnrHint != null && !kunnrHint.trim().isEmpty()) {
            TicketResponse resp = getTickets(kunnrHint.trim(), null, null, null, null, null, extraFilter);
            found = cercaCorrispondenzaEsatta(resp, target);
        }
        if (found == null) {
            // Fallback su ricerca non ristretta: o non avevamo un hint, o il
            // ticket non è tra quelli del Kunnr indicato — può succedere
            // legittimamente (fusione con ticket di un cliente correlato ma
            // diverso, già segnalata come warning sorpassabile più avanti in
            // checkMergeWarnings). Non vogliamo un falso "non trovato" solo
            // perché abbiamo ristretto la ricerca in modo troppo aggressivo.
            TicketResponse resp = getTickets(null, null, null, null, null, null, extraFilter);
            found = cercaCorrispondenzaEsatta(resp, target);
        }
        return found;
    }

    /**
     * IMPORTANTE: non ci fidiamo che il $filter="Tickt eq ..." abbia
     * realmente filtrato lato SAP. Evidenza empirica: sia con un numero
     * inesistente sia con uno zero-paddato correttamente ma esistente,
     * il servizio ha continuato a restituire risultati sganciati dal
     * filtro — segno che la condizione "Tickt eq" viene ignorata dal
     * Gateway/handler OData custom. Cerchiamo quindi la corrispondenza
     * esatta in TUTTO ciò che è tornato, invece di fidarci che sia già
     * filtrato e prendere il primo.
     */
    private Ticket cercaCorrispondenzaEsatta(TicketResponse resp, String target) {
        if (!resp.isSuccess() || resp.getTickets() == null || resp.getTickets().isEmpty()) {
            return null;
        }
        if (resp.getTickets().size() > 1) {
            System.out.println("[TICKETS] getTicketById — il filtro Tickt eq '" + target +
                "' ha restituito " + resp.getTickets().size() + " risultati (atteso 1) — " +
                "probabile filtro ignorato lato SAP. Cerco corrispondenza esatta lato client.");
        }
        for (Ticket t : resp.getTickets()) {
            String foundTickt = t.getTickt() != null ? t.getTickt().trim() : "";
            if (target.equalsIgnoreCase(foundTickt)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Parsing della risposta OData per estrarre l'array di ticket
     */
    private List<Ticket> parseTicketsFromResponse(JSONObject jsonResponse) {
        List<Ticket> tickets = new ArrayList<>();
        
        try {
            // Struttura tipica risposta OData SAP:
            // { "d": { "results": [ {...}, {...}, ... ] } }
            
            if (jsonResponse.has("d")) {
                JSONObject dataObject = jsonResponse.getJSONObject("d");
                
                if (dataObject.has("results")) {
                    JSONArray resultsArray = dataObject.getJSONArray("results");
                    
                    for (int i = 0; i < resultsArray.length(); i++) {
                        JSONObject ticketJson = resultsArray.getJSONObject(i);
                        Ticket ticket = new Ticket(ticketJson);
                        tickets.add(ticket);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[TICKETS] Errore nel parsing dei ticket: " + e.getMessage());
            e.printStackTrace();
        }
        
        return tickets;
    }

    /**
     * Chiude il client HTTP - da chiamare quando non serve più
     */
    public void close() {
        try {
            if (httpClient != null) {
                httpClient.close();
            }
        } catch (IOException e) {
            System.err.println("[TICKETS] Errore chiusura HttpClient: " + e.getMessage());
        }
    }

    /**
     * Classe interna per la risposta del servizio ticket
     */
    public static class TicketResponse {
        private boolean success;
        private int statusCode;
        private String responseBody;
        private String errorMessage;
        private JSONObject jsonResponse;
        private List<Ticket> tickets;

        public TicketResponse() {
            this.tickets = new ArrayList<>();
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public void setStatusCode(int statusCode) {
            this.statusCode = statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }

        public void setResponseBody(String responseBody) {
            this.responseBody = responseBody;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public JSONObject getJsonResponse() {
            return jsonResponse;
        }

        public void setJsonResponse(JSONObject jsonResponse) {
            this.jsonResponse = jsonResponse;
        }

        public List<Ticket> getTickets() {
            return tickets;
        }

        public void setTickets(List<Ticket> tickets) {
            this.tickets = tickets;
        }

        /**
         * Estrae l'oggetto "d" dalla risposta OData SAP
         */
        public JSONObject getDataObject() {
            if (jsonResponse != null && jsonResponse.has("d")) {
                return jsonResponse.getJSONObject("d");
            }
            return null;
        }
    }
}