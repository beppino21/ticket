package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.util.List;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
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
    private final eone.ticket.service.CommentService commentService = new eone.ticket.service.CommentService();
    private final eone.ticket.service.TicketReferenteService referenteService = new eone.ticket.service.TicketReferenteService();
    private final eone.ticket.service.MailService    mailService  = new eone.ticket.service.MailService();

    private FIXGRIDListBinding<GridDraftItem> m_gridDrafts = new FIXGRIDListBinding<>();
    private GridDraftItem  m_selectedItem;
    private String         m_ticktSapInput;
    private boolean        m_waitingConfirm = false;
    private java.util.List<String> m_mergeWarnings = new java.util.ArrayList<>();
    /** false = elenco DRAFT in attesa di smistamento; true = elenco DRAFT sospesi (parcheggiati). */
    private boolean        m_mostraSospesi = false;

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
        public boolean isSospeso()     { return draft.isSospeso(); }
        public String getSospesoDa()   { return nn(draft.getSospesoDa()); }
        public String getSospesoMotivo() { return nn(draft.getSospesoMotivo()); }
        public String getSospesoAt()   { return draft.getSospesoAtFormatted(); }

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
            List<TicketDraft> list = m_mostraSospesi
                    ? draftService.getAllSospesi()
                    : draftService.getPendingDrafts();
            for (TicketDraft d : list) {
                m_gridDrafts.getItems().add(new GridDraftItem(d));
            }
            Statusbar.outputSuccess(m_mostraSospesi
                    ? list.size() + " ticket DRAFT sospesi"
                    : list.size() + " ticket DRAFT in attesa di smistamento");
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

    /**
     * Pulsante "Fondi in SAP": NON esegue nulla subito — dopo i controlli di
     * base (selezione e numero SAP presenti) chiede conferma con un popup
     * Sì/No. Solo con "Sì" parte il flusso vero (controllaEFondi), che a sua
     * volta può ancora fermarsi su eventuali anomalie da confermare.
     */
    public void mergeDraft(ActionEvent ae) {
        if (m_selectedItem == null) {
            Statusbar.outputWarning("Selezionare un ticket DRAFT dalla lista");
            return;
        }
        if (m_ticktSapInput == null || m_ticktSapInput.trim().isEmpty()) {
            Statusbar.outputWarning("Inserire il numero ticket SAP per procedere alla fusione");
            return;
        }
        final long draftId = m_selectedItem.getId();
        final String ticktKey = m_selectedItem.getTicktKey();
        final String ticktSap = eone.ticket.service.SAPTicketService.normalizeTicktNumber(m_ticktSapInput.trim());

        YESNOPopup ynp = YESNOPopup.createInstance(
            "Conferma fusione",
            "Fondere il ticket " + ticktKey + " (\"" + nn(m_selectedItem.getTitolo()) + "\") " +
            "con il ticket SAP n. " + ticktSap + "?\n\n" +
            "L'operazione sposta commenti e allegati sul ticket SAP e il DRAFT esce dalla lista.",
            new YESNOPopup.IYesNoListener() {
                @Override public void reactOnYes() {
                    if (m_selectedItem == null || m_selectedItem.getId() != draftId) {
                        Statusbar.outputWarning("La selezione è cambiata: ripetere l'operazione.");
                        return;
                    }
                    controllaEFondi();
                }
                @Override public void reactOnNo() {
                    Statusbar.outputMessage("Fusione annullata.");
                }
            });
        ynp.setHeadline("Fusione del ticket DRAFT");
        ynp.setTextAlign("left");
        ynp.getModalPopup().setWidth(520);
        ynp.getModalPopup().setHeight(280);
        ynp.getModalPopup().hideCloseIcon(); // si esce solo premendo esplicitamente Sì o No
    }

    private static String nn(String s) { return s == null ? "" : s; }

    /** Flusso di fusione vero e proprio — chiamato solo dopo il "Sì" del popup di conferma. */
    private void controllaEFondi() {
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

    /** Passa dall'elenco "in attesa di smistamento" all'elenco "sospesi" e viceversa. */
    public void toggleMostraSospesi(ActionEvent ae) {
        m_mostraSospesi = !m_mostraSospesi;
        loadDrafts();
    }

    /** Sospende (parcheggia) il DRAFT selezionato — il DISPATCHER può farlo su qualsiasi DRAFT in attesa. */
    public void sospendiDraft(ActionEvent ae) {
        // Guardia basata sullo stato REALE del draft selezionato (non sul
        // semplice toggle di vista m_mostraSospesi), per non dipendere da
        // eventuali refresh parziali della UI che potrebbero disallineare
        // i due — vedi anche i getter getSelectedIsAttivo/getSelectedIsSospeso.
        if (m_selectedItem == null || m_selectedItem.isSospeso()) {
            Statusbar.outputWarning("Selezionare un ticket DRAFT in attesa di smistamento");
            return;
        }
        long draftId    = m_selectedItem.getId();
        String ticktKey = m_selectedItem.getTicktKey();
        String titolo   = m_selectedItem.getTitolo();
        String kunnr    = m_selectedItem.getKunnr();
        String reqid    = m_selectedItem.getReqid();
        String actorId  = eone.ticket.context.ViewSessionContext.instance().getUsername();
        try {
            TicketDraft aggiornato = draftService.sospendi(draftId, actorId, null, null, null);
            if (aggiornato != null) {
                Statusbar.outputSuccess("DRAFT " + ticktKey + " sospeso");
                inviaNotificaDraftSospesoARichiedente(ticktKey, titolo, kunnr, reqid, actorId);
                loadDrafts();
            } else {
                Statusbar.outputError("Impossibile sospendere: il DRAFT non esiste più o è già stato fuso in SAP.");
            }
        } catch (Exception e) {
            Statusbar.outputError("Errore sospensione DRAFT: " + e.getMessage());
            System.err.println("[DispatcherUI] Errore sospendiDraft: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Pulsante unico Sospendi/Riattiva: etichetta e azione dipendono dallo
     * stato del draft selezionato. Introdotto per sostituire due righe XML
     * a "rendered" alternata (una per "Sospendi", una per "Riattiva") che,
     * in questo ambiente CaptainCasa, si sono dimostrate inaffidabili sotto
     * refresh parziale — comparivano entrambe insieme invece di alternarsi.
     * Un solo componente sempre presente, con testo/azione calcolati qui in
     * Java, non lascia margine per quel tipo di DOM residuo.
     */
    public String getToggleSospendiRiattivaLabel() {
        return (m_selectedItem != null && m_selectedItem.isSospeso()) ? "Riattiva DRAFT" : "Sospendi DRAFT";
    }

    public void toggleSospendiRiattiva(ActionEvent ae) {
        if (m_selectedItem == null) {
            Statusbar.outputWarning("Selezionare un ticket DRAFT dalla lista");
            return;
        }
        if (m_selectedItem.isSospeso()) {
            riattivaDraft(ae);   // reversibile e innocua: nessuna conferma
            return;
        }
        final long draftId = m_selectedItem.getId();
        final String ticktKey = m_selectedItem.getTicktKey();
        YESNOPopup ynp = YESNOPopup.createInstance(
            "Conferma sospensione",
            "Sospendere il ticket " + ticktKey + " (\"" + nn(m_selectedItem.getTitolo()) + "\")?\n\n" +
            "Il DRAFT esce dalla lista di smistamento e il richiedente riceve una notifica. " +
            "Potrà essere riattivato in qualsiasi momento dai DRAFT sospesi.",
            new YESNOPopup.IYesNoListener() {
                @Override public void reactOnYes() {
                    if (m_selectedItem == null || m_selectedItem.getId() != draftId) {
                        Statusbar.outputWarning("La selezione è cambiata: ripetere l'operazione.");
                        return;
                    }
                    sospendiDraft(null);
                }
                @Override public void reactOnNo() {
                    Statusbar.outputMessage("Sospensione annullata.");
                }
            });
        ynp.setHeadline("Sospensione del ticket DRAFT");
        ynp.setTextAlign("left");
        ynp.getModalPopup().setWidth(520);
        ynp.getModalPopup().setHeight(280);
        ynp.getModalPopup().hideCloseIcon();
    }

    /** Riattiva il DRAFT sospeso selezionato — torna tra i DRAFT in attesa di smistamento. */
    public void riattivaDraft(ActionEvent ae) {
        if (m_selectedItem == null || !m_selectedItem.isSospeso()) {
            Statusbar.outputWarning("Selezionare un ticket DRAFT sospeso");
            return;
        }
        long draftId    = m_selectedItem.getId();
        String ticktKey = m_selectedItem.getTicktKey();
        String titolo   = m_selectedItem.getTitolo();
        String kunnr    = m_selectedItem.getKunnr();
        String reqid    = m_selectedItem.getReqid();
        String actorId  = eone.ticket.context.ViewSessionContext.instance().getUsername();
        try {
            TicketDraft aggiornato = draftService.riattiva(draftId, actorId, null, null);
            if (aggiornato != null) {
                Statusbar.outputSuccess("DRAFT " + ticktKey + " riattivato");
                inviaNotificaDraftRiattivatoARichiedente(ticktKey, titolo, kunnr, reqid, actorId);
                loadDrafts();
            } else {
                Statusbar.outputError("Impossibile riattivare: il DRAFT non esiste più o non risulta sospeso.");
            }
        } catch (Exception e) {
            Statusbar.outputError("Errore riattivazione DRAFT: " + e.getMessage());
            System.err.println("[DispatcherUI] Errore riattivaDraft: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Notifica al RICHIEDENTE (o referente, se impostato) che il DISPATCHER ha sospeso il suo DRAFT. */
    private void inviaNotificaDraftSospesoARichiedente(String ticktKey, String titolo, String kunnr, String reqid, String actorId) {
        if (kunnr == null || kunnr.trim().isEmpty() || reqid == null || reqid.trim().isEmpty()) return;
        try {
            RequesterInfo richiedente = requesterService.getByKunnrReqid(kunnr, reqid);
            if (richiedente != null && richiedente.getEmail() != null && !richiedente.getEmail().trim().isEmpty()) {
                mailService.sendNotificaDraftSospeso(richiedente.getEmail(), ticktKey, titolo, actorId, null);
            }
        } catch (Exception e) {
            System.err.println("[DispatcherUI] Errore invio notifica sospensione a richiedente: " + e.getMessage());
        }
        try {
            String reqidReferente = referenteService.getReferente(ticktKey);
            if (reqidReferente != null && !reqidReferente.trim().isEmpty()) {
                RequesterInfo referente = requesterService.getReferenteInfo(kunnr, reqidReferente);
                if (referente != null && referente.getEmail() != null && !referente.getEmail().trim().isEmpty()) {
                    mailService.sendNotificaDraftSospeso(referente.getEmail(), ticktKey, titolo, actorId, null);
                }
            }
        } catch (Exception e) {
            System.err.println("[DispatcherUI] Errore invio notifica sospensione a referente: " + e.getMessage());
        }
    }

    /** Notifica al RICHIEDENTE (o referente, se impostato) che il DISPATCHER ha riattivato il suo DRAFT. */
    private void inviaNotificaDraftRiattivatoARichiedente(String ticktKey, String titolo, String kunnr, String reqid, String actorId) {
        if (kunnr == null || kunnr.trim().isEmpty() || reqid == null || reqid.trim().isEmpty()) return;
        try {
            RequesterInfo richiedente = requesterService.getByKunnrReqid(kunnr, reqid);
            if (richiedente != null && richiedente.getEmail() != null && !richiedente.getEmail().trim().isEmpty()) {
                mailService.sendNotificaDraftRiattivato(richiedente.getEmail(), ticktKey, titolo, actorId);
            }
        } catch (Exception e) {
            System.err.println("[DispatcherUI] Errore invio notifica riattivazione a richiedente: " + e.getMessage());
        }
        try {
            String reqidReferente = referenteService.getReferente(ticktKey);
            if (reqidReferente != null && !reqidReferente.trim().isEmpty()) {
                RequesterInfo referente = requesterService.getReferenteInfo(kunnr, reqidReferente);
                if (referente != null && referente.getEmail() != null && !referente.getEmail().trim().isEmpty()) {
                    mailService.sendNotificaDraftRiattivato(referente.getEmail(), ticktKey, titolo, actorId);
                }
            }
        } catch (Exception e) {
            System.err.println("[DispatcherUI] Errore invio notifica riattivazione a referente: " + e.getMessage());
        }
    }

    private void eseguiFusione(long draftId, String ticktSap) throws Exception {
        String kunnr = m_selectedItem != null ? m_selectedItem.getKunnr() : null;
        String titolo = m_selectedItem != null ? m_selectedItem.getTitolo() : null;

        draftService.mergeDraft(draftId, ticktSap);
        Statusbar.outputSuccess("Fusione completata: DRAFT-" + draftId +
                                " → ticket SAP " + ticktSap);

        inviaNotificaTicketDisponibile(ticktSap, kunnr, titolo);

        loadDrafts();
    }

    /**
     * Notifica "ticket disponibile sul portale" a tutti i soggetti coinvolti
     * dopo la fusione: richiedente, referente cliente (se diverso), AMS
     * assegnato e referente SAP (campo Refer) — con deduplica per indirizzo
     * (non invia due volte alla stessa persona se ricopre più ruoli).
     * Un errore qui non deve far fallire la fusione, già avvenuta: solo loggato.
     */
    private void inviaNotificaTicketDisponibile(String ticktSap, String kunnr, String titolo) {
        try {
            // Allegati del commento iniziale (il più vecchio — getComments()
            // ordina DESC, quindi è l'ultimo della lista), con contenuto
            // reale caricato da DB, non solo i metadati.
            java.util.List<eone.ticket.model.TicketAttachment> allegati = new java.util.ArrayList<>();
            try {
                java.util.List<eone.ticket.model.TicketComment> comments = commentService.getComments(ticktSap);
                if (!comments.isEmpty()) {
                    eone.ticket.model.TicketComment primo = comments.get(comments.size() - 1);
                    for (eone.ticket.model.TicketAttachment meta : primo.getAttachments()) {
                        try {
                            allegati.add(commentService.getAttachmentData(meta.getId()));
                        } catch (Exception e) {
                            System.err.println("[DispatcherUI] Errore caricamento allegato " + meta.getId() + ": " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[DispatcherUI] Errore recupero commento iniziale per allegati: " + e.getMessage());
            }

            java.util.Set<String> giaNotificati = new java.util.HashSet<>();

            // Richiedente
            String reqid = m_selectedItem != null ? m_selectedItem.getReqid() : null;
            if (kunnr != null && reqid != null) {
                try {
                    RequesterInfo richiedente = requesterService.getByKunnrReqid(kunnr, reqid);
                    inviaSeNuovo(richiedente, ticktSap, titolo, allegati, giaNotificati);
                } catch (Exception e) {
                    System.err.println("[DispatcherUI] Errore risoluzione richiedente per notifica: " + e.getMessage());
                }
            }

            // Referente cliente (se impostato)
            try {
                String reqidReferente = referenteService.getReferente(ticktSap);
                if (reqidReferente != null && !reqidReferente.trim().isEmpty() && kunnr != null) {
                    RequesterInfo referente = requesterService.getReferenteInfo(kunnr, reqidReferente);
                    inviaSeNuovo(referente, ticktSap, titolo, allegati, giaNotificati);
                }
            } catch (Exception e) {
                System.err.println("[DispatcherUI] Errore risoluzione referente cliente per notifica: " + e.getMessage());
            }

            // AMS assegnato e Referente SAP — letti dal ticket SAP appena
            // fuso: per come è organizzato il processo, a questo punto
            // devono già essere stati inseriti in SAP.
            try {
                eone.ticket.model.Ticket ticket = sapService.getTicketById(ticktSap, kunnr);
                if (ticket != null) {
                    if (ticket.getAmusr() != null && !ticket.getAmusr().trim().isEmpty()) {
                        RequesterInfo ams = requesterService.getById(ticket.getAmusr().trim());
                        inviaSeNuovo(ams, ticktSap, titolo, allegati, giaNotificati);
                    }
                    if (ticket.getRefer() != null && !ticket.getRefer().trim().isEmpty()) {
                        RequesterInfo referenteSap = requesterService.getById(ticket.getRefer().trim());
                        inviaSeNuovo(referenteSap, ticktSap, titolo, allegati, giaNotificati);
                    }
                }
            } catch (Exception e) {
                System.err.println("[DispatcherUI] Errore recupero AMS/Referente SAP dal ticket fuso: " + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("[DispatcherUI] Errore invio notifica ticket disponibile: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Invia solo se l'indirizzo non ha già ricevuto questa notifica (stessa persona in più ruoli). */
    private void inviaSeNuovo(RequesterInfo destinatario, String ticktSap, String titolo,
                               java.util.List<eone.ticket.model.TicketAttachment> allegati,
                               java.util.Set<String> giaNotificati) {
        if (destinatario == null || destinatario.getEmail() == null || destinatario.getEmail().trim().isEmpty()) return;
        String email = destinatario.getEmail().trim().toLowerCase();
        if (giaNotificati.contains(email)) return;
        giaNotificati.add(email);
        mailService.sendNotificaTicketDisponibile(destinatario.getEmail(), ticktSap, titolo, allegati);
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

    public boolean getMostraSospesi()    { return m_mostraSospesi; }
    public String  getToggleSospesiLabel() { return m_mostraSospesi ? "Torna ai DRAFT in attesa" : "Mostra DRAFT sospesi"; }

    /**
     * Condizioni per il pannello destro, basate sullo stato REALE del draft
     * selezionato (draft.isSospeso()) e non sul toggle di vista
     * m_mostraSospesi, così restano coerenti anche se cambia solo la
     * selezione in griglia senza un giro completo di pagina.
     */
    public boolean getShowCampoSap() { return m_selectedItem != null && !m_selectedItem.isSospeso(); }
    public boolean getShowMerge()    { return m_selectedItem != null && !m_selectedItem.isSospeso() && !m_waitingConfirm; }
}