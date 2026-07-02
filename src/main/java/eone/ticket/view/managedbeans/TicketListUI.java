package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.workplace.IWorkpageDispatcher;
import org.eclnt.workplace.WorkpageDispatchedPageBean;

import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.RequesterInfo;
import eone.ticket.model.Ticket;
import eone.ticket.model.TicketDraft;
import eone.ticket.model.TicketSummary;
import eone.ticket.service.EnrichmentService;
import eone.ticket.service.RequesterService;
import eone.ticket.service.SAPTicketService;
import eone.ticket.service.SAPTicketService.TicketResponse;
import eone.ticket.service.TicketDraftService;

@CCGenClass(expressionBase = "#{d.TicketListUI}")
public class TicketListUI extends WorkpageDispatchedPageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Listener per tornare al menu principale (OutestUI) */
    public interface IListener extends Serializable {
        void reactOnBackToMenu();
        default void reactOnSummaryUpdated(TicketSummary summary) {} // opzionale
    }

    private IListener m_listener;
    private CommentUI m_commentUI            = new CommentUI();
    private boolean   m_commentsPanelVisible = false;

    private SAPTicketService  ticketService     = null;
    private EnrichmentService enrichmentService = new EnrichmentService();
    private TicketDraftService draftService     = new TicketDraftService();
    private RequesterService  requesterService  = new RequesterService();

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
    private Ticket  m_selectedTicketObj;
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
        public String getEncUltimaData()     { return nn(ticket.getEncUltimaData()); }
        public String getEncUltimaDataSort() { return nn(ticket.getEncUltimaDataSort()); }
        public String getEncUltimoTesto()    { return nn(ticket.getEncUltimoTesto()); }
        public String getEncSommario()       { return nn(ticket.getEncSommario()); }
        public boolean getEncHasCommenti()   { return ticket.getEncHasCommenti(); }

        /**
         * Label bottone Commenti:
         * - nessun commento → "Commenti"
         * - commenti presenti → data+ora ultimo commento (es. "02/07/2026 14:35")
         */
        public String getCommentiLabel() {
            if (!ticket.getEncHasCommenti()) return "Commenti";
            String data = ticket.getEncUltimaData();
            return (data != null && !data.isEmpty()) ? data : "Commenti";
        }

        // =========================
        // COLONNA GIORNI
        // =========================

        /**
         * Numero di giorni tra la creazione del ticket (erdat SAP) e
         * la data dell'ultimo commento (encUltimaDataSort = yyyyMMddHHmm).
         * Restituisce -1 se i dati non sono disponibili.
         */
        /**
         * Giorni di inattività: da oggi all'ultimo commento (se presente),
         * oppure da oggi alla data di creazione del ticket (se nessun commento).
         * Misura quanti giorni sono passati senza interazione.
         */
        public int getGiorniUltimaAttivita() {
            java.time.LocalDate riferimento = null;

            String sort = ticket.getEncUltimaDataSort();
            if (sort != null && sort.length() >= 8) {
                // Ha commenti: usa la data dell'ultimo
                try {
                    riferimento = java.time.LocalDate.of(
                        Integer.parseInt(sort.substring(0, 4)),
                        Integer.parseInt(sort.substring(4, 6)),
                        Integer.parseInt(sort.substring(6, 8)));
                } catch (Exception ignored) {}
            }

            if (riferimento == null) {
                // Nessun commento: usa la data di creazione del ticket
                riferimento = parseSapDate(ticket.getErdat());
            }

            if (riferimento == null) return -1;
            return (int) Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(
                riferimento, java.time.LocalDate.now()));
        }

        public String getGiorniLabel() {
            int g = getGiorniUltimaAttivita();
            if (g < 0) return "";
            return String.format("%04d", Math.min(g, 9999));
        }

        /** Valore sort numerico a 5 cifre — usato da sortvalue nel XML */
        public String getGiorniSort() {
            int g = getGiorniUltimaAttivita();
            return g < 0 ? "" : String.format("%05d", g);
        }

        /**
         * Colore background cella Giorni — soglie per priorità:
         * HI: <= 1 giorno verde, poi rosso
         * MD: <= 3 verde, <= 7 giallo, oltre rosso
         * LO: <= 5 verde, <= 10 giallo, oltre rosso
         * Colori pallidi per non competere con i badge stato.
         */
        public String getGiorniBackground() {
            int g = getGiorniUltimaAttivita();
            if (g < 0) return "";
            String prio = nn(ticket.getRprio()).toUpperCase();
            boolean verde, giallo;
            switch (prio) {
                case "HI":
                    verde  = g <= 1;
                    giallo = false; // subito rosso dopo 1
                    break;
                case "MD":
                    verde  = g <= 3;
                    giallo = g <= 7;
                    break;
                default: // LO e qualsiasi altro
                    verde  = g <= 5;
                    giallo = g <= 10;
                    break;
            }
            if (verde)  return "#C8E6C9"; // verde pallido
            if (giallo) return "#FFF9C4"; // giallo pallido
            return "#FFCDD2";             // rosso pallido
        }

        /** Giorni di vita del ticket: da erdat a oggi. -1 se CLO o dati mancanti. */
        public int getGiorniVita() {
            if ("CLO".equalsIgnoreCase(ticket.getRstat())) return -1;
            String erdat = ticket.getErdat();
            if (erdat == null || erdat.isEmpty()) return -1;
            try {
                java.time.LocalDate dataCreazione = parseSapDate(erdat);
                if (dataCreazione == null) return -1;
                return (int) java.time.temporal.ChronoUnit.DAYS.between(
                    dataCreazione, java.time.LocalDate.now());
            } catch (Exception e) { return -1; }
        }

        public String getGiorniVitaLabel() {
            int g = getGiorniVita();
            if (g < 0) return "-";
            return String.format("%04d", Math.min(g, 9999));
        }

        /** Valore sort numerico a 5 cifre — usato da sortvalue nel XML */
        public String getGiorniVitaSort() {
            int g = getGiorniVita();
            return g < 0 ? "" : String.format("%05d", g);
        }

        /** Stesso schema cromatico della colonna Giorni attività */
        public String getGiorniVitaBackground() {
            int g = getGiorniVita();
            if (g < 0) return "";
            String prio = nn(ticket.getRprio()).toUpperCase();
            boolean verde, giallo;
            switch (prio) {
                case "HI": verde = g <= 1;  giallo = false; break;
                case "MD": verde = g <= 3;  giallo = g <= 7; break;
                default:   verde = g <= 5;  giallo = g <= 10; break;
            }
            if (verde)  return "#C8E6C9";
            if (giallo) return "#FFF9C4";
            return "#FFCDD2";
        }

        private java.time.LocalDate parseSapDate(String erdat) {
            if (erdat == null || erdat.isEmpty()) return null;
            try {
                if (erdat.contains("Date")) {
                    long ms = Long.parseLong(erdat.replaceAll("[^0-9]", ""));
                    return java.time.Instant.ofEpochMilli(ms)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                }
                if (erdat.length() >= 8) {
                    return java.time.LocalDate.of(
                        Integer.parseInt(erdat.substring(0, 4)),
                        Integer.parseInt(erdat.substring(4, 6)),
                        Integer.parseInt(erdat.substring(6, 8)));
                }
            } catch (Exception ignored) {}
            return null;
        }

        public void onRowSelect()  { m_selectedTicketNumber = ticket.getTickt(); m_selectedTicketObj = ticket; m_enableTicketDetail = true; }

        /** Doppio click su qualsiasi colonna della riga — CC lo chiama in automatico (nessun singleclickexecute su g_51). */
        public void onRowExecute() {
            onRowSelect();
            openComments();
        }

        public void onOpenComments(ActionEvent ae) {
            System.out.println("[TicketListUI] onOpenComments ticket=" + ticket.getTickt());
            m_selectedTicketNumber = ticket.getTickt();
            m_selectedTicketObj    = ticket;
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
        this.m_commentUI.setCloseListener(new CommentUI.ICloseListener() {
            public void reactOnCloseComments() {
                hideCommentsPanel();
            }
        });
    }

    public void prepare(IListener listener) {
        this.m_listener = listener;
    }

    public void backToMenu(ActionEvent ae) {
        System.out.println("[TicketListUI] backToMenu() chiamato, listener=" + (m_listener != null));
        if (m_listener != null) {
            m_listener.reactOnBackToMenu();
        }
    }

    public void init() { init(false); }

    public void init(boolean archivio) {
        m_archivio = archivio;
        ViewSessionContext ctx = ViewSessionContext.instance();
        String kunnr      = ctx.getKunnr();
        String reqid      = ctx.getRichiedente();
        String utente     = ctx.getUtente();
        boolean vedeTutti = "ALL".equalsIgnoreCase(ctx.getOwnAll());
        // Lista normale esclude CLO, archivio carica solo CLO
        String rstatFilter = archivio ? "CLO" : "ne:CLO";

        if (kunnr != null && !kunnr.isEmpty()) {
            String reqidPerFiltro = vedeTutti ? null : reqid;
            System.out.println("[TicketListUI] init() — Kunnr: " + kunnr + ", Reqid: " + reqidPerFiltro +
                               (archivio ? " [ARCHIVIO]" : "") + (vedeTutti ? " (vede_tutti=true)" : ""));
            loadTicketsForUser(kunnr, reqidPerFiltro, utente, rstatFilter);
        } else if (ctx.isAms() && ctx.getUsername() != null && !ctx.getUsername().trim().isEmpty()) {
            if (vedeTutti) {
                System.out.println("[TicketListUI] init() — AMS: " + ctx.getUsername() + " (vede_tutti=true)");
                loadAllTickets(rstatFilter);
            } else {
                System.out.println("[TicketListUI] init() — AMS: " + ctx.getUsername() + ", filtro per amusr");
                loadTicketsForAms(ctx.getUsername(), rstatFilter);
            }
        } else {
            System.err.println("[TicketListUI] init() — kunnr mancante, carico tutti i ticket");
            loadAllTickets(rstatFilter);
        }
    }

    // =========================
    // PANNELLO COMMENTI INLINE
    // =========================

    public void openComments() {
        if (m_selectedTicketNumber == null || m_selectedTicketNumber.isEmpty()) {
            Statusbar.outputWarning("Selezionare un ticket dalla lista");
            return;
        }
        String kunnr = m_selectedTicketObj != null ? m_selectedTicketObj.getKunnr() : "";
        String reqid = m_selectedTicketObj != null ? m_selectedTicketObj.getReqid() : "";
        String amusr = m_selectedTicketObj != null ? m_selectedTicketObj.getAmusr() : "";
        m_commentUI.init(m_selectedTicketNumber, kunnr, reqid, amusr);
        m_commentsPanelVisible = true;
    }

    public void closeComments(ActionEvent ae) {
        hideCommentsPanel();
    }

    /**
     * Logica di chiusura effettiva del pannello, condivisa tra:
     * - closeComments(ActionEvent) — invocabile direttamente da TicketList.xml
     * - il listener ICloseListener registrato su m_commentUI nel costruttore,
     *   invocato dal bottone "Chiudi" dentro CommentDialog.xml (che richiama
     *   solo #{d.CommentUI.onClose}, mai un bean esterno)
     */
    private void hideCommentsPanel() {
        m_commentsPanelVisible = false;
        // Refresh leggero: ri-arricchisce i ticket già in memoria
        // (commenti/allegati/stato aggiornati) senza richiamare SAP.
        refreshAfterCommentPopup();
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
            rebuildGridWithColFilters(); // include i DRAFT dalla draftsCache automaticamente
        } catch (Exception e) {
            System.err.println("[TicketListUI] Errore refresh dopo popup commenti: " + e.getMessage());
        }
    }

    // =========================
    // CARICAMENTO
    // =========================

    private List<Ticket> ticketsEnriched; // ticket SAP arricchiti, usati per il filtro colonna
    private List<Ticket> draftsCache;     // ticket DRAFT locali — persistono tra i rebuild della grid
    private boolean      m_archivio = false; // true = vista archivio (solo CLO)

    private void populateGrid(List<Ticket> ticketList, String statusMsg) {
        // Filtro client-side: SAP non supporta 'ne' come operatore OData
        // quindi filtriamo qui dopo aver ricevuto tutti i ticket.
        if (m_archivio) {
            // Archivio: tieni solo CLO e CAN
            ticketList = ticketList.stream()
                .filter(t -> "CLO".equals(t.getRstat()) || "CAN".equals(t.getRstat()))
                .collect(java.util.stream.Collectors.toList());
        } else {
            // Lista operativa: escludi CLO e CAN
            ticketList = ticketList.stream()
                .filter(t -> !"CLO".equals(t.getRstat()) && !"CAN".equals(t.getRstat()))
                .collect(java.util.stream.Collectors.toList());
        }

        enrichmentService.enrichTickets(ticketList);
        ticketsEnriched = ticketList;

        // DRAFT in cache solo per la lista operativa, non per l'archivio
        draftsCache = null;
        if (!m_archivio) {
            ViewSessionContext ctx = ViewSessionContext.instance();
            if (ctx.isCliente() && ctx.getKunnr() != null && !ctx.getKunnr().isEmpty()) {
                draftsCache = buildDraftTickets(ctx.getKunnr(), ctx.getRichiedente());
            }
        }

        rebuildGridWithColFilters();
        m_hasError      = false;
        m_hasTickets    = !ticketList.isEmpty();
        m_statusMessage = statusMsg;
        Statusbar.outputSuccess(statusMsg);

        // Aggiorna il summary SOLO dalla lista operativa (non dall'archivio)
        // per mantenere il riepilogo stabile e coerente nel menu.
        if (!m_archivio && m_listener != null) {
            int draftCount = draftsCache != null ? draftsCache.size() : 0;
            m_listener.reactOnSummaryUpdated(TicketSummary.build(ticketsEnriched, draftCount));
        }
    }

    /**
     * Costruisce la lista di Ticket "virtuali" dai DRAFT locali del richiedente.
     * Restituisce la lista invece di aggiungerla direttamente alla grid,
     * così viene memorizzata in draftsCache e riusata da ogni rebuild.
     */
    private List<Ticket> buildDraftTickets(String kunnr, String reqid) {
        List<Ticket> result = new java.util.ArrayList<>();
        try {
            List<TicketDraft> drafts = draftService.getDraftsByRequester(kunnr, reqid);
            for (TicketDraft d : drafts) {
                if (!d.isDraft()) continue;
                Ticket t = new Ticket();
                t.setTickt (d.getTicktKey());
                t.setTitle (d.getTitolo());
                t.setRstat ("DRAFT");
                t.setKunnr (stripLeadingZeros(d.getKunnr()));
                t.setReqid (d.getReqid());
                // Risolve nome richiedente da ticket_user (kunnr+reqid)
                String reqidDraft = d.getReqid();
                String nomeRichiedente = reqidDraft != null ? reqidDraft : "";
                try {
                    if (d.getKunnr() != null && reqidDraft != null) {
                        RequesterInfo info = requesterService.getByKunnrReqid(d.getKunnr(), reqidDraft);
                        if (info != null && info.getNome() != null && !info.getNome().trim().isEmpty()) {
                            nomeRichiedente = reqidDraft + " \u2014 " + info.getNome().trim();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[TicketListUI] Errore risoluzione nome DRAFT: " + e.getMessage());
                }
                t.setEncNomeRichiedente(nomeRichiedente);
                if (d.getCreatedAt() != null) {
                    t.setErdat(String.format("%04d%02d%02d",
                        d.getCreatedAt().getYear(),
                        d.getCreatedAt().getMonthValue(),
                        d.getCreatedAt().getDayOfMonth()));
                }
                t.setEncRstatLabel    ("DRAFT - In attesa di smistamento");
                t.setEncRstatColor    ("#FF8F00");
                t.setEncRstatTextColor("#FFFFFF");
                result.add(t);
            }
            System.out.println("[TicketListUI] DRAFT in cache: " + result.size());
        } catch (Exception e) {
            System.err.println("[TicketListUI] Errore caricamento DRAFT: " + e.getMessage());
        }
        return result;
    }

    /** Rimuove gli zeri iniziali da un codice cliente (es. "0003000004" → "3000004") */
    private String stripLeadingZeros(String s) {
        if (s == null) return "";
        String stripped = s.replaceFirst("^0+(?!$)", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    /**
     * Ricostruisce m_gridTickets applicando i filtri per colonna (case-insensitive, "contiene")
     * sulla lista già arricchita — nessuna nuova chiamata SAP/DB.
     */
    private void rebuildGridWithColFilters() {
        m_gridTickets.getItems().clear();

        // DRAFT in cima — sempre, applicando gli stessi filtri colonna
        if (draftsCache != null) {
            for (Ticket t : draftsCache) {
                if (!matches(t.getTickt(), m_colFilterTickt)) continue;
                if (!matches(t.getTitle(), m_colFilterTitle)) continue;
                if (!matches(t.getRstat(), m_colFilterRstat)) continue;
                if (!matches(t.getKunnr(), m_colFilterKunnr)) continue;
                m_gridTickets.getItems().add(new GridTicketItem(t));
            }
        }

        // Ticket SAP
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

    private void loadTicketsForUser(String kunnr, String reqid, String utente, String rstatFilter) {
        try {
            tickets = null;
            TicketResponse response = ticketService.getTickets(kunnr, reqid, null, null, null, rstatFilter);
            if (response.isSuccess()) {
                tickets = response.getTickets();
                populateGrid(tickets, "Trovati " + tickets.size() + " ticket per " + utente);
            } else { setError("Errore SAP: " + response.getErrorMessage()); }
        } catch (Exception e) { setError("Eccezione: " + e.getMessage()); e.printStackTrace(); }
    }

    private void loadAllTickets(String rstatFilter) {
        try {
            tickets = null;
            TicketResponse response = ticketService.getTickets(null, null, null, null, null, rstatFilter);
            if (response.isSuccess()) {
                tickets = response.getTickets();
                populateGrid(tickets, "Caricati " + tickets.size() + " ticket");
            } else { setError("Errore SAP: " + response.getErrorMessage()); }
        } catch (Exception e) { setError("Eccezione: " + e.getMessage()); e.printStackTrace(); }
    }

    private void loadTicketsForAms(String idUserAms, String rstatFilter) {
        try {
            tickets = null;
            TicketResponse response = ticketService.getTickets(null, null, null, null, null, rstatFilter);
            if (response.isSuccess()) {
                List<Ticket> all = response.getTickets();
                tickets = all.stream()
                    .filter(t -> idUserAms.equalsIgnoreCase(t.getAmusr()))
                    .collect(java.util.stream.Collectors.toList());
                populateGrid(tickets, "Trovati " + tickets.size() + " ticket assegnati a " + idUserAms);
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

    public CommentUI getCommentUI()          { return m_commentUI; }
    public boolean   getCommentsPanelVisible() { return m_commentsPanelVisible; }

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