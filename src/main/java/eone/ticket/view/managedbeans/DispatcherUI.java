package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.util.List;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.workplace.IWorkpageDispatcher;
import org.eclnt.workplace.WorkpageDispatchedPageBean;

import eone.ticket.model.RequesterInfo;
import eone.ticket.model.TicketDraft;
import eone.ticket.service.RequesterService;
import eone.ticket.service.SAPTicketService;
import eone.ticket.service.TicketDraftService;

/**
 * UI per il DISPATCHER — AMS smistatore che:
 * 1. Vede la lista dei ticket DRAFT in attesa di fusione
 * 2. Seleziona un DRAFT e inserisce il numero ticket SAP creato backoffice
 * 3. Conferma la fusione (migra commenti/allegati dal DRAFT al ticket SAP)
 */
@CCGenClass(expressionBase = "#{d.DispatcherUI}")
public class DispatcherUI extends WorkpageDispatchedPageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void reactOnBackToMenu();
    }

    private IListener m_listener;

    private final TicketDraftService draftService = new TicketDraftService();
    private final SAPTicketService   sapService   = new SAPTicketService();
    private final RequesterService   requesterService = new RequesterService();

    private FIXGRIDListBinding<GridDraftItem> m_gridDrafts = new FIXGRIDListBinding<>();
    private GridDraftItem  m_selectedItem;
    private String         m_ticktSapInput;
    private boolean        m_waitingConfirm = false;
    private java.util.List<String> m_mergeWarnings = new java.util.ArrayList<>();

    /** Wrapper per t:repeat — espone il testo del warning come proprietà */
    public class WarningItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String testo;
        public WarningItem(String t) { this.testo = t; }
        public String getTesto() { return testo; }
    }

    public java.util.List<WarningItem> getWarningItems() {
        java.util.List<WarningItem> list = new java.util.ArrayList<>();
        for (String w : m_mergeWarnings) list.add(new WarningItem(w));
        return list;
    }

    // =========================
    // INNER CLASS — RIGA DRAFT
    // =========================

    public class GridDraftItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final TicketDraft draft;

        public GridDraftItem(TicketDraft d) { this.draft = d; }

        public long   getId()          { return draft.getId(); }
        public String getTicktKey()    { return draft.getTicktKey(); }
        public String getKunnr()       { return nn(draft.getKunnr()); }
        public String getReqid()       { return nn(draft.getReqid()); }
        public String getIdUser()      { return nn(draft.getIdUser()); }

        /** id_user + nome se disponibile in ticket_user */
        public String getIdUserConNome() {
            String id = nn(draft.getIdUser());
            try {
                RequesterInfo info = requesterService.getById(draft.getIdUser());
                if (info != null && info.getNome() != null && !info.getNome().trim().isEmpty()) {
                    return id + " \u2014 " + info.getNome().trim();
                }
            } catch (Exception ignored) {}
            return id;
        }
        public String getTitolo()      { return nn(draft.getTitolo()); }
        public String getCreatedAt()   { return draft.getCreatedAtFormatted(); }

        public String getRowBackground() {
            return (m_selectedItem != null && m_selectedItem.getId() == draft.getId())
                ? "#D6E8FB" : "#FFFFFF";
        }

        @Override public void onRowSelect() {
            m_selectedItem    = this;
            m_ticktSapInput   = null;
            m_waitingConfirm  = false;
            m_mergeWarnings.clear();
        }
        @Override public void onRowExecute() { onRowSelect(); }

        private String nn(String s) { return s != null ? s : ""; }
    }

    // =========================
    // COSTRUTTORE / INIT
    // =========================

    public DispatcherUI(IWorkpageDispatcher dispatcher) {
        super(dispatcher);
    }

    public void prepare(IListener listener) {
        this.m_listener = listener;
    }

    public void init() {
        loadDrafts();
    }

    private void loadDrafts() {
        m_gridDrafts.getItems().clear();
        m_selectedItem     = null;
        m_ticktSapInput    = null;
        m_waitingConfirm   = false;
        m_mergeWarnings.clear();
        try {
            List<TicketDraft> list = draftService.getPendingDrafts();
            for (TicketDraft d : list) {
                m_gridDrafts.getItems().add(new GridDraftItem(d));
            }
            Statusbar.outputSuccess(list.size() + " ticket DRAFT in attesa di smistamento");
        } catch (Exception e) {
            Statusbar.outputError("Errore caricamento DRAFT: " + e.getMessage());
            System.err.println("[DispatcherUI] Errore loadDrafts: " + e.getMessage());
        }
    }

    // =========================
    // AZIONI
    // =========================

    public void backToMenu(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnBackToMenu();
    }

    public void refresh(ActionEvent ae) {
        loadDrafts();
    }

    /**
     * Seleziona un DRAFT per chiave (es. "DRAFT-42") — usato dal deep link
     * nella mail di notifica al DISPATCHER. Se non lo trova (es. già
     * smistato da un collega nel frattempo), avvisa senza bloccare la vista.
     */
    public void selectDraft(String ticktKey) {
        if (ticktKey == null || ticktKey.trim().isEmpty()) return;
        String target = ticktKey.trim();
        for (GridDraftItem item : m_gridDrafts.getItems()) {
            if (target.equals(item.getTicktKey())) {
                item.onRowSelect();
                return;
            }
        }
        Statusbar.outputWarning("Il DRAFT " + target + " non è (più) in attesa di smistamento " +
                                "— probabilmente è già stato gestito.");
    }

    public void mergeDraft(ActionEvent ae) {
        if (m_selectedItem == null) {
            Statusbar.outputWarning("Selezionare un ticket DRAFT dalla lista");
            return;
        }
        if (m_ticktSapInput == null || m_ticktSapInput.trim().isEmpty()) {
            Statusbar.outputWarning("Inserire il numero ticket SAP per procedere alla fusione");
            return;
        }

        long draftId = m_selectedItem.getId();
        // Normalizza SUBITO (formato realmente usato dall'OData SAP — senza
        // zero-padding, es. "0000000003" -> "3", vedi commento in
        // SAPTicketService.normalizeTicktNumber) così il valore usato per la
        // verifica SAP, i messaggi e la scrittura su DB (tickt_sap +
        // migrazione commenti) è sempre lo stesso, coerente col formato
        // realmente restituito da SAP — altrimenti si rischia di salvare un
        // formato nel DRAFT diverso da quello con cui il ticket è effettivamente
        // indicizzato altrove nell'app: commenti migrati non più visibili
        // sotto il ticket giusto, deep-link email rotto, ecc.
        String ticktSap = eone.ticket.service.SAPTicketService.normalizeTicktNumber(m_ticktSapInput.trim());
        m_ticktSapInput = ticktSap; // riflette il valore normalizzato anche in UI

        try {
            // Raccoglie tutti i warning "sorpassabili" (commenti, richiedente, data)
            m_mergeWarnings = draftService.checkMergeWarnings(draftId, ticktSap, sapService);
            if (!m_mergeWarnings.isEmpty()) {
                m_waitingConfirm = true;
                Statusbar.outputWarning(m_mergeWarnings.size() + " anomalia/e rilevata/e — " +
                    "leggere i dettagli e confermare se si vuole procedere.");
                return;
            }
            // Nessun warning — procede direttamente
            eseguiFusione(draftId, ticktSap);

        } catch (eone.ticket.service.TicketSapNotFoundException e) {
            // Blocco vero: senza un ticket SAP esistente non c'è nulla con cui
            // fondere il DRAFT. Niente "conferma e procedi" per questo caso —
            // altrimenti il DRAFT verrebbe marcato MERGED verso un ticket
            // fantasma e sparirebbe dalla lista senza che la fusione sia
            // avvenuta davvero.
            m_waitingConfirm = false;
            m_mergeWarnings.clear();
            Statusbar.outputError(e.getMessage());
            System.err.println("[DispatcherUI] Ticket SAP non trovato: " + e.getMessage());
        } catch (Exception e) {
            Statusbar.outputError("Errore controllo ticket SAP: " + e.getMessage());
            System.err.println("[DispatcherUI] Errore controllo: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Chiamato solo dopo conferma esplicita del DISPATCHER */
    public void mergeDraftConfirmed(ActionEvent ae) {
        if (m_selectedItem == null || m_ticktSapInput == null || !m_waitingConfirm) return;
        try {
            eseguiFusione(m_selectedItem.getId(), m_ticktSapInput.trim());
        } catch (Exception e) {
            Statusbar.outputError("Errore fusione: " + e.getMessage());
            System.err.println("[DispatcherUI] Errore fusione confermata: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Annulla la conferma in sospeso */
    public void annullaConferma(ActionEvent ae) {
        m_waitingConfirm   = false;
        m_mergeWarnings.clear();
        Statusbar.outputSuccess("Fusione annullata.");
    }

    private void eseguiFusione(long draftId, String ticktSap) throws Exception {
        draftService.mergeDraft(draftId, ticktSap);
        Statusbar.outputSuccess("Fusione completata: DRAFT-" + draftId +
                                " → ticket SAP " + ticktSap);
        loadDrafts();
    }

    // =========================
    // GETTERS / SETTERS
    // =========================

    @Override public String getPageName()                 { return "/Dispatcher.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.DispatcherUI}"; }

    public FIXGRIDListBinding<GridDraftItem> getGridDrafts() { return m_gridDrafts; }

    public boolean getHasSelected()       { return m_selectedItem != null; }
    public String  getSelectedTicktKey()  { return m_selectedItem != null ? m_selectedItem.getTicktKey() : ""; }
    public String  getSelectedTitolo()    { return m_selectedItem != null ? m_selectedItem.getTitolo() : ""; }
    public String  getSelectedKunnr()     { return m_selectedItem != null ? m_selectedItem.getKunnr() : ""; }
    public String  getSelectedIdUser()    { return m_selectedItem != null ? m_selectedItem.getIdUser() : ""; }

    public boolean getWaitingConfirm()    { return m_waitingConfirm; }
    public boolean getNotWaitingConfirm() { return !m_waitingConfirm; }
    public String  getWarningMessage() {
        if (!m_waitingConfirm || m_mergeWarnings.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < m_mergeWarnings.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(m_mergeWarnings.get(i));
        }
        return sb.toString();
    }

    public String getTicktSapInput()          { return m_ticktSapInput; }
    public void setTicktSapInput(String v)    { this.m_ticktSapInput = v; }

    public int getDraftCount() { return m_gridDrafts.getItems().size(); }
}