package eone.ticket.view.managedbeans;

import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.ModalPopup;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.pagebean.PageBean;

import eone.ticket.model.RequesterInfo;
import eone.ticket.model.UserSessionData;
import eone.ticket.service.RequesterService;

/**
 * LogonUI v2 — autenticazione contro PostgreSQL (ticket_requester).
 *
 * Modifiche rispetto alla v1:
 *  - checkLogonData() non chiama più SAPLogonService ma RequesterService.authenticate()
 *  - UserSessionData viene costruito dai campi di RequesterInfo
 *  - onRequestNewPasswordAction() apre un popup modale ChangePasswordUI
 *    (il link era già presente nell'XML ma non implementato)
 */
@CCGenClass(expressionBase = "#{d.LogonUI}")
public class LogonUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    // =========================================================
    // LISTENER
    // =========================================================

    public interface IListener extends Serializable {
        void reactOnLogon(UserSessionData userData);
    }

    // =========================================================
    // MEMBERS
    // =========================================================

    private IListener       m_listener;
    private String          m_userName;
    private String          m_password;
    private String          m_errorMessage;
    private UserSessionData m_userData;

    private final RequesterService m_requesterService = new RequesterService();

    private static final String FALLBACK_IMAGE = "/images/Sonia_Delaunay_Diagonale_1970.jpg";
    private static final int    IMAGE_COUNT    = 10;
    private String              m_backgroundImage = null;

    // =========================================================
    // COSTRUTTORE
    // =========================================================

    public LogonUI() {
        System.out.println("[LogonUI] Bean creato (v2 — logon su PostgreSQL)");
    }

    public String getBackgroundImage() {
        if (m_backgroundImage == null) {
            try {
                int n = 1 + new java.util.Random().nextInt(IMAGE_COUNT);
                m_backgroundImage = "/images/" + n + ".jpg";
            } catch (Exception e) {
                m_backgroundImage = FALLBACK_IMAGE;
            }
            System.out.println("[LogonUI] Immagine di sfondo: " + m_backgroundImage);
        }
        return m_backgroundImage;
    }

    @Override
    public String getPageName()                 { return "/Logon.xml"; }
    @Override
    public String getRootExpressionUsedInPage() { return "#{d.LogonUI}"; }

    // =========================================================
    // SETUP
    // =========================================================

    public void prepare(IListener listener) {
        m_listener = listener;
        System.out.println("[LogonUI] prepare() — listener impostato");
    }

    // =========================================================
    // GETTER / SETTER
    // =========================================================

    public String getPassword()          { return m_password; }
    public void setPassword(String v)    { this.m_password = v; }
    public String getUserName()          { return m_userName; }
    public void setUserName(String v)    { this.m_userName = v; }

    // =========================================================
    // AZIONI
    // =========================================================

    public void onLogonAction(ActionEvent event) {
        try {
            System.out.println("[LogonUI] onLogonAction — user: " + m_userName);

            boolean ok = checkLogonData();

            if (!ok) {
                Statusbar.outputError("Logon fallito — verificare le credenziali.");
                return;
            }

            Statusbar.outputSuccess("Accesso effettuato: " + m_userName);

            if (m_listener == null) {
                Statusbar.outputError("Errore interno: listener non impostato");
                return;
            }
            if (m_userData == null) {
                Statusbar.outputError("Errore interno: dati utente non disponibili");
                return;
            }

            m_listener.reactOnLogon(m_userData);

        } catch (Exception e) {
            System.err.println("[LogonUI] ❌ Eccezione in onLogonAction:");
            e.printStackTrace();
            Statusbar.outputError("Errore durante il login: " + e.getMessage());
        }
    }

    /**
     * Apre il popup di cambio password.
     * Il campo m_userName viene passato come reqid iniziale, così l'utente
     * può inserire lo username e poi cliccare "Cambia password" senza riscrivere.
     */
    public void onRequestNewPasswordAction(ActionEvent event) {
        System.out.println("[LogonUI] onRequestNewPasswordAction — apertura popup cambio password");

        final ChangePasswordUI cpUI = new ChangePasswordUI();
        cpUI.prepare(
            m_userName != null ? m_userName : "",
            new ChangePasswordUI.IListener() {
                @Override
                public void reactOnPasswordChanged() {
                    // Chiude il popup — l'utente può ora fare logon con la nuova password
                    closePopup(cpUI);
                    Statusbar.outputSuccess("Password aggiornata. Effettua il logon con la nuova password.");
                }
                @Override
                public void reactOnCancel() {
                    closePopup(cpUI);
                }
            }
        );

        openModalPopup(
            cpUI,
            "Cambio Password",
            440,
            510,
            new ModalPopup.IModalPopupListener() {
                @Override
                public void reactOnPopupClosedByUser() {
                    closePopup(cpUI);
                }
            }
        );
    }

    // =========================================================
    // LOGICA PRIVATA
    // =========================================================

    /**
     * Autentica l'utente contro ticket_requester su PostgreSQL.
     * In caso di successo popola m_userData.
     */
    private boolean checkLogonData() {
        m_errorMessage = null;

        if (m_userName == null || m_userName.trim().isEmpty()) {
            m_errorMessage = "Inserire lo username";
            Statusbar.outputError(m_errorMessage);
            return false;
        }
        if (m_password == null || m_password.trim().isEmpty()) {
            m_errorMessage = "Inserire la password";
            Statusbar.outputError(m_errorMessage);
            return false;
        }

        try {
            RequesterInfo info = m_requesterService.authenticate(m_userName.trim(), m_password);

            if (info == null) {
                m_errorMessage = "Credenziali non valide.";
                Statusbar.outputError(m_errorMessage);
                return false;
            }

            // Costruisce UserSessionData dai dati PostgreSQL
            m_userData = buildUserSessionData(info);
            System.out.println("[LogonUI] ✅ Autenticazione OK: " + info);
            return true;

        } catch (Exception e) {
            System.err.println("[LogonUI] ❌ Eccezione in checkLogonData: " + e.getMessage());
            e.printStackTrace();
            m_errorMessage = "Errore durante il logon: " + e.getMessage();
            Statusbar.outputError(m_errorMessage);
            return false;
        }
    }

    /**
     * Converte RequesterInfo in UserSessionData, mantenendo la struttura
     * che OutestUI/ViewSessionContext già usano.
     */
    private UserSessionData buildUserSessionData(RequesterInfo info) {
        UserSessionData usd = new UserSessionData();
        usd.setUsername   (info.getId_user());      // chiave di login, sempre valorizzata
        usd.setUtente     (info.getNomeOReqid());   // nome visualizzabile
        usd.setKunnr      (info.getKunnr() != null ? info.getKunnr() : "");
        usd.setRichiedente(info.getReqid());         // può essere vuoto per AMS/ADMIN
        usd.setOwnAll     (info.isVedeTutti() ? "ALL" : "");
        usd.setHasError   (false);
        // Salva anche il RequesterInfo completo per uso futuro
        usd.setRequesterInfo(info);
        return usd;
    }
}