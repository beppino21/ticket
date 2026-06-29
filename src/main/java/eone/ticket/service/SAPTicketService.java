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
        TicketResponse ticketResponse = new TicketResponse();

        System.out.println("================================================================================");
        System.out.println("[TICKETS] Inizio recupero ticket");
        System.out.println("================================================================================");

        try {
            // Costruisci l'URL base
            String baseUrl = SAPODataConfig.getTicketsEndpoint();
            
            // Costruisci i filtri OData
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
            
            // Costruisci la query string
            StringBuilder queryString = new StringBuilder();
            
            if (!filters.isEmpty()) {
                String filterExpression = String.join(" and ", filters);
                // ✅ Sostituisci SOLO gli spazi con %20 (non encodare tutto!)
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
            request.setHeader(SAPODataConfig.HEADER_SAP_CLIENT, SAPODataConfig.SAP_CLIENT);

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
        } finally {
            this.close();
        }

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
