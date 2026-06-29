package eone.ticket.service;

import java.io.IOException;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.CookieStore;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import eone.ticket.config.SAPODataConfig;
import eone.ticket.model.UserSessionData;

/**
 * Service per gestire il logon tramite servizio oData SAP CON GESTIONE CORRETTA
 * DEI COOKIE
 */
public class SAPLogonService {

	// Cookie store condiviso per mantenere la sessione
	private CookieStore cookieStore;
	private CloseableHttpClient httpClient;

	/**
	 * Costruttore - inizializza il client HTTP con cookie store
	 */
	public SAPLogonService() {
		this.cookieStore = new BasicCookieStore();
		this.httpClient = HttpClients.custom().setDefaultCookieStore(cookieStore).build();
	}

	/**
	 * Recupera il CSRF token dal servizio SAP IMPORTANTE: Questa chiamata
	 * stabilisce anche la sessione (cookie)
	 * 
	 * @return Il CSRF token recuperato
	 * @throws Exception Se non è possibile recuperare il token
	 */
	private String fetchCSRFToken() throws Exception {
		String csrfToken = null;

		System.out.println("================================================================================");
		System.out.println("[CSRF-TOKEN] Inizio recupero CSRF Token");
		System.out.println("================================================================================");

		try {
			// USA L'ENDPOINT LOGON invece del base URL per forzare la creazione della
			// sessione
			String url = SAPODataConfig.getLogonEndpoint();
			System.out.println("[CSRF-TOKEN] URL: " + url);

			HttpGet request = new HttpGet(url);

			// Imposta gli headers necessari
			String authHeader = SAPODataConfig.getBasicAuthHeader();
			System.out.println("[CSRF-TOKEN] Authorization: " + authHeader);

			request.setHeader(SAPODataConfig.HEADER_AUTHORIZATION, authHeader);
			request.setHeader(SAPODataConfig.HEADER_CSRF_TOKEN, SAPODataConfig.HEADER_CSRF_FETCH);
			request.setHeader(SAPODataConfig.HEADER_ACCEPT, SAPODataConfig.CONTENT_TYPE_JSON);
			request.setHeader(SAPODataConfig.HEADER_SAP_CLIENT, SAPODataConfig.SAP_CLIENT);

			System.out.println("[CSRF-TOKEN] Headers inviati:");
			for (Header header : request.getAllHeaders()) {
				System.out.println("  - " + header.getName() + ": " + header.getValue());
			}

			System.out.println("[CSRF-TOKEN] Esecuzione chiamata HTTP GET...");

			// Esegue la richiesta CON LO STESSO httpClient
			HttpResponse response = httpClient.execute(request);
			int statusCode = response.getStatusLine().getStatusCode();

			System.out.println("[CSRF-TOKEN] ==========================================");
			System.out.println("[CSRF-TOKEN] RISPOSTA RICEVUTA");
			System.out.println("[CSRF-TOKEN] ==========================================");
			System.out.println("[CSRF-TOKEN] Status Code: " + statusCode);
			System.out.println("[CSRF-TOKEN] Status Line: " + response.getStatusLine());

			// Stampa TUTTI gli headers della risposta
			System.out.println("[CSRF-TOKEN] Headers ricevuti:");
			for (Header header : response.getAllHeaders()) {
				System.out.println("  - " + header.getName() + ": " + header.getValue());
			}

			// DEBUG: Stampa i cookie ricevuti
			System.out.println("[CSRF-TOKEN] Cookie ricevuti: " + cookieStore.getCookies().size());
			for (Cookie cookie : cookieStore.getCookies()) {
				System.out.println("  - " + cookie.getName() + " = " + cookie.getValue());
				System.out.println("    Domain: " + cookie.getDomain() + ", Path: " + cookie.getPath());
			}

			// Leggi il body
			String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
			System.out.println("[CSRF-TOKEN] Body (primi 500 char): "
					+ responseBody.substring(0, Math.min(500, responseBody.length())));

			// Recupera il token dall'header della risposta
			if (response.getFirstHeader(SAPODataConfig.HEADER_CSRF_TOKEN) != null) {
				csrfToken = response.getFirstHeader(SAPODataConfig.HEADER_CSRF_TOKEN).getValue();
				System.out.println("[CSRF-TOKEN] ✅ Token trovato: " + csrfToken);
			} else {
				System.err.println("[CSRF-TOKEN] ❌ Header 'x-csrf-token' NON TROVATO!");
			}

		} catch (IOException e) {
			System.err.println("[CSRF-TOKEN] ❌ ERRORE di connessione:");
			e.printStackTrace();
			throw new Exception("Errore durante il recupero del CSRF token: " + e.getMessage(), e);
		}

		System.out.println("================================================================================");

		if (csrfToken == null || csrfToken.isEmpty()) {
			throw new Exception("Impossibile recuperare il CSRF token dal servizio SAP");
		}

		return csrfToken;
	}

	/**
	 * Esegue il logon chiamando il servizio SAP oData USA LO STESSO httpClient
	 * della chiamata precedente (con cookie)
	 * 
	 * @param username Username dell'utente
	 * @param password Password dell'utente
	 * @return LogonResponse con l'esito dell'operazione
	 * @throws Exception In caso di errore durante la chiamata
	 */
	public LogonResponse performLogon(String username, String password) throws Exception {
		LogonResponse logonResponse = new LogonResponse();

		System.out.println("================================================================================");
		System.out.println("[LOGON] Inizio logon per utente: " + username);
		System.out.println("================================================================================");

		try {
			// Step 1: Recupera il CSRF token (e stabilisce la sessione)
			String csrfToken = fetchCSRFToken();

			if (csrfToken == null || csrfToken.trim().isEmpty()) {
				throw new Exception("CSRF token è nullo o vuoto");
			}

			System.out.println("[LOGON] CSRF Token ottenuto: " + csrfToken);
			System.out.println("[LOGON] Cookie nello store: " + cookieStore.getCookies().size());

			// Step 2: Prepara la richiesta POST per il logon
			String url = SAPODataConfig.getLogonEndpoint();
			System.out.println("[LOGON] URL: " + url);

			HttpPost request = new HttpPost(url);

			// Imposta gli headers necessari (incluso sap-client!)
			request.setHeader(SAPODataConfig.HEADER_AUTHORIZATION, SAPODataConfig.getBasicAuthHeader());
			request.setHeader(SAPODataConfig.HEADER_CONTENT_TYPE, SAPODataConfig.CONTENT_TYPE_JSON);
			request.setHeader(SAPODataConfig.HEADER_ACCEPT, SAPODataConfig.CONTENT_TYPE_JSON);
			request.setHeader(SAPODataConfig.HEADER_CSRF_TOKEN, csrfToken);
			request.setHeader(SAPODataConfig.HEADER_SAP_CLIENT, SAPODataConfig.SAP_CLIENT);

			System.out.println("[LOGON] Headers inviati:");
			for (Header header : request.getAllHeaders()) {
				if (header.getName().equals("Authorization")) {
					System.out.println("  - Authorization: [REDACTED]");
				} else {
					System.out.println("  - " + header.getName() + ": " + header.getValue());
				}
			}

			// Crea il payload JSON con username e password
			JSONObject payload = new JSONObject();
			payload.put("Username", username);
			payload.put("Password", password);

			System.out.println("[LOGON] Payload: {Username: " + username + ", Password: ***}");

			StringEntity entity = new StringEntity(payload.toString(), "UTF-8");
			request.setEntity(entity);

			System.out.println("[LOGON] Esecuzione chiamata HTTP POST...");

			// Esegue la richiesta POST CON LO STESSO httpClient (con cookie!)
			HttpResponse response = httpClient.execute(request);
			int statusCode = response.getStatusLine().getStatusCode();
			String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

			System.out.println("[LOGON] ==========================================");
			System.out.println("[LOGON] RISPOSTA RICEVUTA");
			System.out.println("[LOGON] ==========================================");
			System.out.println("[LOGON] Status Code: " + statusCode);
			System.out.println("[LOGON] Response Body: " + responseBody);

			// Elabora la risposta
			logonResponse.setStatusCode(statusCode);
			logonResponse.setSuccess(statusCode >= 200 && statusCode < 300);
			logonResponse.setResponseBody(responseBody);

			if (logonResponse.isSuccess()) {
				System.out.println("[LOGON] ✅ Logon RIUSCITO!");

				// Parsing della risposta JSON se necessario
				try {
					JSONObject jsonResponse = new JSONObject(responseBody);
					logonResponse.setJsonResponse(jsonResponse);
				} catch (Exception e) {
					System.err.println("[LOGON] ⚠️ Parsing JSON fallito: " + e.getMessage());
				}
			} else {
				String errorMsg = "Logon fallito. Status code: " + statusCode;
				logonResponse.setErrorMessage(errorMsg);
				System.err.println("[LOGON] ❌ " + errorMsg);
			}

		} catch (IOException e) {
			String errorMsg = "Errore di connessione al servizio SAP: " + e.getMessage();
			logonResponse.setSuccess(false);
			logonResponse.setErrorMessage(errorMsg);
			System.err.println("[LOGON] ❌ " + errorMsg);
			e.printStackTrace();
			throw new Exception("Errore durante il logon", e);
		} finally {
			this.close(); // ← Chiudi alla fine di TUTTE le operazioni
		}
		System.out.println("================================================================================");
		
		return logonResponse;
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
			System.err.println("[LOGON] Errore chiusura HttpClient: " + e.getMessage());
		}
	}

	/**
	 * Classe interna per la risposta del logon
	 */
	public static class LogonResponse {
	    private boolean success;
	    private int statusCode;
	    private String responseBody;
	    private String errorMessage;
	    private JSONObject jsonResponse;
	    
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
	    
	    /**
	     * Estrae l'oggetto "d" dalla risposta OData SAP
	     */
	    public JSONObject getDataObject() {
	        if (jsonResponse != null && jsonResponse.has("d")) {
	            return jsonResponse.getJSONObject("d");
	        }
	        return null;
	    }
	    
	    /**
	     * Crea un oggetto UserSessionData dalla risposta
	     */
	    public UserSessionData toUserSessionData() {
	        JSONObject dataObj = getDataObject();
	        if (dataObj != null) {
	            return new UserSessionData(dataObj);
	        }
	        return null;
	    }
	}
}