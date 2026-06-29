package eone.ticket.context;

import org.eclnt.jsfserver.util.HttpSessionAccess;

/**
 * Contesto di sessione per l'utente loggato.
 * Singleton per sessione CC dialog (HttpSessionAccess).
 *
 * BUGFIX v2: setRichiedente ora scrive su this.richiedente (non su this.utente).
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

    private String username;
    private String utente;
    private String kunnr;
    private String richiedente;
    private String ownAll;

    public String getUsername()              { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getUtente()               { return utente; }
    public void setUtente(String utente)    { this.utente = utente; }

    public String getKunnr()                { return kunnr; }
    public void setKunnr(String kunnr)      { this.kunnr = kunnr; }

    public String getRichiedente()                       { return richiedente; }
    public void setRichiedente(String richiedente)       { this.richiedente = richiedente; }  // BUGFIX: era this.utente

    public String getOwnAll()               { return ownAll; }
    public void setOwnAll(String ownAll)    { this.ownAll = ownAll; }

    /** Restituisce true se l'utente è un cliente (ha kunnr valorizzato) */
    public boolean isCliente() {
        return kunnr != null && !kunnr.trim().isEmpty();
    }

    /** Restituisce true se l'utente è del servizio assistenza (nessun kunnr) */
    public boolean isAssistenza() {
        return !isCliente();
    }
}
