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

    // =========================
    // SERVIZI
    // =========================

    private SAPTicketService   ticketService     = null;
    private EnrichmentService  enrichmentService = new EnrichmentService();

    // =========================
    // DATI UI
    // =========================

    private FIXGRIDListBinding<GridTicketItem> m_gridTickets = new FIXGRIDListBinding<>();

    private String  m_filterKunnr;
    private String  m_filterReqid;
    private String  m_filterFromDate;
    private String  m_filterToDate;

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

        public GridTicketItem(Ticket ticket) {
            this.ticket = ticket;
        }

        // Campi SAP
        public String getTickt()  { return ticket.getTickt()  != null ? ticket.getTickt()  : ""; }
        public String getTitle()  { return ticket.getTitle()  != null ? ticket.getTitle()  : ""; }
        public String getRstat()  { return ticket.getRstat()  != null ? ticket.getRstat()  : ""; }
        public String getRprio()  { return ticket.getRprio()  != null ? ticket.getRprio()  : ""; }
        public String getKunnr()  { return ticket.getKunnr()  != null ? ticket.getKunnr()  : ""; }
        public String getReqid()  { return ticket.getReqid()  != null ? ticket.getReqid()  : ""; }
        public String getCateg()  { return ticket.getCateg()  != null ? ticket.getCateg()  : ""; }
        public String getPrdct()  { return ticket.getPrdct()  != null ? ticket.getPrdct()  : ""; }
        public String getModul()  { return ticket.getModul()  != null ? ticket.getModul()  : ""; }
        public String getAmusr()  { return ticket.getAmusr()  != null ? ticket.getAmusr()  : ""; }
        public String getRefer()  { return ticket.getRefer()  != null ? ticket.getRefer()  : ""; }
        public String getFathr()  { return ticket.getFathr()  != null ? ticket.getFathr()  : ""; }
        public String getComch()  { return ticket.getComch()  != null ? ticket.getComch()  : ""; }
        public String getBukrs()  { return ticket.getBukrs()  != null ? ticket.getBukrs()  : ""; }

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
        public String getEncUltimoStato()   { return ticket.getEncUltimoStato(); }
        public String getEncUltimaData()    { return ticket.getEncUltimaData(); }
        public String getEncUltimoTesto()   { return ticket.getEncUltimoTesto(); }
        public String getEncSommario()      { return ticket.getEncSommario(); }
        public boolean getEncHasCommenti()  { return ticket.getEncHasCommenti(); }

        /** Colore riga: giallo chiaro se ci sono commenti in attesa dal cliente */
        public String getBackground() {
            if ("Attesa Assistenza".equals(ticket.getEncUltimoStato())) return "#FFF9C4";
            if ("Attesa Cliente".equals(ticket.getEncUltimoStato()))    return "#E8F5E9";
            return "";
        }

        public void onRowSelect() {
            m_selectedTicketNumber = ticket.getTickt();
            m_enableTicketDetail   = true;
        }

        public void onRowExecute() {
            onRowSelect();
        }

        public void onOpenComments(ActionEvent ae) {
            System.out.println("OnOpenComments Action!!! ticket=" + ticket.getTickt());
            m_selectedTicketNumber = ticket.getTickt();
            openComments();
        }
    }

    // =========================
    // COSTRUTTORE
    // =========================

    public TicketListUI(IWorkpageDispatcher dispatcher) {
        super(dispatcher);
        this.ticketService = new SAPTicketService();
    }

    // =========================
    // INIT
    // =========================

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
    // APERTURA POPUP COMMENTI
    // =========================

    public void openComments() {
        if (m_selectedTicketNumber == null || m_selectedTicketNumber.isEmpty()) {
            Statusbar.outputWarning("Selezionare un ticket dalla lista");
            return;
        }

        final CommentUI commentUI = new CommentUI();
        commentUI.init(m_selectedTicketNumber);

        openModalPopup(
            commentUI,
            "Commenti — Ticket " + m_selectedTicketNumber,
            1180,
            840,
            new ModalPopup.IModalPopupListener() {
                public void reactOnPopupClosedByUser() {
                    closePopup(commentUI);
                    // Non ricarichiamo automaticamente: l'utente può cliccare "Aggiorna"
                    // per vedere i dati aggiornati. Questo evita problemi col connection pool.
                }
            }
        );
    }

    // =========================
    // CARICAMENTO — metodo comune
    // =========================

    /**
     * Popola la grid dopo aver ricevuto la lista ticket da SAP.
     * Chiama EnrichmentService per completare i dati da PostgreSQL
     * con una singola query aggregata.
     */
    private void populateGrid(List<Ticket> ticketList, String statusMsg) {
        // Arricchimento da PostgreSQL — una sola query per tutta la lista
        enrichmentService.enrichTickets(ticketList);

        m_gridTickets.getItems().clear();
        for (Ticket t : ticketList) {
            m_gridTickets.getItems().add(new GridTicketItem(t));
        }
        m_hasError      = false;
        m_hasTickets    = !ticketList.isEmpty();
        m_statusMessage = statusMsg;
        Statusbar.outputSuccess(statusMsg);
    }

    private void loadTicketsForUser(String kunnr, String reqid, String utente) {
        try {
            tickets = null;
            TicketResponse response = ticketService.getTickets(kunnr, reqid, null, null, null);
            if (response.isSuccess()) {
                tickets = response.getTickets();
                populateGrid(tickets, "Trovati " + tickets.size() + " ticket per " + utente);
            } else {
                setError("Errore SAP: " + response.getErrorMessage());
            }
        } catch (Exception e) {
            setError("Eccezione: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadAllTickets() {
        try {
            tickets = null;
            TicketResponse response = ticketService.getAllTickets();
            if (response.isSuccess()) {
                tickets = response.getTickets();
                populateGrid(tickets, "Caricati " + tickets.size() + " ticket");
            } else {
                setError("Errore SAP: " + response.getErrorMessage());
            }
        } catch (Exception e) {
            setError("Eccezione: " + e.getMessage());
            e.printStackTrace();
        }
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
            } else {
                setError("Errore SAP: " + response.getErrorMessage());
            }
        } catch (Exception e) {
            setError("Eccezione: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void clearFilters() {
        m_filterKunnr    = null;
        m_filterReqid    = null;
        m_filterFromDate = null;
        m_filterToDate   = null;
        init();
    }

    public void refreshTickets() {
        init();
    }

    // =========================
    // GETTERS / SETTERS
    // =========================

    @Override
    public String getPageName()                 { return "/TicketList.xml"; }
    @Override
    public String getRootExpressionUsedInPage() { return "#{d.TicketListUI}"; }

    public FIXGRIDListBinding<GridTicketItem> getGridTickets() { return m_gridTickets; }

    public String getFilterKunnr()              { return m_filterKunnr; }
    public void setFilterKunnr(String v)        { this.m_filterKunnr = v; }

    public String getFilterReqid()              { return m_filterReqid; }
    public void setFilterReqid(String v)        { this.m_filterReqid = v; }

    public String getFilterFromDate()           { return m_filterFromDate; }
    public void setFilterFromDate(String v)     { this.m_filterFromDate = v; }

    public String getFilterToDate()             { return m_filterToDate; }
    public void setFilterToDate(String v)       { this.m_filterToDate = v; }

    public String getStatusMessage()            { return m_statusMessage; }
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
