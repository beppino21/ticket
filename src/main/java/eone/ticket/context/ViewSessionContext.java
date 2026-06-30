package eone.ticket.context;

import org.eclnt.jsfserver.util.HttpSessionAccess;

import eone.ticket.model.RequesterInfo;

/**
 * Contesto di sessione per l'utente loggato.
 * Singleton per sessione CC dialog (HttpSessionAccess).
 *
 * v3: aggiunto campo RequesterInfo (proveniente da ticket_requester)
 *     che porta ruolo, nome e kunnr dopo logon su PostgreSQL.
 */
public class ViewSessionContext {

    public static ViewSessionContext instance() {
        ViewSessionContext result = (ViewSessionContext) HttpSessionAccess
                .getCurrentDialogSession()
                .getAttribute(ViewSessionContext.class.getName());
        if (result == null) {
            result = new ViewSessionContext();
            HttpSessionAccess.getCurrentDialogSession()
                    .setAttribute(ViewSessionContext.class.getName(), result);
        }
        return result;
    }

    // Campi base (mantenuti per compatibilità con il codice esistente)
    private String username;
    private String utente;
    private String kunnr;
    private String richiedente;
    private String ownAll;

    // Dati estesi da ticket_requester (nuovi)
    private RequesterInfo requesterInfo;

    // =========================================================
    // GETTER / SETTER campi base
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

    // =========================================================
    // RequesterInfo
    // =========================================================

    public RequesterInfo getRequesterInfo()          { return requesterInfo; }
    public void setRequesterInfo(RequesterInfo info) { this.requesterInfo = info; }

    // =========================================================
    // CONVENIENCE — evitano null-check sparsi nel codice
    // =========================================================

    /**
     * True se l'utente loggato è di tipo CLIENTE.
     * Se RequesterInfo non è disponibile (caso legacy SAP), usa kunnr come fallback.
     */
    public boolean isCliente() {
        if (requesterInfo != null) return requesterInfo.isCliente();
        return kunnr != null && !kunnr.trim().isEmpty();
    }

    /** True se l'utente è AMS (assistenza). */
    public boolean isAms() {
        if (requesterInfo != null) return requesterInfo.isAms();
        return !isCliente();
    }

    /** Ruolo stringa (CLIENTE / AMS / ADMIN), o stringa vuota se non disponibile. */
    public String getRuolo() {
        if (requesterInfo != null && requesterInfo.getRuolo() != null)
            return requesterInfo.getRuolo();
        return "";
    }

    /** Nome visualizzabile dell'utente loggato (nome da DB, fallback su reqid). */
    public String getNomeUtente() {
        if (requesterInfo != null) return requesterInfo.getNomeOReqid();
        return utente != null ? utente : "";
    }
}
