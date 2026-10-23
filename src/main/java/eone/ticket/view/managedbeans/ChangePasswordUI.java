package eone.ticket.view.managedbeans;

import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.pagebean.PageBean;

import eone.ticket.model.PasswordPolicy;
import eone.ticket.model.RequesterInfo;
import eone.ticket.service.AuditService;
import eone.ticket.service.ClienteConfigService;
import eone.ticket.service.RequesterService;

/**
 * Bean per il popup "Cambio Password".
 *
 * Flusso:
 *   1. LogonUI apre questo popup via openModalPopup()
 *   2. L'utente inserisce: vecchia password / nuova password / conferma
 *   3. onCambiaPassword() verifica la password attuale, valida la nuova
 *      contro la PasswordPolicy del cliente (Kunnr) dell'utente e delega a
 *      RequesterService.changePassword()
 *   4. In caso di successo, il popup mostra un messaggio e può essere chiuso
 *
 * N.B. Il popup viene aperto PRIMA del logon, quindi non c'è utente in sessione:
 * il campo m_reqid viene passato esplicitamente da LogonUI tramite prepare().
 */
@CCGenClass(expressionBase = "#{d.ChangePasswordUI}")
public class ChangePasswordUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    // =========================================================
    // LISTENER (callback verso LogonUI)
    // =========================================================

    public interface IListener extends Serializable {
        /** Chiamato dopo cambio password riuscito */
        void reactOnPasswordChanged();
        /** Chiamato se l'utente chiude il popup senza fare nulla */
        void reactOnCancel();
    }

    // =========================================================
    // MEMBERS
    // =========================================================

    private IListener m_listener;
    private String    m_reqid;          // username — passato da LogonUI

    private String    m_vecchiaPassword;
    private String    m_nuovaPassword;
    private String    m_confermaPassword;
    private String    m_statusMessage   = "";
    private boolean   m_hasError        = false;
    private boolean   m_success         = false;

    /** Hint requisiti mostrato sotto "Nuova password". */
    private String    m_requisitiHint   = "";

    private final RequesterService     m_requesterService     = new RequesterService();
    private final ClienteConfigService m_clienteConfigService = new ClienteConfigService();

    // =========================================================
    // SETUP
    // =========================================================

    /**
     * Chiamato da LogonUI prima di aprire il popup.
     *
     * @param reqid    username del campo di logon (può essere vuoto)
     * @param listener callback
     */
    public void prepare(String reqid, IListener listener) {
        this.m_reqid    = reqid != null ? reqid.trim() : "";
        this.m_listener = listener;
        resetForm();
        // Prima del logon l'utente non è autenticato: non si risolve il suo
        // Kunnr (eviterebbe di rivelare a chiunque se uno username esiste e
        // di che cliente è). I requisiti precisi compaiono dopo la verifica
        // della password attuale, se la nuova non li rispetta.
        m_requisitiHint = "(requisiti definiti per il tuo cliente — verificati alla conferma)";
    }

    /**
     * Variante per utente già autenticato (es. MenuUI): il Kunnr è noto,
     * quindi l'hint mostra subito i requisiti effettivi.
     */
    public void prepare(String reqid, String kunnr, IListener listener) {
        prepare(reqid, listener);
        try {
            m_requisitiHint = "(" + m_clienteConfigService.getPasswordPolicy(kunnr).getDescrizione() + ")";
        } catch (Exception e) {
            System.err.println("[ChangePasswordUI] Impossibile leggere la policy per kunnr " + kunnr + ": " + e.getMessage());
        }
    }

    private void resetForm() {
        m_vecchiaPassword  = "";
        m_nuovaPassword    = "";
        m_confermaPassword = "";
        m_statusMessage    = "";
        m_hasError         = false;
        m_success          = false;
    }

    // =========================================================
    // AZIONI
    // =========================================================

    public void onCambiaPassword(ActionEvent ae) {
        m_hasError      = false;
        m_statusMessage = "";

        // --- Validazioni di base ---
        if (m_reqid == null || m_reqid.isEmpty()) {
            setError("Inserire prima lo username nel form di logon, poi richiedere il cambio password.");
            return;
        }
        if (m_vecchiaPassword == null || m_vecchiaPassword.isEmpty()) {
            setError("Inserire la password attuale.");
            return;
        }
        if (m_nuovaPassword == null || m_nuovaPassword.isEmpty()) {
            setError("Inserire la nuova password.");
            return;
        }
        if (!m_nuovaPassword.equals(m_confermaPassword)) {
            setError("La nuova password e la conferma non coincidono.");
            return;
        }
        if (m_nuovaPassword.equals(m_vecchiaPassword)) {
            setError("La nuova password deve essere diversa da quella attuale.");
            return;
        }

        // --- Cambio password ---
        try {
            // Verifica la password attuale PRIMA di applicare la policy: il
            // Kunnr (e quindi la policy) si legge solo per un utente autenticato.
            RequesterInfo info = m_requesterService.authenticate(m_reqid, m_vecchiaPassword);
            if (info == null) {
                AuditService.log(m_reqid, null, null, AuditService.PASSWORD_CAMBIO_FAIL, AuditService.ESITO_FAIL,
                                 m_reqid, "password attuale errata, utente inesistente o bloccato");
                setError("Password attuale non corretta, oppure utente temporaneamente bloccato per troppi tentativi.");
                return;
            }
            PasswordPolicy policy = m_clienteConfigService.getPasswordPolicy(info.getKunnr());
            m_requisitiHint = "(" + policy.getDescrizione() + ")";
            String erroreRequisiti = policy.valida(m_nuovaPassword);
            if (erroreRequisiti != null) {
                AuditService.log(info.getId_user(), info.getKunnr(), info.getRuolo(), AuditService.PASSWORD_CAMBIO_FAIL,
                                 AuditService.ESITO_FAIL, info.getId_user(), "nuova password non conforme alla policy");
                setError(erroreRequisiti);
                return;
            }

            boolean ok = m_requesterService.changePassword(m_reqid, m_vecchiaPassword, m_nuovaPassword);
            if (ok) {
                AuditService.log(info.getId_user(), info.getKunnr(), info.getRuolo(), AuditService.PASSWORD_CAMBIATA,
                                 AuditService.ESITO_OK, info.getId_user(), null);
                m_success       = true;
                m_statusMessage = "✅ Password cambiata con successo. Ora puoi accedere con la nuova password.";
                m_hasError      = false;
                Statusbar.outputSuccess("Password aggiornata per: " + m_reqid);
                System.out.println("[ChangePasswordUI] ✅ Password cambiata per: " + m_reqid);
                if (m_listener != null) m_listener.reactOnPasswordChanged();
            } else {
                setError("Password attuale non corretta. Verificare e riprovare.");
            }
        } catch (Exception e) {
            setError("Errore durante il cambio password: " + e.getMessage());
            System.err.println("[ChangePasswordUI] ❌ Eccezione: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void onAnnulla(ActionEvent ae) {
        System.out.println("[ChangePasswordUI] Annullato da: " + m_reqid);
        if (m_listener != null) m_listener.reactOnCancel();
    }

    // =========================================================
    // GETTERS / SETTERS
    // =========================================================

    @Override
    public String getPageName()                 { return "/ChangePassword.xml"; }
    @Override
    public String getRootExpressionUsedInPage() { return "#{d.ChangePasswordUI}"; }

    public String getReqid()                    { return m_reqid; }
    public void   setReqid(String v)            { this.m_reqid = v; }

    public String getVecchiaPassword()          { return m_vecchiaPassword; }
    public void setVecchiaPassword(String v)    { this.m_vecchiaPassword = v; }

    public String getNuovaPassword()            { return m_nuovaPassword; }
    public void setNuovaPassword(String v)      { this.m_nuovaPassword = v; }

    public String getConfermaPassword()         { return m_confermaPassword; }
    public void setConfermaPassword(String v)   { this.m_confermaPassword = v; }

    public String getStatusMessage()            { return m_statusMessage; }
    public boolean isHasError()                 { return m_hasError; }
    public boolean isSuccess()                  { return m_success; }
    public String getRequisitiHint()            { return m_requisitiHint; }

    // =========================================================
    // PRIVATE
    // =========================================================

    private void setError(String msg) {
        m_hasError      = true;
        m_statusMessage = msg;
        Statusbar.outputError(msg);
        System.err.println("[ChangePasswordUI] ❌ " + msg);
    }
}
