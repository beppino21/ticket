package eone.ticket.model;

import java.io.Serializable;
import org.json.JSONObject;

/**
 * Dati dell'utente loggato per tutta la sessione.
 * Serializable per poter essere salvata in sessione HTTP.
 *
 * v2: aggiunto campo requesterInfo (da ticket_requester PostgreSQL).
 *     I campi base (username, kunnr ecc.) continuano ad essere popolati
 *     per retrocompatibilità con tutto il codice esistente.
 */
public class UserSessionData implements Serializable {

    private static final long serialVersionUID = 2L;

    // Dati principali (mantenuti per retrocompatibilità)
    private String username;
    private String utente;
    private String kunnr;
    private String richiedente;
    private String ownAll;

    // Stato logon
    private boolean hasError;
    private String  errorMessage;
    private String  loginMessage;

    // Metadati
    private String userId;
    private String sessionId;
    private long   loginTimestamp;

    // Dati estesi da PostgreSQL (nuovo in v2)
    private RequesterInfo requesterInfo;

    // =========================================================
    // COSTRUTTORI
    // =========================================================

    public UserSessionData() {
        this.loginTimestamp = System.currentTimeMillis();
    }

    /** Costruttore legacy da risposta SAP JSON (mantenuto per compatibilità) */
    public UserSessionData(JSONObject sapResponse) {
        this();
        populateFromSAPResponse(sapResponse);
    }

    /** Popola i dati dalla risposta SAP (percorso legacy — non più usato per logon) */
    public void populateFromSAPResponse(JSONObject sapResponse) {
        if (sapResponse == null) return;
        this.username    = getStringField(sapResponse, "Username");
        this.utente      = getStringField(sapResponse, "Utente");
        this.kunnr       = getStringField(sapResponse, "Kunnr");
        this.richiedente = getStringField(sapResponse, "Richiedente");
        this.ownAll      = getStringField(sapResponse, "OwnAll");
        String hasErrorStr = getStringField(sapResponse, "HasError");
        this.hasError    = "X".equalsIgnoreCase(hasErrorStr) || "true".equalsIgnoreCase(hasErrorStr);
        this.errorMessage = getStringField(sapResponse, "ErrorMessage");
        this.loginMessage = this.errorMessage;
        if (sapResponse.has("__metadata")) {
            JSONObject metadata = sapResponse.getJSONObject("__metadata");
            this.userId = getStringField(metadata, "id");
        }
    }

    private String getStringField(JSONObject json, String field) {
        if (json != null && json.has(field)) {
            Object v = json.get(field);
            return v != null ? v.toString() : null;
        }
        return null;
    }

    // =========================================================
    // BUSINESS LOGIC
    // =========================================================

    public boolean hasAllPermissions() { return "ALL".equalsIgnoreCase(ownAll); }

    public boolean isLoggedIn()        { return username != null && !hasError; }

    public boolean hasValidCustomer()  {
        return kunnr != null && !kunnr.trim().isEmpty() && !"0000000000".equals(kunnr);
    }

    /** True se il ruolo è CLIENTE (da RequesterInfo, o fallback su kunnr) */
    public boolean isCliente() {
        if (requesterInfo != null) return requesterInfo.isCliente();
        return hasValidCustomer();
    }

    /** True se il ruolo è AMS */
    public boolean isAms() {
        if (requesterInfo != null) return requesterInfo.isAms();
        return !isCliente();
    }

    // =========================================================
    // GETTER / SETTER
    // =========================================================

    public String getUsername()              { return username; }
    public void setUsername(String v)        { this.username = v; }

    public String getUtente()               { return utente; }
    public void setUtente(String v)         { this.utente = v; }

    public String getKunnr()                { return kunnr; }
    public void setKunnr(String v)          { this.kunnr = v; }

    public String getRichiedente()          { return richiedente; }
    public void setRichiedente(String v)    { this.richiedente = v; }

    public String getOwnAll()               { return ownAll; }
    public void setOwnAll(String v)         { this.ownAll = v; }

    public boolean isHasError()             { return hasError; }
    public void setHasError(boolean v)      { this.hasError = v; }

    public String getErrorMessage()         { return errorMessage; }
    public void setErrorMessage(String v)   { this.errorMessage = v; }

    public String getLoginMessage()         { return loginMessage; }
    public void setLoginMessage(String v)   { this.loginMessage = v; }

    public String getUserId()               { return userId; }
    public void setUserId(String v)         { this.userId = v; }

    public String getSessionId()            { return sessionId; }
    public void setSessionId(String v)      { this.sessionId = v; }

    public long getLoginTimestamp()         { return loginTimestamp; }
    public void setLoginTimestamp(long v)   { this.loginTimestamp = v; }

    public RequesterInfo getRequesterInfo()          { return requesterInfo; }
    public void setRequesterInfo(RequesterInfo info) { this.requesterInfo = info; }

    @Override
    public String toString() {
        return "UserSessionData{username='" + username + "', kunnr='" + kunnr +
               "', ruolo='" + (requesterInfo != null ? requesterInfo.getRuolo() : "N/A") + "'}";
    }
}
