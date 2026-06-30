package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.ModalPopup;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.workplace.IWorkpageDispatcher;
import org.eclnt.workplace.WorkpageDispatchedPageBean;

import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.Ticket;
import eone.ticket.service.EnrichmentService;
import eone.ticket.service.SAPTicketService;
import eone.ticket.service.SAPTicketService.TicketResponse;

@CCGenClass(expressionBase = "#{d.TicketListUI}")
public class TicketListUI extends WorkpageDispatchedPageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private SAPTicketService  ticketService     = null;
    private EnrichmentService enrichmentService = new EnrichmentService();

    private FIXGRIDListBinding<GridTicketItem> m_gridTickets = new FIXGRIDListBinding<>();

    private String  m_filterKunnr;
    private String  m_filterReqid;
    private String  m_filterFromDate;
    private String  m_filterToDate;

    // Filtri per colonna nella grid (in-memory, su tickets già caricati)
    private String  m_colFilterTickt           = "";
    private String  m_colFilterTitle           = "";
    private String  m_colFilterRstat           = "";
    private String  m_colFilterKunnr           = "";
    private String  m_colFilterReqidNome       = "";
    private String  m_colFilterEncUltimoStato  = "";

    private String  m_statusMessage  = "Pronto per caricare i ticket";
    private Boolean m_hasError       = false;
    private Boolean m_hasTickets     = false;

    private List<Ticket> tickets;

    private String  m_selectedTicketNumber;
    private Boolean m_enableTicketDetail = false;

    // =========================
    // INNER CLASS GRID ITEM
    // =========================

    public class GridTicketItem extends FIXGRIDItem implements Serializable {

        private static final long serialVersionUID = 1L;
        private Ticket ticket;

        public GridTicketItem(Ticket ticket) { this.ticket = ticket; }

        // Campi SAP
        public String getTickt()  { return nn(ticket.getTickt()); }
        public String getTitle()  { return nn(ticket.getTitle()); }
        public String getRstat()  { return nn(ticket.getRstat()); }
        public String getEncRstatLabel()     { return nn(ticket.getEncRstatLabel()); }
        public String getEncRstatColor()     { return ticket.getEncRstatColor(); }
        public String getEncRstatTextColor() { return ticket.getEncRstatTextColor(); }
        public String getRprio()  { return nn(ticket.getRprio()); }
        public String getKunnr()  { return nn(ticket.getKunnr()); }
        public String getCateg()  { return nn(ticket.getCateg()); }
        public String getPrdct()  { return nn(ticket.getPrdct()); }
        public String getModul()  { return nn(ticket.getModul()); }
        public String getAmusr()  { return nn(ticket.getAmusr()); }
        public String getRefer()  { return nn(ticket.getRefer()); }
        public String getFathr()  { return nn(ticket.getFathr()); }
        public String getComch()  { return nn(ticket.getComch()); }
        public String getBukrs()  { return nn(ticket.getBukrs()); }

        /** Codice richiedente SAP (es. "MARIO") */
        public String getReqid()  { return nn(ticket.getReqid()); }

        /**
         * Richiedente arricchito da PostgreSQL.
         * Formato: "MARIO — Mario Rossi" oppure solo "MARIO" se nome non disponibile.
         */
        public String getReqidNome() { return nn(ticket.getEncNomeRichiedente()); }

        public String getErdat() {
            String erdat = ticket.getErdat();
            if (erdat == null || erdat.isEmpty()) return "";
            try {
                if (erdat.contains("Date")) {
                    long ms = Long.parseLong(erdat.substring(6, erdat.length() - 2));
                    LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
                    return String.format("%02d/%02d/%04d", dt.getDayOfMonth(), dt.getMonthValue(), dt.getYear());
                }
                if (erdat.length() >= 8) {
                    return erdat.substring(6, 8) + "/" + erdat.substring(4, 6) + "/" + erdat.substring(0, 4);
                }
            } catch (Exception e) {
                System.err.println("[TicketListUI] Errore parsing data: " + erdat);
            }
            return erdat;
        }

        // Campi arricchimento da PostgreSQL
        public int    getEncNumCommenti()   { return ticket.getEncNumCommenti(); }
        public int    getEncNumAllegati()   { return ticket.getEncNumAllegati(); }
        public String getEncUltimoStato()   { return nn(ticket.getEncUltimoStato()); }
        public String getEncUltimoStatoLabel()     { return nn(ticket.getEncUltimoStatoLabel()); }
        public String getEncUltimoStatoColor()     { return ticket.getEncUltimoStatoColor(); }
        public String getEncUltimoStatoTextColor() { return ticket.getEncUltimoStatoTextColor(); }
        public String getEncUltimaData()    { return nn(ticket.getEncUltimaData()); }
        public String getEncUltimoTesto()   { return nn(ticket.getEncUltimoTesto()); }
        public String getEncSommario()      { return nn(ticket.getEncSommario()); }
        public boolean getEncHasCommenti()  { return ticket.getEncHasCommenti(); }

        public void onRowSelect()  { m_selectedTicketNumber = ticket.getTickt(); m_enableTicketDetail = true; }
        public void onRowExecute() { onRowSelect(); }

        public void onOpenComments(ActionEvent ae) {
            System.out.println("[TicketListUI] onOpenComments ticket=" + ticket.getTickt());
            m_selectedTicketNumber = ticket.getTickt();
            openComments();
        }

        private String nn(String s) { return s != null ? s : ""; }
    }

    // =========================
    // COSTRUTTORE / INIT
    // =========================

    public TicketListUI(IWorkpageDispatcher dispatcher) {
        super(dispatcher);
        this.ticketService = new SAPTicketService();
    }

    public void init() {
        String kunnr  = ViewSessionContext.instance().getKunnr();
        String reqid  = ViewSessionContext.instance().getRichiedente();
        String utente = ViewSessionContext.instance().getUtente();

        if (kunnr != null && !kunnr.isEmpty()) {
            System.out.println("[TicketListUI] init() — Kunnr: " + kunnr + ", Reqid: " + reqid);
            loadTicketsForUser(kunnr, reqid, utente);
        } else {
            System.err.println("[TicketListUI] init() — kunnr mancante, carico tutti i ticket");
            loadAllTickets();
        }
    }

    // =========================
    // POPUP COMMENTI
    // =========================

    public void openComments() {
        if (m_selectedTicketNumber == null || m_selectedTicketNumber.isEmpty()) {
            Statusbar.outputWarning("Selezionare un ticket dalla lista");
            return;
        }
        final CommentUI commentUI = new CommentUI();
        commentUI.init(m_selectedTicketNumber);

        ModalPopup popup = openModalPopup(
            commentUI,
            "Commenti — Ticket " + m_selectedTicketNumber,
            0, 0,
            new ModalPopup.IModalPopupListener() {
                public void reactOnPopupClosedByUser() {
                    closePopup(commentUI);
                    // Refresh leggero: ri-arricchisce i ticket già in memoria
                    // (commenti/allegati/stato aggiornati) senza richiamare SAP.
                    refreshAfterCommentPopup();
                }
            }
        );
        // Apre a schermo intero: più leggibile per il layout a due colonne
        popup.maximize(true);
    }

    /**
     * Ricarica solo l'arricchimento PostgreSQL sui ticket già caricati da SAP,
     * poi riapplica i filtri colonna correnti. Non richiama SAP — veloce e sicuro
     * (DBConfig ora gestisce correttamente il pool con isClosed()).
     */
    private void refreshAfterCommentPopup() {
        if (ticketsEnriched == null || ticketsEnriched.isEmpty()) return;
        try {
            enrichmentService.enrichTickets(ticketsEnriched);
            rebuildGridWithColFilters();
        } catch (Exception e) {
            System.err.println("[TicketListUI] Errore refresh dopo popup commenti: " + e.getMessage());
        }
    }

    // =========================
    // CARICAMENTO
    // =========================

    private List<Ticket> ticketsEnriched; // lista arricchita, usata per il filtro colonna in-memory

    private void populateGrid(List<Ticket> ticketList, String statusMsg) {
        enrichmentService.enrichTickets(ticketList);
        ticketsEnriched = ticketList;
        rebuildGridWithColFilters();
        m_hasError      = false;
        m_hasTickets    = !ticketList.isEmpty();
        m_statusMessage = statusMsg;
        Statusbar.outputSuccess(statusMsg);
    }

    /**
     * Ricostruisce m_gridTickets applicando i filtri per colonna (case-insensitive, "contiene")
     * sulla lista già arricchita — nessuna nuova chiamata SAP/DB.
     */
    private void rebuildGridWithColFilters() {
        m_gridTickets.getItems().clear();
        if (ticketsEnriched == null) return;

        for (Ticket t : ticketsEnriched) {
            if (!matches(t.getTickt(), m_colFilterTickt)) continue;
            if (!matches(t.getTitle(), m_colFilterTitle)) continue;
            if (!matches(t.getRstat(), m_colFilterRstat)) continue;
            if (!matches(t.getKunnr(), m_colFilterKunnr)) continue;
            if (!matches(t.getEncNomeRichiedente(), m_colFilterReqidNome)) continue;
            if (!matches(t.getEncUltimoStato(), m_colFilterEncUltimoStato)) continue;
            m_gridTickets.getItems().add(new GridTicketItem(t));
        }
    }

    private boolean matches(String value, String filter) {
        if (filter == null || filter.trim().isEmpty()) return true;
        if (value == null) return false;
        return value.toLowerCase().contains(filter.trim().toLowerCase());
    }

    /** Triggerato dai campi del gridheader — ricostruisce la grid filtrata */
    public void onColFilter(ActionEvent ae) {
        rebuildGridWithColFilters();
    }

    /** Reset di tutti i filtri colonna */
    public void onResetColFilters(ActionEvent ae) {
        m_colFilterTickt = m_colFilterTitle = m_colFilterRstat = "";
        m_colFilterKunnr = m_colFilterReqidNome = m_colFilterEncUltimoStato = "";
        rebuildGridWithColFilters();
    }

    private void loadTicketsForUser(String kunnr, String reqid, String utente) {
        try {
            tickets = null;
            TicketResponse response = ticketService.getTickets(kunnr, reqid, null, null, null);
            if (response.isSuccess()) {
                tickets = response.getTickets();
                populateGrid(tickets, "Trovati " + tickets.size() + " ticket per " + utente);
            } else { setError("Errore SAP: " + response.getErrorMessage()); }
        } catch (Exception e) { setError("Eccezione: " + e.getMessage()); e.printStackTrace(); }
    }

    private void loadAllTickets() {
        try {
            tickets = null;
            TicketResponse response = ticketService.getAllTickets();
            if (response.isSuccess()) {
                tickets = response.getTickets();
                populateGrid(tickets, "Caricati " + tickets.size() + " ticket");
            } else { setError("Errore SAP: " + response.getErrorMessage()); }
        } catch (Exception e) { setError("Eccezione: " + e.getMessage()); e.printStackTrace(); }
    }

    private void setError(String msg) {
        m_hasError      = true;
        m_hasTickets    = false;
        m_statusMessage = msg;
        Statusbar.outputError(msg);
        System.err.println("[TicketListUI] " + msg);
    }

    // =========================
    // AZIONI PUBBLICHE
    // =========================

    public void searchTickets() {
        try {
            tickets = null;
            TicketResponse response = ticketService.getTickets(
                m_filterKunnr, m_filterReqid, null, m_filterFromDate, m_filterToDate);
            if (response.isSuccess()) {
                tickets = response.getTickets();
                populateGrid(tickets, "Trovati " + tickets.size() + " ticket");
            } else { setError("Errore SAP: " + response.getErrorMessage()); }
        } catch (Exception e) { setError("Eccezione: " + e.getMessage()); e.printStackTrace(); }
    }

    public void clearFilters() {
        m_filterKunnr = m_filterReqid = m_filterFromDate = m_filterToDate = null;
        init();
    }

    public void refreshTickets() { init(); }

    // =========================
    // GETTERS / SETTERS
    // =========================

    @Override public String getPageName()                 { return "/TicketList.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.TicketListUI}"; }

    public FIXGRIDListBinding<GridTicketItem> getGridTickets() { return m_gridTickets; }

    public String getFilterKunnr()              { return m_filterKunnr; }
    public void setFilterKunnr(String v)        { this.m_filterKunnr = v; }
    public String getFilterReqid()              { return m_filterReqid; }
    public void setFilterReqid(String v)        { this.m_filterReqid = v; }
    public String getFilterFromDate()           { return m_filterFromDate; }
    public void setFilterFromDate(String v)     { this.m_filterFromDate = v; }
    public String getFilterToDate()             { return m_filterToDate; }
    public void setFilterToDate(String v)       { this.m_filterToDate = v; }

    public String getColFilterTickt()           { return m_colFilterTickt; }
    public void setColFilterTickt(String v)     { this.m_colFilterTickt = v; }
    public String getColFilterTitle()           { return m_colFilterTitle; }
    public void setColFilterTitle(String v)     { this.m_colFilterTitle = v; }
    public String getColFilterRstat()           { return m_colFilterRstat; }
    public void setColFilterRstat(String v)     { this.m_colFilterRstat = v; }
    public String getColFilterKunnr()           { return m_colFilterKunnr; }
    public void setColFilterKunnr(String v)     { this.m_colFilterKunnr = v; }
    public String getColFilterReqidNome()       { return m_colFilterReqidNome; }
    public void setColFilterReqidNome(String v) { this.m_colFilterReqidNome = v; }
    public String getColFilterEncUltimoStato()        { return m_colFilterEncUltimoStato; }
    public void setColFilterEncUltimoStato(String v)  { this.m_colFilterEncUltimoStato = v; }

    public String  getStatusMessage()           { return m_statusMessage; }
    public void setStatusMessage(String v)      { this.m_statusMessage = v; }
    public Boolean getHasError()                { return m_hasError; }
    public void setHasError(Boolean v)          { this.m_hasError = v; }
    public Boolean getHasTickets()              { return m_hasTickets; }
    public void setHasTickets(Boolean v)        { this.m_hasTickets = v; }

    public String getSelectedTicketNumber()     { return m_selectedTicketNumber; }
    public void setSelectedTicketNumber(String v){ this.m_selectedTicketNumber = v; }
    public Boolean getEnableTicketDetail()      { return m_enableTicketDetail; }
    public void setEnableTicketDetail(Boolean v){ this.m_enableTicketDetail = v; }

    public int getTicketCount()                 { return tickets != null ? tickets.size() : 0; }
}