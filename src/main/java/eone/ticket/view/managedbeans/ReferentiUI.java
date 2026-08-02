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
import eone.ticket.model.ClienteConfig;
import eone.ticket.model.RequesterInfo;
import eone.ticket.service.ClienteConfigService;
import eone.ticket.service.TicketReferenteService;
import eone.ticket.service.UserAdminService;

/**
 * UI self-service per la creazione/disattivazione dei REFERENTE_CLI.
 *
 * Tre modalità:
 *  - ADMIN: sceglie il kunnr da una combobox dei clienti abilitati (obbligatorio).
 *  - REQ_ADMIN: kunnr fisso = il proprio (scoping automatico, non modificabile).
 *  - CLIENTE con gestisce_referenti=TRUE: kunnr fisso = il proprio, identico a REQ_ADMIN.
 *
 * Modello: richiedente e referente sono persone DISTINTE, ciascuna con un
 * proprio reqid (mai condiviso). Il reqid del referente è sempre = al suo
 * id_user (nessun input separato). Ogni referente fa capo a un richiedente
 * specifico (reqid_richiedente, scelto da combobox tra i richiedenti del
 * kunnr) — un richiedente può anche essere referente di se stesso, ma quello
 * resta un caso particolare gestito direttamente nella combobox "Referente"
 * di NewTicketUI/CommentUI, non qui.
 *
 * Se per quel kunnr non esiste ancora nessun richiedente, non è possibile
 * creare un referente (combobox "Richiedente di riferimento" vuota,
 * salvataggio bloccato con messaggio esplicito).
 *
 * Stesso pattern di UserAdminUI per il resto: cancellazione con guardia
 * (bloccata se il referente ha assegnazioni attive) e doppia conferma.
 */
@CCGenClass(expressionBase = "#{d.ReferentiUI}")
public class ReferentiUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void reactOnBackToMenu();
    }

    private IListener m_listener;

    private final UserAdminService       adminService       = new UserAdminService();
    private final TicketReferenteService referenteService   = new TicketReferenteService();
    private final ClienteConfigService   clienteConfigService = new ClienteConfigService();

    private boolean m_modeAdmin;     // true = ADMIN (kunnr scelto da combobox)
    private boolean m_kunnrFisso;    // true = REQ_ADMIN o CLIENTE (kunnr derivato, non scelto)
    private String  m_kunnrAmministrato;
    private String  m_prefissoCorrente; // prefisso del kunnr corrente, null se non configurato

    private final ValidValuesBinding m_kunnrVVB = new ValidValuesBinding();        // solo ADMIN
    private final ValidValuesBinding m_richiedentiVVB = new ValidValuesBinding();  // richiedenti del kunnr corrente

    private FIXGRIDListBinding<ReferenteRow> m_grid = new FIXGRIDListBinding<>();

    // Form di dettaglio (nuovo referente o modifica di quello selezionato)
    private boolean m_isNuovo;
    private boolean m_formVisible;
    private String  m_formIdUser;           // = reqid del referente; in creazione ricostruito lato server come prefisso_codice
    private String  m_formCodice;           // solo in creazione: la parte digitata dall'utente, senza prefisso
    private String  m_formReqidRichiedente; // a chi fa capo (combobox)
    private String  m_formNome;
    private String  m_formEmail;
    private boolean m_formAttivo = true;
    private String  m_formPassword; // solo in creazione

    private String  m_resetPasswordValue;
    private boolean m_resetPasswordVisible;

    private String m_eliminaWaitingConfirmIdUser;

    public class ReferenteRow extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final RequesterInfo referente;

        public ReferenteRow(RequesterInfo r) { this.referente = r; }

        public String  getIdUser()           { return referente.getId_user(); }
        public String  getReqid()            { return referente.getReqid(); }
        public String  getReqidRichiedente() { return nn(referente.getReqidRichiedente()); }
        public String  getNome()             { return referente.getNome(); }
        public String  getEmail()            { return nn(referente.getEmail()); }
        public boolean getAttivo()           { return referente.isAttivo(); }
        public String  getStatoLabel()       { return referente.isAttivo() ? "Attivo" : "Disattivo"; }

        public void onSeleziona(ActionEvent ae) {
            m_isNuovo = false;
            m_formVisible = true;
            m_formIdUser = referente.getId_user();
            m_formReqidRichiedente = referente.getReqidRichiedente();
            m_formNome   = referente.getNome();
            m_formEmail  = referente.getEmail();
            m_formAttivo = referente.isAttivo();
            m_formPassword = null;
            m_eliminaWaitingConfirmIdUser = null;
            m_resetPasswordVisible = false;
            m_resetPasswordValue = null;
        }

        private String nn(String s) { return s != null ? s : ""; }
    }

    // =========================
    // INIT
    // =========================

    public void prepare(IListener listener) {
        this.m_listener = listener;
        ViewSessionContext ctx = ViewSessionContext.instance();
        m_modeAdmin  = "ADMIN".equalsIgnoreCase(ctx.getRuolo());
        m_kunnrFisso = !m_modeAdmin; // REQ_ADMIN e CLIENTE: kunnr derivato dalla sessione
        m_formVisible = false;

        if (m_modeAdmin) {
            m_kunnrAmministrato = null;
            caricaClientiAbilitati();
        } else {
            m_kunnrAmministrato = ctx.getKunnr();
            caricaPrefisso();
            caricaRichiedentiDisponibili();
            caricaLista();
        }
    }

    /** Prefisso configurato per il kunnr corrente — null se non ancora impostato. */
    private void caricaPrefisso() {
        m_prefissoCorrente = null;
        if (m_kunnrAmministrato == null || m_kunnrAmministrato.trim().isEmpty()) return;
        try {
            m_prefissoCorrente = clienteConfigService.getPrefissoReferente(m_kunnrAmministrato);
        } catch (Exception e) {
            System.err.println("[ReferentiUI] Errore caricamento prefisso: " + e.getMessage());
        }
    }

    /** Solo ADMIN: elenco clienti abilitati per la combobox Kunnr. */
    private void caricaClientiAbilitati() {
        m_kunnrVVB.clear();
        try {
            List<ClienteConfig> tutti = clienteConfigService.listAll();
            int count = 0;
            for (ClienteConfig c : tutti) {
                if (c.isAbilitato()) {
                    String label = c.getNomeCliente() != null && !c.getNomeCliente().trim().isEmpty()
                        ? c.getKunnr() + " — " + c.getNomeCliente() : c.getKunnr();
                    m_kunnrVVB.addValidValue(c.getKunnr(), label);
                    count++;
                }
            }
            if (count == 0) {
                Statusbar.outputWarning("Nessun cliente risulta \"Abilitato\" — verificare in Clienti abilitati (ADMIN).");
            }
        } catch (Exception e) {
            Statusbar.outputError("Errore caricamento clienti abilitati: " + e.getMessage());
            System.err.println("[ReferentiUI] Errore caricamento clienti abilitati: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Elenco dei richiedenti (CLIENTE) già esistenti per il kunnr corrente —
     * popola la combobox "Richiedente di riferimento" (a chi fa capo il
     * nuovo referente), NON il reqid del referente stesso.
     */
    private void caricaRichiedentiDisponibili() {
        m_richiedentiVVB.clear();
        if (m_kunnrAmministrato == null || m_kunnrAmministrato.trim().isEmpty()) return;
        try {
            List<RequesterInfo> richiedenti = adminService.listRichiedenti(m_kunnrAmministrato);
            for (RequesterInfo r : richiedenti) {
                m_richiedentiVVB.addValidValue(r.getReqid(), r.getReqid() + " — " + r.getNomeOReqid());
            }
            if (richiedenti.isEmpty()) {
                Statusbar.outputWarning("Nessun richiedente esistente per il cliente " + m_kunnrAmministrato);
            }
        } catch (Exception e) {
            Statusbar.outputError("Errore caricamento richiedenti: " + e.getMessage());
            System.err.println("[ReferentiUI] Errore caricamento richiedenti: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** ADMIN: cambia il kunnr amministrato (la combobox Kunnr ha flush=true). */
    public void onKunnrAction(ActionEvent ae) {
        m_formVisible = false;
        caricaPrefisso();
        caricaRichiedentiDisponibili();
        caricaLista();
    }

    private void caricaLista() {
        if (m_kunnrAmministrato == null || m_kunnrAmministrato.trim().isEmpty()) {
            m_grid.getItems().clear();
            return;
        }
        try {
            List<RequesterInfo> referenti = adminService.listReferenti(m_kunnrAmministrato);
            m_grid.getItems().clear();
            for (RequesterInfo r : referenti) m_grid.getItems().add(new ReferenteRow(r));
            Statusbar.outputSuccess(referenti.size() + " referenti per il cliente " + m_kunnrAmministrato);
        } catch (Exception e) {
            Statusbar.outputError("Errore caricamento referenti: " + e.getMessage());
            System.err.println("[ReferentiUI] Errore caricaLista: " + e.getMessage());
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
        caricaRichiedentiDisponibili();
        caricaLista();
    }

    public void onNuovo(ActionEvent ae) {
        if (m_kunnrAmministrato == null || m_kunnrAmministrato.trim().isEmpty()) {
            Statusbar.outputWarning("Selezionare un cliente prima di creare un referente");
            return;
        }
        if (m_prefissoCorrente == null || m_prefissoCorrente.trim().isEmpty()) {
            Statusbar.outputError("Nessun prefisso configurato per questo cliente: impostarlo in \"Clienti abilitati\" prima di poter creare un referente.");
            return;
        }
        if (m_richiedentiVVB.size() == 0) {
            Statusbar.outputError("Nessun richiedente esistente per questo cliente: crea prima un richiedente " +
                                   "(funzione riservata al backoffice AMS/ADMIN), poi potrai collegargli un referente.");
            return;
        }
        m_isNuovo = true;
        m_formVisible = true;
        m_formIdUser = null;
        m_formCodice = null;
        // Per il CLIENTE self-service, preseleziona se stesso come
        // richiedente di riferimento — resta comunque modificabile.
        ViewSessionContext ctx = ViewSessionContext.instance();
        m_formReqidRichiedente = ctx.isCliente() ? ctx.getRichiedente() : null;
        m_formNome = null;
        m_formEmail = null;
        m_formAttivo = true;
        m_formPassword = null;
        m_eliminaWaitingConfirmIdUser = null;
        m_resetPasswordVisible = false;
        m_resetPasswordValue = null;
    }

    public void onAnnullaForm(ActionEvent ae) {
        m_formVisible = false;
        m_eliminaWaitingConfirmIdUser = null;
        m_resetPasswordVisible = false;
        m_resetPasswordValue = null;
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
            System.err.println("[ReferentiUI] Errore onResetPassword: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void onSalva(ActionEvent ae) {
        if (m_kunnrAmministrato == null || m_kunnrAmministrato.trim().isEmpty()) {
            Statusbar.outputWarning("Selezionare un cliente prima di creare un referente");
            return;
        }
        if (m_formNome == null || m_formNome.trim().isEmpty()) {
            Statusbar.outputWarning("Il nome è obbligatorio");
            return;
        }
        if (m_formReqidRichiedente == null || m_formReqidRichiedente.trim().isEmpty()) {
            Statusbar.outputWarning("Selezionare a quale richiedente fa capo questo referente");
            return;
        }
        try {
            if (m_isNuovo) {
                // Username SEMPRE ricostruito qui, lato server, come
                // prefisso_codice — non si usa mai m_formIdUser in
                // creazione: anche se un valore arrivasse dal client (un
                // campo disabilitato in UI può comunque essere alterato
                // lato client), verrebbe ignorato. Il prefisso viene letto
                // di nuovo da DB in questo momento, non riusando
                // m_prefissoCorrente calcolato prima — evita che resti
                // valido un prefisso letto a inizio sessione ma nel
                // frattempo cambiato da un ADMIN in un'altra sessione.
                String prefisso;
                try {
                    prefisso = clienteConfigService.getPrefissoReferente(m_kunnrAmministrato);
                } catch (Exception e) {
                    Statusbar.outputError("Errore verifica prefisso: " + e.getMessage());
                    return;
                }
                if (prefisso == null || prefisso.trim().isEmpty()) {
                    Statusbar.outputError("Nessun prefisso configurato per questo cliente: impostarlo in \"Clienti abilitati\".");
                    return;
                }
                if (m_formCodice == null || !m_formCodice.trim().matches("[A-Za-z0-9]{2,15}")) {
                    Statusbar.outputWarning("Codice obbligatorio: solo lettere e cifre, da 2 a 15 caratteri");
                    return;
                }
                String usernameCostruito = prefisso.trim().toLowerCase() + "_" + m_formCodice.trim().toLowerCase();

                if (m_formPassword == null || m_formPassword.trim().length() < 6) {
                    Statusbar.outputWarning("Password iniziale obbligatoria (almeno 6 caratteri) — dovrà cambiarla al primo accesso");
                    return;
                }
                adminService.createReferente(usernameCostruito, m_kunnrAmministrato,
                    m_formReqidRichiedente.trim(), m_formNome.trim(), m_formEmail, m_formPassword);
                Statusbar.outputSuccess("Referente " + usernameCostruito + " creato — dovrà cambiare la password al primo accesso");
            } else {
                adminService.updateAnagrafica(m_formIdUser, m_formNome.trim(), m_formEmail, m_formAttivo,
                    false, false, 90, false);
                // Il richiedente di riferimento è modificabile anche in
                // seguito (es. il referente passa "in carico" a un altro
                // richiedente) — persistito separatamente da updateAnagrafica
                // perché quel metodo è condiviso con richiedenti/utenti AMS.
                adminService.updateReferenteRichiedente(m_formIdUser, m_formReqidRichiedente.trim());
                Statusbar.outputSuccess("Referente " + m_formIdUser + " aggiornato");
            }
            m_formVisible = false;
            m_formCodice = null;
            m_formPassword = null;
            m_resetPasswordVisible = false;
            m_resetPasswordValue = null;
            caricaLista();
        } catch (Exception e) {
            Statusbar.outputError("Errore salvataggio: " + e.getMessage());
            System.err.println("[ReferentiUI] Errore onSalva: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Primo click: verifica assegnazioni attive e chiede conferma. Secondo click: elimina davvero. */
    public void onElimina(ActionEvent ae) {
        if (m_formIdUser == null) return;

        if (!m_formIdUser.equals(m_eliminaWaitingConfirmIdUser)) {
            try {
                int attivi = adminService.contaTicketAttiviReferente(m_formIdUser, referenteService);
                if (attivi > 0) {
                    Statusbar.outputError("Impossibile eliminare: è ancora referente su " + attivi +
                        " ticket. Riassegnali prima di procedere.");
                    return;
                }
                m_eliminaWaitingConfirmIdUser = m_formIdUser;
                Statusbar.outputWarning("Nessuna assegnazione attiva — premi di nuovo \"Elimina\" per confermare.");
            } catch (Exception e) {
                Statusbar.outputError("Errore verifica assegnazioni: " + e.getMessage());
                System.err.println("[ReferentiUI] Errore verifica: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        try {
            int attivi = adminService.deleteReferente(m_formIdUser, m_formIdUser, referenteService);
            if (attivi > 0) {
                Statusbar.outputError("Eliminazione annullata: sono comparse " + attivi + " assegnazioni nel frattempo.");
            } else {
                Statusbar.outputSuccess("Referente " + m_formIdUser + " eliminato");
                m_formVisible = false;
            }
        } catch (Exception e) {
            Statusbar.outputError("Errore eliminazione: " + e.getMessage());
            System.err.println("[ReferentiUI] Errore onElimina: " + e.getMessage());
            e.printStackTrace();
        } finally {
            m_eliminaWaitingConfirmIdUser = null;
            caricaLista();
        }
    }

    // =========================
    // GETTERS / SETTERS
    // =========================

    @Override public String getPageName()                 { return "/Referenti.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.ReferentiUI}"; }

    public boolean getModeAdmin()  { return m_modeAdmin; }
    public boolean getKunnrFisso() { return m_kunnrFisso; }

    public ValidValuesBinding getKunnrVVB()       { return m_kunnrVVB; }
    public ValidValuesBinding getRichiedentiVVB() { return m_richiedentiVVB; }

    public String  getKunnrAmministrato()      { return m_kunnrAmministrato; }
    public void    setKunnrAmministrato(String v) { this.m_kunnrAmministrato = v; }

    public FIXGRIDListBinding<ReferenteRow> getGrid() { return m_grid; }

    public boolean getFormVisible() { return m_formVisible; }
    public boolean getIsNuovo()     { return m_isNuovo; }

    /** Vero solo in creazione, con form aperto — usato per mostrare Prefisso+Codice invece dello username fisso. */
    public boolean isShowUsernameNuovo() { return m_formVisible && m_isNuovo; }
    /** Vero solo in modifica, con form aperto — usato per mostrare lo username fisso invece di Prefisso+Codice. */
    public boolean isShowUsernameEdit()  { return m_formVisible && !m_isNuovo; }

    // Condizioni composte precalcolate in Java invece che nell'XML: le
    // espressioni EL con più "and"/"!" concatenati nell'attributo rendered
    // si sono dimostrate inaffidabili in CC (stesso caso già visto in
    // UserAdminUI) — un singolo getter booleano è l'unico binding di cui
    // fidarsi. Il bug originale di "password/attivo restano visibili dopo
    // il salvataggio" era proprio questo: quelle righe controllavano solo
    // isNuovo/!isNuovo, mai formVisible — quindi restavano visibili anche a
    // form chiuso.
    public boolean isShowPasswordFieldForm() { return m_formVisible && m_isNuovo; }
    public boolean isShowEditFlags()         { return m_formVisible && !m_isNuovo; }
    public boolean isShowResetPasswordButton() { return m_formVisible && !m_isNuovo && !m_resetPasswordVisible; }
    public boolean isShowResetPasswordPanel()  { return m_formVisible && !m_isNuovo && m_resetPasswordVisible; }

    public String  getFormIdUser()        { return m_formIdUser; }
    public void    setFormIdUser(String v) { this.m_formIdUser = v; }

    public String  getFormCodice()        { return m_formCodice; }
    public void    setFormCodice(String v) { this.m_formCodice = v; }

    public String  getPrefissoCorrente() { return m_prefissoCorrente != null ? m_prefissoCorrente : ""; }

    public String  getFormReqidRichiedente()        { return m_formReqidRichiedente; }
    public void    setFormReqidRichiedente(String v) { this.m_formReqidRichiedente = v; }

    public String  getFormNome()        { return m_formNome; }
    public void    setFormNome(String v) { this.m_formNome = v; }

    public String  getFormEmail()        { return m_formEmail; }
    public void    setFormEmail(String v) { this.m_formEmail = v; }

    public boolean getFormAttivo()        { return m_formAttivo; }
    public void    setFormAttivo(boolean v) { this.m_formAttivo = v; }

    public String  getFormPassword()        { return m_formPassword; }
    public void    setFormPassword(String v) { this.m_formPassword = v; }

    public boolean isResetPasswordVisible()        { return m_resetPasswordVisible; }
    public String  getResetPasswordValue()         { return m_resetPasswordValue; }
    public void    setResetPasswordValue(String v) { this.m_resetPasswordValue = v; }
}