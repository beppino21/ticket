package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.util.List;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.RequesterInfo;
import eone.ticket.service.SAPTicketService;
import eone.ticket.service.TicketDraftService;
import eone.ticket.service.UserAdminService;

/**
 * UI di amministrazione utenti — un'unica classe per due ruoli distinti:
 *  - AMS_ADMIN: gestisce utenti AMS/DISPATCHER (nessuno scoping cliente)
 *  - REQ_ADMIN: gestisce richiedenti CLIENTE del proprio Kunnr
 *
 * Lo "spostamento ticket da un utente all'altro" non è implementato: il
 * servizio OData SAP dichiara esplicitamente CCListOfTicketsSet come
 * sap:updatable="false" — non c'è modo di scriverci sopra da qui.
 * La cancellazione utente resta invece bloccata se ha ticket attivi a
 * suo carico, per evitare di lasciarli orfani.
 */
@CCGenClass(expressionBase = "#{d.UserAdminUI}")
public class UserAdminUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void reactOnBackToMenu();
    }

    private IListener m_listener;

    private final UserAdminService  adminService  = new UserAdminService();
    private final SAPTicketService  sapService    = new SAPTicketService();
    private final TicketDraftService draftService = new TicketDraftService();

    private boolean m_modeAms; // true = AMS_ADMIN, false = REQ_ADMIN
    private String  m_kunnrAmministrato; // solo REQ_ADMIN

    private FIXGRIDListBinding<UserRow> m_grid = new FIXGRIDListBinding<>();
    private ValidValuesBinding m_ruoloVVS = new ValidValuesBinding();

    // Form di dettaglio (nuovo utente o modifica di quello selezionato)
    private boolean m_isNuovo;
    private String  m_formIdUser;
    private String  m_formNome;
    private String  m_formEmail;
    private String  m_formReqid;      // solo REQ_ADMIN
    private String  m_formRuolo;      // solo AMS_ADMIN (AMS | DISPATCHER)
    private boolean m_formAttivo = true;
    private boolean m_formVedeTutti;
    private boolean m_formPasswordNonScade;
    private String  m_formPasswordScadenzaGiorniStr = "90"; // stringa per il binding col campo — convertita in int al salvataggio
    private String  m_formPassword;   // solo in creazione
    private boolean m_formVisible;

    // Reset password (utenti già esistenti)
    private String  m_resetPasswordValue;
    private boolean m_resetPasswordVisible;

    // Cancellazione — richiede doppia conferma (stesso pattern già in uso
    // per la fusione DRAFT con warning sorpassabili)
    private String m_eliminaWaitingConfirmIdUser;

    // =========================
    // INIT
    // =========================

    public void prepare(IListener listener) {
        this.m_listener = listener;
        ViewSessionContext ctx = ViewSessionContext.instance();
        m_modeAms = ctx.isAmsAdmin();
        m_kunnrAmministrato = ctx.getKunnr();

        if (!m_modeAms) {
            m_ruoloVVS = null; // REQ_ADMIN non sceglie il ruolo, è sempre CLIENTE
        } else {
            m_ruoloVVS = new ValidValuesBinding();
            m_ruoloVVS.addValidValue("AMS", "AMS");
            m_ruoloVVS.addValidValue("DISPATCHER", "DISPATCHER (smistamento)");
        }

        caricaLista();
    }

    private void caricaLista() {
        try {
            List<RequesterInfo> utenti = m_modeAms
                ? adminService.listAmsUsers()
                : adminService.listRichiedenti(m_kunnrAmministrato);
            m_grid.getItems().clear();
            for (RequesterInfo u : utenti) m_grid.getItems().add(new UserRow(u));
            Statusbar.outputSuccess(utenti.size() + (m_modeAms ? " utenti AMS/DISPATCHER" : " richiedenti"));
        } catch (Exception e) {
            Statusbar.outputError("Errore caricamento utenti: " + e.getMessage());
            System.err.println("[UserAdminUI] Errore caricaLista: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================
    // AZIONI
    // =========================

    public void backToMenu(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnBackToMenu();
    }

    public void refresh(ActionEvent ae) {
        caricaLista();
    }

    public void onNuovo(ActionEvent ae) {
        m_isNuovo = true;
        m_formVisible = true;
        m_formIdUser = null;
        m_formNome = null;
        m_formEmail = null;
        m_formReqid = null;
        m_formRuolo = m_modeAms ? "AMS" : null;
        m_formAttivo = true;
        m_formVedeTutti = false;
        m_formPasswordNonScade = false;
        m_formPasswordScadenzaGiorniStr = "90";
        m_formPassword = null;
        m_eliminaWaitingConfirmIdUser = null;
        m_resetPasswordVisible = false;
        m_resetPasswordValue = null;
    }

    public void onAnnullaForm(ActionEvent ae) {
        m_formVisible = false;
        m_eliminaWaitingConfirmIdUser = null;
        m_resetPasswordVisible = false;
    }

    public void onMostraResetPassword(ActionEvent ae) {
        m_resetPasswordVisible = true;
        m_resetPasswordValue = null;
    }

    public void onAnnullaResetPassword(ActionEvent ae) {
        m_resetPasswordVisible = false;
        m_resetPasswordValue = null;
    }

    public void onResetPassword(ActionEvent ae) {
        if (m_formIdUser == null) return;
        if (m_resetPasswordValue == null || m_resetPasswordValue.trim().length() < 6) {
            Statusbar.outputWarning("Indicare una password temporanea di almeno 6 caratteri");
            return;
        }
        try {
            adminService.resetPassword(m_formIdUser, m_resetPasswordValue.trim());
            Statusbar.outputSuccess("Password di " + m_formIdUser +
                " reimpostata — dovrà sceglierne una propria al prossimo accesso.");
            m_resetPasswordVisible = false;
            m_resetPasswordValue = null;
        } catch (Exception e) {
            Statusbar.outputError("Errore reset password: " + e.getMessage());
            System.err.println("[UserAdminUI] Errore onResetPassword: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void onSalva(ActionEvent ae) {
        if (m_formIdUser == null || m_formIdUser.trim().isEmpty()) {
            Statusbar.outputWarning("Lo username è obbligatorio");
            return;
        }
        if (m_formNome == null || m_formNome.trim().isEmpty()) {
            Statusbar.outputWarning("Il nome è obbligatorio");
            return;
        }
        try {
            if (m_isNuovo) {
                if (m_formPassword == null || m_formPassword.trim().length() < 6) {
                    Statusbar.outputWarning("Password iniziale obbligatoria (almeno 6 caratteri) — l'utente dovrà cambiarla al primo accesso");
                    return;
                }
                if (m_modeAms) {
                    String ruolo = (m_formRuolo == null || m_formRuolo.trim().isEmpty()) ? "AMS" : m_formRuolo;
                    adminService.createAmsUser(m_formIdUser.trim(), m_formNome.trim(), m_formEmail, ruolo, m_formPassword);
                } else {
                    if (m_formReqid == null || m_formReqid.trim().isEmpty()) {
                        Statusbar.outputWarning("Il Reqid è obbligatorio per un richiedente");
                        return;
                    }
                    adminService.createRichiedente(m_formIdUser.trim(), m_kunnrAmministrato,
                        m_formReqid.trim(), m_formNome.trim(), m_formEmail, m_formPassword);
                }
                Statusbar.outputSuccess("Utente " + m_formIdUser + " creato — dovrà cambiare la password al primo accesso");
            } else {
                int scadenzaGiorni;
                try {
                    scadenzaGiorni = Integer.parseInt(m_formPasswordScadenzaGiorniStr.trim());
                    if (scadenzaGiorni <= 0) throw new NumberFormatException();
                } catch (Exception nfe) {
                    Statusbar.outputWarning("Giorni di scadenza password non validi — indicare un numero intero positivo");
                    return;
                }
                adminService.updateAnagrafica(m_formIdUser, m_formNome.trim(), m_formEmail, m_formAttivo,
                    m_formVedeTutti, m_formPasswordNonScade, scadenzaGiorni);
                Statusbar.outputSuccess("Utente " + m_formIdUser + " aggiornato — valori riletti dal DB qui sotto");
                caricaLista();
                ripopolaFormDaRiga(m_formIdUser); // rilettura fresca dal DB, form resta aperto per verifica immediata
                return; // non chiudere il form in questo caso
            }
            m_formVisible = false;
            caricaLista();
        } catch (Exception e) {
            Statusbar.outputError("Errore salvataggio: " + e.getMessage());
            System.err.println("[UserAdminUI] Errore onSalva: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Primo click: verifica ticket attivi e chiede conferma. Secondo click: elimina davvero. */
    public void onElimina(ActionEvent ae) {
        if (m_formIdUser == null) return;

        if (!m_formIdUser.equals(m_eliminaWaitingConfirmIdUser)) {
            try {
                UserRow row = trovaRow(m_formIdUser);
                int attivi = m_modeAms
                    ? adminService.contaTicketAttiviAms(m_formIdUser, sapService)
                    : adminService.contaTicketAttiviRichiedente(m_kunnrAmministrato,
                          row != null ? row.getReqid() : m_formReqid, sapService, draftService);
                if (attivi > 0) {
                    Statusbar.outputError("Impossibile eliminare: " + attivi +
                        " ticket attivi ancora a suo carico. Riassegnali prima di procedere.");
                    return;
                }
                m_eliminaWaitingConfirmIdUser = m_formIdUser;
                Statusbar.outputWarning("Nessun ticket attivo — premi di nuovo \"Elimina\" per confermare.");
            } catch (Exception e) {
                Statusbar.outputError("Errore verifica ticket attivi: " + e.getMessage());
                System.err.println("[UserAdminUI] Errore verifica: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        // Conferma ricevuta
        try {
            UserRow row = trovaRow(m_formIdUser);
            int attivi = m_modeAms
                ? adminService.deleteAmsUser(m_formIdUser, sapService)
                : adminService.deleteRichiedente(m_formIdUser, m_kunnrAmministrato,
                      row != null ? row.getReqid() : m_formReqid, sapService, draftService);
            if (attivi > 0) {
                // Race condition: qualcosa è cambiato tra la verifica e la conferma
                Statusbar.outputError("Eliminazione annullata: sono comparsi " + attivi + " ticket attivi nel frattempo.");
            } else {
                Statusbar.outputSuccess("Utente " + m_formIdUser + " eliminato");
                m_formVisible = false;
            }
        } catch (Exception e) {
            Statusbar.outputError("Errore eliminazione: " + e.getMessage());
            System.err.println("[UserAdminUI] Errore onElimina: " + e.getMessage());
            e.printStackTrace();
        } finally {
            m_eliminaWaitingConfirmIdUser = null;
            caricaLista();
        }
    }

    private UserRow trovaRow(String idUser) {
        for (UserRow r : m_grid.getItems()) {
            if (idUser.equals(r.getIdUser())) return r;
        }
        return null;
    }

    /**
     * Ripopola il form con i valori della riga corrispondente, appena
     * ricaricata dal DB (caricaLista già eseguita) — usato dopo il
     * salvataggio per una verifica visiva immediata del round-trip
     * completo (salva → rilegge dal DB → mostra), senza dover ricliccare
     * la riga.
     */
    private void ripopolaFormDaRiga(String idUser) {
        UserRow row = trovaRow(idUser);
        if (row == null) {
            System.err.println("[UserAdminUI] ripopolaFormDaRiga — riga non trovata per " + idUser + " dopo il ricaricamento");
            return;
        }
        row.onRowSelect();
    }

    // =========================
    // GETTERS / SETTERS
    // =========================

    @Override public String getPageName()                 { return "/UserAdmin.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.UserAdminUI}"; }

    public boolean isModeAms() { return m_modeAms; }
    public String  getTitolo()  { return m_modeAms ? "Gestione utenti AMS" : "Gestione richiedenti"; }
    public String  getSottotitolo() {
        return m_modeAms ? "Utenti AMS e DISPATCHER" : "Cliente " + m_kunnrAmministrato;
    }

    public FIXGRIDListBinding<UserRow> getGrid() { return m_grid; }
    public ValidValuesBinding getRuoloVVS()      { return m_ruoloVVS; }

    public boolean isFormVisible()      { return m_formVisible; }
    public boolean getIsNuovo()          { return m_isNuovo; }
    public boolean isShowReqidField()   { return !m_modeAms; }
    public boolean isShowRuoloField()   { return m_modeAms; }
    public boolean isShowPasswordField(){ return m_isNuovo; }
    public boolean isShowEliminaButton(){ return !m_isNuovo; }
    public boolean isShowResetPasswordButton() { return m_formVisible && !m_isNuovo && !m_resetPasswordVisible; }
    public boolean isShowResetPasswordPanel()  { return m_formVisible && !m_isNuovo && m_resetPasswordVisible; }

    public String  getFormIdUser()          { return m_formIdUser; }
    public void    setFormIdUser(String v)  { this.m_formIdUser = v; }
    public boolean isFormIdUserEditable()  { return m_isNuovo; } // non modificabile dopo la creazione

    public String  getFormNome()         { return m_formNome; }
    public void    setFormNome(String v) { this.m_formNome = v; }

    public String  getFormEmail()         { return m_formEmail; }
    public void    setFormEmail(String v) { this.m_formEmail = v; }

    public String  getFormReqid()         { return m_formReqid; }
    public void    setFormReqid(String v) { this.m_formReqid = v; }

    public String  getFormRuolo()         { return m_formRuolo; }
    public void    setFormRuolo(String v) { this.m_formRuolo = v; }

    public boolean isFormAttivo()         { return m_formAttivo; }
    public void    setFormAttivo(boolean v){ this.m_formAttivo = v; }

    public boolean isFormVedeTutti()         { return m_formVedeTutti; }
    public void    setFormVedeTutti(boolean v){ this.m_formVedeTutti = v; }

    public boolean isFormPasswordNonScade()         { return m_formPasswordNonScade; }
    public void    setFormPasswordNonScade(boolean v){ this.m_formPasswordNonScade = v; }

    public String  getFormPasswordScadenzaGiorniStr()      { return m_formPasswordScadenzaGiorniStr; }
    public void    setFormPasswordScadenzaGiorniStr(String v) { this.m_formPasswordScadenzaGiorniStr = v; }

    public String  getFormPassword()         { return m_formPassword; }
    public void    setFormPassword(String v) { this.m_formPassword = v; }

    public boolean isResetPasswordVisible() { return m_resetPasswordVisible; }

    public String  getResetPasswordValue()         { return m_resetPasswordValue; }
    public void    setResetPasswordValue(String v) { this.m_resetPasswordValue = v; }

    // =========================
    // INNER CLASS — RIGA GRIGLIA
    // =========================

    public class UserRow extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final RequesterInfo info;

        public UserRow(RequesterInfo info) { this.info = info; }

        public String  getIdUser() { return info.getId_user(); }
        public String  getNome()   { return info.getNome(); }
        public String  getEmail()  { return info.getEmail(); }
        public String  getRuolo()  { return info.getRuolo(); }
        public String  getReqid()  { return info.getReqid(); }
        public String  getStatoLabel() { return info.isAttivo() ? "Attivo" : "Disattivato"; }
        public String  getStatoColor() { return info.isAttivo() ? "#E8F5E9" : "#FFEBEE"; }

        public void onRowSelect() {
            m_isNuovo = false;
            m_formVisible = true;
            m_formIdUser = info.getId_user();
            m_formNome = info.getNome();
            m_formEmail = info.getEmail();
            m_formReqid = info.getReqid();
            m_formRuolo = info.getRuolo();
            m_formAttivo = info.isAttivo();
            m_formVedeTutti = info.isVedeTutti();
            m_formPasswordNonScade = info.isPasswordNonScade();
            m_formPasswordScadenzaGiorniStr = String.valueOf(info.getPasswordScadenzaGiorni());
            System.out.println("[UserAdminUI] onRowSelect — id_user='" + m_formIdUser +
                "' formAttivo=" + m_formAttivo + " formVedeTutti=" + m_formVedeTutti +
                " formPasswordNonScade=" + m_formPasswordNonScade);
            m_formPassword = null;
            m_eliminaWaitingConfirmIdUser = null;
            m_resetPasswordVisible = false;
            m_resetPasswordValue = null;
        }
    }
}