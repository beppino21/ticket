package eone.ticket.model;

import java.io.Serializable;
import org.json.JSONObject;

/**
 * Classe che contiene i dati dell'utente loggato per tutta la sessione
 * DEVE essere Serializable per poter essere salvata in sessione HTTP
 */
public class UserSessionData implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // Dati principali utente
    private String username;
    private String utente;
    private String kunnr;
    private String richiedente;
    private String ownAll;
    
    // Stato logon
    private boolean hasError;
    private String errorMessage;
    private String loginMessage;
    
    // Metadati
    private String userId;
    private String sessionId;
    private long loginTimestamp;
    
    // NOTA: NON salvare oggetti JSONObject in sessione!
    // JSONObject non è Serializable
    
    /**
     * Costruttore vuoto
     */
    public UserSessionData() {
        this.loginTimestamp = System.currentTimeMillis();
    }
    
    /**
     * Costruttore che popola i dati dalla risposta SAP
     */
    public UserSessionData(JSONObject sapResponse) {
        this();
        populateFromSAPResponse(sapResponse);
    }
    
    /**
     * Popola i dati dalla risposta SAP
     */
    public void populateFromSAPResponse(JSONObject sapResponse) {
        if (sapResponse == null) {
            return;
        }
        
        this.username = getStringField(sapResponse, "Username");
        this.utente = getStringField(sapResponse, "Utente");
        this.kunnr = getStringField(sapResponse, "Kunnr");
        this.richiedente = getStringField(sapResponse, "Richiedente");
        this.ownAll = getStringField(sapResponse, "OwnAll");
        
        String hasErrorStr = getStringField(sapResponse, "HasError");
        this.hasError = "X".equalsIgnoreCase(hasErrorStr) || "true".equalsIgnoreCase(hasErrorStr);
        this.errorMessage = getStringField(sapResponse, "ErrorMessage");
        this.loginMessage = this.errorMessage;
        
        if (sapResponse.has("__metadata")) {
            JSONObject metadata = sapResponse.getJSONObject("__metadata");
            this.userId = getStringField(metadata, "id");
        }
    }
    
    /**
     * Metodo helper per estrarre stringhe dal JSON in modo sicuro
     */
    private String getStringField(JSONObject json, String fieldName) {
        if (json != null && json.has(fieldName)) {
            Object value = json.get(fieldName);
            return value != null ? value.toString() : null;
        }
        return null;
    }
    
    // ========== METODI DI BUSINESS LOGIC ==========
    
    public boolean hasAllPermissions() {
        return "ALL".equalsIgnoreCase(ownAll);
    }
    
    public boolean isLoggedIn() {
        return username != null && !hasError;
    }
    
    public boolean hasValidCustomer() {
        return kunnr != null && !kunnr.trim().isEmpty() && !"0000000000".equals(kunnr);
    }
    
    // ========== GETTER E SETTER ==========
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getUtente() {
        return utente;
    }
    
    public void setUtente(String utente) {
        this.utente = utente;
    }
    
    public String getKunnr() {
        return kunnr;
    }
    
    public void setKunnr(String kunnr) {
        this.kunnr = kunnr;
    }
    
    public String getRichiedente() {
        return richiedente;
    }
    
    public void setRichiedente(String richiedente) {
        this.richiedente = richiedente;
    }
    
    public String getOwnAll() {
        return ownAll;
    }
    
    public void setOwnAll(String ownAll) {
        this.ownAll = ownAll;
    }
    
    public boolean isHasError() {
        return hasError;
    }
    
    public void setHasError(boolean hasError) {
        this.hasError = hasError;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public String getLoginMessage() {
        return loginMessage;
    }
    
    public void setLoginMessage(String loginMessage) {
        this.loginMessage = loginMessage;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
    
    public long getLoginTimestamp() {
        return loginTimestamp;
    }
    
    public void setLoginTimestamp(long loginTimestamp) {
        this.loginTimestamp = loginTimestamp;
    }
    
    @Override
    public String toString() {
        return "UserSessionData{" +
                "username='" + username + '\'' +
                ", utente='" + utente + '\'' +
                ", kunnr='" + kunnr + '\'' +
                ", richiedente='" + richiedente + '\'' +
                ", ownAll='" + ownAll + '\'' +
                ", hasError=" + hasError +
                ", loginMessage='" + loginMessage + '\'' +
                '}';
    }
}