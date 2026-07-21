package eone.ticket.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

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
    private String  ruolo;       // CLIENTE | AMS | ADMIN | DISPATCHER | AMS_ADMIN | REQ_ADMIN
    private boolean vedeTutti;   // true = vede tutti i ticket del cliente
    private boolean attivo = true;

    // --- Policy password (v3) ---------------------------------
    // Nessun flag "deve cambiare password" a parte: tutto si deriva da
    // questi 3 campi, per evitare due fonti di verità sullo stesso stato.
    private LocalDateTime passwordImpostataIl;
    private int           passwordScadenzaGiorni = 90;
    private boolean       passwordNonScade;

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

    /** True se l'utente amministra gli utenti AMS/DISPATCHER */
    public boolean isAmsAdmin() {
        return "AMS_ADMIN".equalsIgnoreCase(ruolo);
    }

    /** True se l'utente amministra i richiedenti (CLIENTE) del proprio kunnr */
    public boolean isReqAdmin() {
        return "REQ_ADMIN".equalsIgnoreCase(ruolo);
    }

    /**
     * Restituisce il nome visualizzabile.
     * Se il campo nome non è valorizzato, torna il reqid come fallback.
     */
    public String getNomeOReqid() {
        return (nome != null && !nome.trim().isEmpty()) ? nome.trim() : reqid;
    }

    // --- Policy password: logica derivata, nessuno stato duplicato ---

    /** Data di scadenza calcolata (irrilevante se passwordNonScade=true). */
    private LocalDateTime getDataScadenza() {
        LocalDateTime base = passwordImpostataIl != null ? passwordImpostataIl : LocalDateTime.now();
        return base.plusDays(passwordScadenzaGiorni);
    }

    /** True se la password è scaduta. Mai true se passwordNonScade=true. */
    public boolean isPasswordScaduta() {
        if (passwordNonScade) return false;
        return getDataScadenza().isBefore(LocalDateTime.now());
    }

    /** Giorni mancanti alla scadenza (negativo se già scaduta). Long.MAX_VALUE se non scade mai. */
    public long getGiorniAllaScadenzaPassword() {
        if (passwordNonScade) return Long.MAX_VALUE;
        return ChronoUnit.DAYS.between(LocalDateTime.now(), getDataScadenza());
    }

    /** True negli ultimi 10 giorni prima della scadenza (non ancora scaduta). */
    public boolean isPasswordInAvvisoScadenza() {
        if (passwordNonScade) return false;
        long giorni = getGiorniAllaScadenzaPassword();
        return giorni >= 0 && giorni <= 10;
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

    public boolean isAttivo()           { return attivo; }
    public void setAttivo(boolean v)    { this.attivo = v; }

    public LocalDateTime getPasswordImpostataIl()        { return passwordImpostataIl; }
    public void setPasswordImpostataIl(LocalDateTime v)  { this.passwordImpostataIl = v; }

    public int getPasswordScadenzaGiorni()               { return passwordScadenzaGiorni; }
    public void setPasswordScadenzaGiorni(int v)         { this.passwordScadenzaGiorni = v; }

    public boolean isPasswordNonScade()                  { return passwordNonScade; }
    public void setPasswordNonScade(boolean v)           { this.passwordNonScade = v; }

    @Override
    public String toString() {
        return "RequesterInfo{reqid='" + reqid + "', nome='" + nome +
               "', ruolo='" + ruolo + "', kunnr='" + kunnr + "'}";
    }
}