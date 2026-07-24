package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import eone.ticket.service.ClienteConfigService;
import eone.ticket.service.EnrichmentService;
import eone.ticket.service.RequesterService;
import eone.ticket.service.SAPTicketService;
import eone.ticket.service.SAPTicketService.TicketResponse;
import eone.ticket.service.SubstitutionService;
import eone.ticket.service.TicketDraftService;

@CCGenClass(expressionBase = "#{d.TicketListUI}")
public class TicketListUI extends WorkpageDispatchedPageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Listener per tornare al menu principale (OutestUI) */
    public interface IListener extends Serializable {
        void reactOnBackToMenu();
        default void reactOnSummaryUpdated(TicketSummary summary) {} // opzionale
        default void reactOnLogoutRequest() {} // opzionale — bottone "Logout"
    }

    private IListener m_listener;
    private CommentUI m_commentUI   = new CommentUI();
    private boolean   m_commentsPanelVisible = false;


    private SAPTicketService  ticketService     = null;
    private EnrichmentService enrichmentService = new EnrichmentService();
    private TicketDraftService draftService     = new TicketDraftService();
    private RequesterService  requesterService  = new RequesterService();
    private SubstitutionService substitutionService = new SubstitutionService();
    private ClienteConfigService clienteConfigService = new ClienteConfigService();

    /**
     * Reqid/amusr dei colleghi attualmente sostituiti dall'utente loggato
     * (periodo attivo oggi, estremi inclusi) — calcolati una volta in
     * init() e usati sia per ampliare le query (vede anche i loro ticket)
     * sia per evidenziare le relative righe in griglia (vedi GridTicketItem).
     */
    private Set<String> m_reqidSostituitiAttivi = new HashSet<>();
    private Set<String> m_amusrSostituitiAttivi = new HashSet<>();

    /** True quando la lista è aperta in modalità "Ticket come Referente" (solo AMS). */
    private boolean m_modoReferente;

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

        /**
         * Giallo se questo ticket appartiene a un collega attualmente
         * sostituito dall'utente loggato (colonna "Richiedente", ruolo
         * CLIENTE) — bianco altrimenti.
         */
        public String getReqidCellBackground() {
            String r = ticket.getReqid();
            return (r != null && m_reqidSostituitiAttivi.contains(r.trim())) ? "#FFF59D" : "#FFFFFF";
        }

        /**
         * Giallo se questo ticket appartiene a un collega AMS attualmente
         * sostituito dall'utente loggato (colonna "AMS") — bianco altrimenti.
         */
        public String getAmusrCellBackground() {
            String a = ticket.getAmusr();
            return (a != null && m_amusrSostituitiAttivi.contains(a.trim())) ? "#FFF59D" : "#FFFFFF";
        }

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

        /** Giorni di vita del ticket: da erdat a oggi. -1 se CLO/RES o dati mancanti. */
        public int getGiorniVita() {
            if ("CLO".equalsIgnoreCase(ticket.getRstat()) || "RES".equalsIgnoreCase(ticket.getRstat())) return -1;
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
        public void onRowExecute() { onRowSelect(); openComments(); }

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
    }

    public void prepare(IListener listener) {
        this.m_listener = listener;
    }

    public void backToMenu(ActionEvent ae) {
        System.out.println("[TicketListUI] backToMenu() chiamato, listener=" + (m_listener != null));
        if (ticketService != null) {
            ticketService.close();
        }
        if (m_listener != null) {
            m_listener.reactOnBackToMenu();
        }
    }

    /**
     * Bottone "Logout" accanto ad "Aggiorna" — stesso comportamento del
     * refresh del browser (torna alla pagina di Logon), senza richiedere
     * una vera ricarica di pagina.
     */
    public void logout(ActionEvent ae) {
        System.out.println("[TicketListUI] logout() chiamato, listener=" + (m_listener != null));
        if (ticketService != null) {
            ticketService.close();
        }
        if (m_listener != null) {
            m_listener.reactOnLogoutRequest();
        }
    }

    /** Usato da "Aggiorna" — ricarica preservando la modalità corrente (archivio/referente). */
    public void init() { init(m_archivio, m_modoReferente); }

    public void init(boolean archivio) {
        init(archivio, false);
    }

    /**
     * @param modoReferente se true (solo per AMS), mostra i ticket dove
     *                       l'utente è "Referente" (campo Refer) invece che
     *                       quelli assegnati come AMS (campo Amusr) — stessa
     *                       vista, criterio di filtro diverso. Non
     *                       applicabile ai DRAFT (non hanno un Referente) né
     *                       a "vede tutti" (è una vista intrinsecamente
     *                       personale, non ha senso vedere i referenti di
     *                       tutti).
     */
    public void init(boolean archivio, boolean modoReferente) {
        m_archivio = archivio;
        m_modoReferente = modoReferente;
        ViewSessionContext ctx = ViewSessionContext.instance();
        String kunnr      = ctx.getKunnr();
        String reqid      = ctx.getRichiedente();
        String utente     = ctx.getUtente();
        boolean vedeTutti = !modoReferente && "ALL".equalsIgnoreCase(ctx.getOwnAll());
        // Nessun filtro Rstat lato SAP in nessuno dei due casi: la vera
        // selezione (operativa vs archivio, con CLO+RES+CAN) avviene tutta
        // lato client in populateGrid(), altrimenti la restrizione server-side
        // "Rstat eq 'CLO'" escluderebbe i RES prima ancora di poterli filtrare.
        String rstatFilter = "ne:CLO";

        caricaSostituzioniAttive(ctx);

        if (modoReferente) {
            if (ctx.isAms() && ctx.getUsername() != null && !ctx.getUsername().trim().isEmpty()) {
                System.out.println("[TicketListUI] init() — Referente: " + ctx.getUsername() +
                                   (m_amusrSostituitiAttivi.isEmpty() ? "" : " + sostituiti " + m_amusrSostituitiAttivi));
                loadTicketsForReferente(ctx.getUsername(), rstatFilter);
            } else {
                System.err.println("[TicketListUI] init() — modoReferente richiesto ma utente non AMS, ignorato");
                loadAllTickets(rstatFilter);
            }
            return;
        }

        if (kunnr != null && !kunnr.isEmpty()) {
            if (vedeTutti) {
                System.out.println("[TicketListUI] init() — Kunnr: " + kunnr + ", Reqid: null" +
                                   (archivio ? " [ARCHIVIO]" : "") + " (vede_tutti=true)");
                loadTicketsForUser(kunnr, null, utente, rstatFilter);
            } else if (!m_reqidSostituitiAttivi.isEmpty()) {
                // Sostituzione attiva: amplia la query al kunnr intero e filtra
                // lato client su {proprio reqid} ∪ {reqid dei sostituiti} —
                // stesso schema già usato per vede_tutti, il filtro SAP su un
                // singolo Reqid non basta più.
                System.out.println("[TicketListUI] init() — Kunnr: " + kunnr + ", Reqid: " + reqid +
                                   " + sostituiti " + m_reqidSostituitiAttivi +
                                   (archivio ? " [ARCHIVIO]" : ""));
                loadTicketsForUser(kunnr, null, utente, rstatFilter);
            } else {
                System.out.println("[TicketListUI] init() — Kunnr: " + kunnr + ", Reqid: " + reqid +
                                   (archivio ? " [ARCHIVIO]" : ""));
                loadTicketsForUser(kunnr, reqid, utente, rstatFilter);
            }
        } else if (ctx.isAms() && ctx.getUsername() != null && !ctx.getUsername().trim().isEmpty()) {
            if (vedeTutti) {
                System.out.println("[TicketListUI] init() — AMS: " + ctx.getUsername() + " (vede_tutti=true)");
                loadAllTickets(rstatFilter);
            } else {
                System.out.println("[TicketListUI] init() — AMS: " + ctx.getUsername() + ", filtro per amusr" +
                                   (m_amusrSostituitiAttivi.isEmpty() ? "" : " + sostituiti " + m_amusrSostituitiAttivi));
                loadTicketsForAms(ctx.getUsername(), rstatFilter);
            }
        } else {
            System.err.println("[TicketListUI] init() — kunnr mancante, carico tutti i ticket");
            loadAllTickets(rstatFilter);
        }
    }

    /**
     * Calcola i colleghi (stesso ruolo) attualmente sostituiti dall'utente
     * loggato — usato per ampliare le query e per l'evidenziazione in griglia.
     * Un errore qui non blocca il caricamento della lista: si procede senza
     * sostituzioni (comportamento identico a prima di questa funzionalità).
     */
    private void caricaSostituzioniAttive(ViewSessionContext ctx) {
        m_reqidSostituitiAttivi = new HashSet<>();
        m_amusrSostituitiAttivi = new HashSet<>();
        String idUser = ctx.getUsername();
        if (idUser == null || idUser.trim().isEmpty()) {
            System.out.println("[TicketListUI] caricaSostituzioniAttive — idUser (username) non disponibile in sessione, salto.");
            return;
        }
        try {
            List<String> sostituiti = substitutionService.getSostituitiAttivi(idUser);
            System.out.println("[TicketListUI] caricaSostituzioniAttive — idUser='" + idUser +
                "' isAms=" + ctx.isAms() + " → colleghi sostituiti trovati in DB (id_user_sostituto=idUser, periodo attivo oggi): " + sostituiti);
            for (String idUserSostituito : sostituiti) {
                if (ctx.isAms()) {
                    // Per AMS l'id_user coincide con l'amusr sui ticket SAP
                    m_amusrSostituitiAttivi.add(idUserSostituito);
                } else {
                    RequesterInfo info = requesterService.getById(idUserSostituito);
                    if (info != null && info.getReqid() != null && !info.getReqid().trim().isEmpty()) {
                        m_reqidSostituitiAttivi.add(info.getReqid().trim());
                    } else {
                        System.out.println("[TicketListUI] caricaSostituzioniAttive — reqid non risolto per '" +
                            idUserSostituito + "' (utente non trovato o reqid vuoto in ticket_user) — sostituzione ignorata.");
                    }
                }
            }
            System.out.println("[TicketListUI] caricaSostituzioniAttive — risultato: reqidSostituiti=" +
                m_reqidSostituitiAttivi + " amusrSostituiti=" + m_amusrSostituitiAttivi);
        } catch (Exception e) {
            System.err.println("[TicketListUI] Errore caricamento sostituzioni attive: " + e.getMessage());
        }
    }

    // =========================
    // PANNELLO COMMENTI INLINE
    // =========================

    /**
     * Seleziona e apre direttamente un ticket per numero — usato dal deep
     * link nelle email ("Apri il ticket"). Se il ticket non è nella lista
     * attiva (es. è chiuso), ritenta automaticamente nell'archivio prima di
     * arrendersi, così il link funziona indipendentemente dallo stato.
     */
    public void selectAndOpenTicket(String tickt) {
        if (tickt == null || tickt.trim().isEmpty()) return;
        String target = tickt.trim();

        GridTicketItem found = m_gridTickets.getItems().stream()
            .filter(item -> target.equals(item.getTickt()))
            .findFirst().orElse(null);

        if (found == null && !m_archivio) {
            // Non è tra i ticket attivi (esclude CLO) — potrebbe essere chiuso.
            System.out.println("[TicketListUI] selectAndOpenTicket — non trovato tra gli attivi, ritento in archivio: " + target);
            init(true);
            found = m_gridTickets.getItems().stream()
                .filter(item -> target.equals(item.getTickt()))
                .findFirst().orElse(null);
        }

        if (found == null) {
            Statusbar.outputWarning("Ticket " + target + " non trovato o non accessibile.");
            System.err.println("[TicketListUI] selectAndOpenTicket — ticket non trovato: " + target);
            return;
        }

        m_selectedTicketNumber = found.getTickt();
        m_selectedTicketObj    = found.ticket;
        openComments();
    }

    /**
     * True se il ticket attualmente selezionato è un DRAFT non ancora
     * fuso in SAP, e appartiene al CLIENTE loggato (o a un collega che sta
     * attualmente sostituendo) — condizione per mostrare "Elimina DRAFT".
     */
    public boolean isSelectedDraftEliminabile() {
        if (m_selectedTicketObj == null || !"DRAFT".equals(m_selectedTicketObj.getRstat())) return false;
        ViewSessionContext ctx = ViewSessionContext.instance();
        if (!ctx.isCliente()) return false;
        String reqid = m_selectedTicketObj.getReqid();
        if (reqid == null) return false;
        String proprioReqid = ctx.getRichiedente();
        return reqid.equalsIgnoreCase(proprioReqid) || m_reqidSostituitiAttivi.contains(reqid.trim());
    }

    /** Cancella il DRAFT selezionato — solo se non ancora fuso in SAP e di propria competenza. */
    public void onEliminaDraft(ActionEvent ae) {
        if (!isSelectedDraftEliminabile()) {
            Statusbar.outputWarning("Nessun DRAFT eliminabile selezionato");
            return;
        }
        String ticktKey = m_selectedTicketObj.getTickt(); // "DRAFT-{id}"
        try {
            long draftId = Long.parseLong(ticktKey.replace("DRAFT-", "").trim());
            // NOTA: uso ctx.getKunnr() (con zero-padding originale), non
            // m_selectedTicketObj.getKunnr() — quest'ultimo per i DRAFT è
            // privato degli zeri iniziali per la visualizzazione (vedi
            // buildTicketsFromDrafts) e non farebbe match nella query DB.
            String kunnr = ViewSessionContext.instance().getKunnr();
            String reqid = m_selectedTicketObj.getReqid();
            boolean eliminato = draftService.deleteDraft(draftId, kunnr, reqid);
            if (eliminato) {
                Statusbar.outputSuccess("DRAFT " + ticktKey + " eliminato");
                m_selectedTicketNumber = null;
                m_selectedTicketObj = null;
                init(m_archivio, m_modoReferente);
            } else {
                Statusbar.outputError("Impossibile eliminare: il DRAFT non esiste più o è già stato fuso in SAP.");
            }
        } catch (Exception e) {
            Statusbar.outputError("Errore eliminazione DRAFT: " + e.getMessage());
            System.err.println("[TicketListUI] Errore onEliminaDraft: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void openComments() {
        if (m_selectedTicketNumber == null || m_selectedTicketNumber.isEmpty()) {
            Statusbar.outputWarning("Selezionare un ticket dalla lista");
            return;
        }
        String kunnr = m_selectedTicketObj != null ? m_selectedTicketObj.getKunnr() : "";
        String reqid = m_selectedTicketObj != null ? m_selectedTicketObj.getReqid() : "";
        String amusr = m_selectedTicketObj != null ? m_selectedTicketObj.getAmusr() : "";
        String refer = m_selectedTicketObj != null ? m_selectedTicketObj.getRefer() : "";
        m_commentUI.setCloseListener(new CommentUI.IListener() {
            @Override
            public void reactOnClose() {
                m_commentsPanelVisible = false;
                refreshAfterCommentPopup();
            }
        });
        m_commentUI.init(m_selectedTicketNumber, kunnr, reqid, amusr, refer);
        m_commentsPanelVisible = true;
    }

    public void closeComments(ActionEvent ae) {
        m_commentsPanelVisible = false;
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
        // RES (Risolto) trattato esattamente come CLO (Chiuso): tolto dalla
        // lista operativa, visibile solo in archivio.
        if (m_archivio) {
            // Archivio: tieni solo CLO, RES e CAN
            ticketList = ticketList.stream()
                .filter(t -> "CLO".equals(t.getRstat()) || "RES".equals(t.getRstat()) || "CAN".equals(t.getRstat()))
                .collect(java.util.stream.Collectors.toList());
        } else {
            // Lista operativa: escludi CLO, RES e CAN
            ticketList = ticketList.stream()
                .filter(t -> !"CLO".equals(t.getRstat()) && !"RES".equals(t.getRstat()) && !"CAN".equals(t.getRstat()))
                .collect(java.util.stream.Collectors.toList());
        }

        enrichmentService.enrichTickets(ticketList);
        ticketsEnriched = ticketList;

        // DRAFT in cache solo per la lista operativa, non per l'archivio né
        // per la modalità Referente (i DRAFT non hanno un Referente — sono
        // record locali pre-fusione SAP).
        draftsCache = null;
        if (!m_archivio && !m_modoReferente) {
            ViewSessionContext ctx = ViewSessionContext.instance();
            if (ctx.isCliente() && ctx.getKunnr() != null && !ctx.getKunnr().isEmpty()) {
                if (!m_reqidSostituitiAttivi.isEmpty()) {
                    // Include anche i DRAFT dei colleghi attualmente sostituiti
                    List<String> reqids = new java.util.ArrayList<>(m_reqidSostituitiAttivi);
                    reqids.add(ctx.getRichiedente());
                    draftsCache = buildDraftTickets(ctx.getKunnr(), reqids);
                } else {
                    // CLIENTE: solo i suoi DRAFT
                    draftsCache = buildDraftTickets(ctx.getKunnr(), ctx.getRichiedente());
                }
            } else if ("DISPATCHER".equalsIgnoreCase(ctx.getRuolo())) {
                // DISPATCHER: tutti i DRAFT in attesa da tutti i clienti
                draftsCache = buildAllDraftTickets();
            }
        }

        rebuildGridWithColFilters();
        m_hasError      = false;
        m_hasTickets    = !ticketList.isEmpty();
        m_statusMessage = statusMsg;
        Statusbar.outputSuccess(statusMsg);

        // Aggiorna il summary SOLO dalla lista operativa (non dall'archivio)
        // per mantenere il riepilogo stabile e coerente nel menu.
        if (!m_archivio && !m_modoReferente && m_listener != null) {
            int draftCount = draftsCache != null ? draftsCache.size() : 0;
            int sostituitiCount = contaTicketDiSostituiti();
            m_listener.reactOnSummaryUpdated(TicketSummary.build(ticketsEnriched, draftCount, sostituitiCount));
        }
    }

    /**
     * Quanti dei ticket/draft attualmente caricati (ticketsEnriched +
     * draftsCache) appartengono a colleghi attualmente sostituiti — per la
     * riga in più nel riepilogo del Menu. Non è un totale a parte: quei
     * ticket sono già conteggiati normalmente nelle voci per stato.
     */
    private int contaTicketDiSostituiti() {
        if (m_reqidSostituitiAttivi.isEmpty() && m_amusrSostituitiAttivi.isEmpty()) return 0;
        int count = 0;
        if (ticketsEnriched != null) {
            for (Ticket t : ticketsEnriched) {
                String r = t.getReqid() != null ? t.getReqid().trim() : "";
                String a = t.getAmusr() != null ? t.getAmusr().trim() : "";
                if (m_reqidSostituitiAttivi.contains(r) || m_amusrSostituitiAttivi.contains(a)) count++;
            }
        }
        if (draftsCache != null) {
            for (Ticket t : draftsCache) {
                String r = t.getReqid() != null ? t.getReqid().trim() : "";
                if (m_reqidSostituitiAttivi.contains(r)) count++;
            }
        }
        return count;
    }

    /**
     * Costruisce la lista di Ticket "virtuali" dai DRAFT locali del richiedente.
     * Restituisce la lista invece di aggiungerla direttamente alla grid,
     * così viene memorizzata in draftsCache e riusata da ogni rebuild.
     */
    private List<Ticket> buildDraftTickets(String kunnr, String reqid) {
        List<TicketDraft> drafts;
        try {
            drafts = draftService.getDraftsByRequester(kunnr, reqid);
        } catch (Exception e) {
            System.err.println("[TicketListUI] Errore caricamento DRAFT: " + e.getMessage());
            return new java.util.ArrayList<>();
        }
        return buildTicketsFromDrafts(drafts);
    }

    /** Come sopra, ma per più reqid — usato quando ci sono sostituzioni attive. */
    private List<Ticket> buildDraftTickets(String kunnr, List<String> reqids) {
        List<TicketDraft> drafts;
        try {
            drafts = draftService.getDraftsByRequesters(kunnr, reqids);
        } catch (Exception e) {
            System.err.println("[TicketListUI] Errore caricamento DRAFT (multi-reqid): " + e.getMessage());
            return new java.util.ArrayList<>();
        }
        return buildTicketsFromDrafts(drafts);
    }

    /** Tutti i DRAFT in attesa — per il DISPATCHER */
    private List<Ticket> buildAllDraftTickets() {
        List<TicketDraft> drafts;
        try {
            drafts = draftService.getPendingDrafts();
        } catch (Exception e) {
            System.err.println("[TicketListUI] Errore caricamento tutti i DRAFT: " + e.getMessage());
            return new java.util.ArrayList<>();
        }
        return buildTicketsFromDrafts(drafts);
    }

    /** Converte una lista di TicketDraft in Ticket virtuali per la grid */
    private List<Ticket> buildTicketsFromDrafts(List<TicketDraft> drafts) {
        List<Ticket> result = new java.util.ArrayList<>();
        for (TicketDraft d : drafts) {
            if (!d.isDraft()) continue;
            Ticket t = new Ticket();
            t.setTickt (d.getTicktKey());
            t.setTitle (d.getTitolo());
            t.setRstat ("DRAFT");
            t.setKunnr (stripLeadingZeros(d.getKunnr()));
            t.setReqid (d.getReqid());
            String reqidDraft = d.getReqid();
            String nomeRichiedente = reqidDraft != null ? reqidDraft : "";
            try {
                if (d.getKunnr() != null && reqidDraft != null) {
                    RequesterInfo info = requesterService.getByKunnrReqid(d.getKunnr(), reqidDraft);
                    if (info != null && info.getNome() != null && !info.getNome().trim().isEmpty()) {
                        nomeRichiedente = reqidDraft + " — " + info.getNome().trim();
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
                // reqid è null sia per vede_tutti che per sostituzione attiva —
                // nel caso di sostituzione (non vede_tutti) restringiamo qui
                // lato client a {proprio reqid} ∪ {reqid dei sostituiti},
                // altrimenti si vedrebbe l'intero cliente come con vede_tutti.
                boolean vedeTutti = "ALL".equalsIgnoreCase(ViewSessionContext.instance().getOwnAll());
                if (reqid == null && !vedeTutti && !m_reqidSostituitiAttivi.isEmpty()) {
                    String proprioReqid = ViewSessionContext.instance().getRichiedente();
                    tickets = tickets.stream()
                        .filter(t -> {
                            String r = t.getReqid() != null ? t.getReqid().trim() : "";
                            return r.equalsIgnoreCase(proprioReqid) || m_reqidSostituitiAttivi.contains(r);
                        })
                        .collect(java.util.stream.Collectors.toList());
                }
                populateGrid(tickets, "Trovati " + tickets.size() + " ticket per " + utente);
            } else { setError("Errore SAP: " + response.getErrorMessage()); }
        } catch (Exception e) { setError("Eccezione: " + e.getMessage()); e.printStackTrace(); }
    }

    private void loadAllTickets(String rstatFilter) {
        try {
            tickets = null;
            TicketResponse response = ticketService.getTickets(null, null, null, null, null, rstatFilter);
            if (response.isSuccess()) {
                tickets = filtraClientiAbilitati(response.getTickets());
                populateGrid(tickets, "Caricati " + tickets.size() + " ticket");
            } else { setError("Errore SAP: " + response.getErrorMessage()); }
        } catch (Exception e) { setError("Eccezione: " + e.getMessage()); e.printStackTrace(); }
    }

    private void loadTicketsForAms(String idUserAms, String rstatFilter) {
        try {
            tickets = null;
            TicketResponse response = ticketService.getTickets(null, null, null, null, null, rstatFilter);
            if (response.isSuccess()) {
                List<Ticket> all = filtraClientiAbilitati(response.getTickets());
                tickets = all.stream()
                    .filter(t -> idUserAms.equalsIgnoreCase(t.getAmusr()) ||
                                 (t.getAmusr() != null && m_amusrSostituitiAttivi.contains(t.getAmusr().trim())))
                    .collect(java.util.stream.Collectors.toList());
                String msg = "Trovati " + tickets.size() + " ticket assegnati a " + idUserAms;
                if (!m_amusrSostituitiAttivi.isEmpty()) msg += " (incl. sostituzioni)";
                populateGrid(tickets, msg);
            } else { setError("Errore SAP: " + response.getErrorMessage()); }
        } catch (Exception e) { setError("Eccezione: " + e.getMessage()); e.printStackTrace(); }
    }

    /**
     * Come {@link #loadTicketsForAms}, ma filtra sul campo Refer (Referente)
     * invece che Amusr — stessa logica di sostituzione (un collega
     * sostituito è la stessa identità id_user, che si controlli su Amusr o
     * su Refer). Niente DRAFT: non hanno un Referente, sono record locali
     * pre-fusione SAP.
     */
    private void loadTicketsForReferente(String idUserAms, String rstatFilter) {
        try {
            tickets = null;
            TicketResponse response = ticketService.getTickets(null, null, null, null, null, rstatFilter);
            if (response.isSuccess()) {
                List<Ticket> all = filtraClientiAbilitati(response.getTickets());
                tickets = all.stream()
                    .filter(t -> idUserAms.equalsIgnoreCase(t.getRefer()) ||
                                 (t.getRefer() != null && m_amusrSostituitiAttivi.contains(t.getRefer().trim())))
                    .collect(java.util.stream.Collectors.toList());
                String msg = "Trovati " + tickets.size() + " ticket dove sei Referente";
                if (!m_amusrSostituitiAttivi.isEmpty()) msg += " (incl. sostituzioni)";
                populateGrid(tickets, msg);
            } else { setError("Errore SAP: " + response.getErrorMessage()); }
        } catch (Exception e) { setError("Eccezione: " + e.getMessage()); e.printStackTrace(); }
    }

    /**
     * Scarta i ticket dei clienti (Kunnr) non ancora abilitati alla nuova
     * gestione — usato solo nelle viste che attraversano tutti i clienti
     * (AMS/DISPATCHER/Referente). La vista del CLIENTE stesso non ne ha
     * bisogno: se ha un login, il suo cliente è per forza già migrato.
     * Un errore nel leggere l'abilitazione non deve bloccare la lista:
     * in quel caso si mostra tutto, senza filtro (fail-open sull'esistente,
     * non sul nuovo — coerente con "non bloccare mai un caricamento").
     */
    private List<Ticket> filtraClientiAbilitati(List<Ticket> ticketList) {
        if (ticketList == null || ticketList.isEmpty()) return ticketList;
        try {
            java.util.Set<String> abilitati = clienteConfigService.getKunnrAbilitati();
            if (abilitati.isEmpty()) {
                System.out.println("[TicketListUI] Nessun cliente abilitato in ticket_cliente_config — lista vuota.");
            }
            return ticketList.stream()
                .filter(t -> abilitati.contains(ClienteConfigService.normalizeKunnr(t.getKunnr())))
                .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            System.err.println("[TicketListUI] Errore lettura clienti abilitati, procedo senza filtro: " + e.getMessage());
            return ticketList;
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
            } else { setError("Errore SAP: " + response.getErrorMessage()); }
        } catch (Exception e) { setError("Eccezione: " + e.getMessage()); e.printStackTrace(); }
    }

    public void clearFilters() {
        m_filterKunnr = m_filterReqid = m_filterFromDate = m_filterToDate = null;
        init();
    }

    public void refreshTickets(ActionEvent ae) { init(); }

    // =========================
    // GETTERS / SETTERS
    // =========================

    @Override public String getPageName()                 { return "/TicketList.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.TicketListUI}"; }

    public CommentUI getCommentUI()            { return m_commentUI; }
    public boolean getCommentsPanelVisible()   { return m_commentsPanelVisible; }

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