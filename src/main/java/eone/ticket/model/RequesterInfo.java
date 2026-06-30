package eone.ticket.model;

import java.io.Serializable;

/**
 * Dati dell'utente letti da ticket_requester dopo autenticazione.
 * Serializable perché finisce in ViewSessionContext (sessione HTTP).
 */
public class RequesterInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String  id_user;  // PK VARCHAR(20) — username assegnato dal backoffice
    private String  kunnr;
    private String  reqid;
    private String  nome;
    private String  email;
    private String  ruolo;       // CLIENTE | AMS | ADMIN
    private boolean vedeTutti;   // true = vede tutti i ticket del cliente

    // =========================================================
    // BUSINESS LOGIC
    // =========================================================

    /** True se l'utente è di tipo cliente (ruolo CLIENTE) */
    public boolean isCliente() {
        return "CLIENTE".equalsIgnoreCase(ruolo);
    }

    /** True se l'utente è del servizio assistenza (ruolo AMS) */
    public boolean isAms() {
        return "AMS".equalsIgnoreCase(ruolo);
    }

    /** True se l'utente è amministratore */
    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(ruolo);
    }

    /**
     * Restituisce il nome visualizzabile.
     * Se il campo nome non è valorizzato, torna il reqid come fallback.
     */
    public String getNomeOReqid() {
        return (nome != null && !nome.trim().isEmpty()) ? nome.trim() : reqid;
    }

    // =========================================================
    // GETTERS / SETTERS
    // =========================================================

    public String getId_user()           { return id_user; }
    public void setId_user(String v)     { this.id_user = v; }

    public String getKunnr()            { return kunnr; }
    public void setKunnr(String v)      { this.kunnr = v; }

    public String getReqid()            { return reqid; }
    public void setReqid(String v)      { this.reqid = v; }

    public String getNome()             { return nome; }
    public void setNome(String v)       { this.nome = v; }

    public String getEmail()            { return email; }
    public void setEmail(String v)      { this.email = v; }

    public String getRuolo()            { return ruolo; }
    public void setRuolo(String v)      { this.ruolo = v; }

    public boolean isVedeTutti()        { return vedeTutti; }
    public void setVedeTutti(boolean v) { this.vedeTutti = v; }

    @Override
    public String toString() {
        return "RequesterInfo{reqid='" + reqid + "', nome='" + nome +
               "', ruolo='" + ruolo + "', kunnr='" + kunnr + "'}";
    }
}