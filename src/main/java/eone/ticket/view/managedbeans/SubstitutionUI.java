package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.RequesterInfo;
import eone.ticket.model.Ticket;
import eone.ticket.model.TicketDraft;
import eone.ticket.model.TicketSubstitution;
import eone.ticket.service.MailService;
import eone.ticket.service.RequesterService;
import eone.ticket.service.SAPTicketService;
import eone.ticket.service.SubstitutionService;
import eone.ticket.service.TicketDraftService;

/**
 * UI self-service per definire la propria sostituzione temporanea.
 * Versione semplice: un solo periodo alla volta, sostituto scelto tra
 * utenti con lo stesso ruolo dell'utente loggato.
 */
@CCGenClass(expressionBase = "#{d.SubstitutionUI}")
public class SubstitutionUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void reactOnBackToMenu();
    }

    private IListener m_listener;

    private final SubstitutionService substitutionService = new SubstitutionService();
    private final RequesterService    requesterService     = new RequesterService();
    private final SAPTicketService    sapService            = new SAPTicketService();
    private final TicketDraftService  draftService          = new TicketDraftService();
    private final MailService         mailService           = new MailService();

    private String    m_idUserSostituto;
    private LocalDate m_dataInizio;
    private LocalDate m_dataFine;

    private ValidValuesBinding m_sostitutoVVS = new ValidValuesBinding();

    /** La sostituzione già configurata (se esiste) — mostrata come riepilogo. */
    private TicketSubstitution m_esistente;

    /** Nomi di chi questo utente sta già coprendo come sostituto — per l'avviso non bloccante. */
    private List<String> m_copreAltriUtenti = new ArrayList<>();

    // =========================
    // COSTRUTTORE / INIT
    // =========================

    public SubstitutionUI() {
    }

    public void prepare(IListener listener) {
        this.m_listener = listener;
        caricaStatoAttuale();
    }

    private void caricaStatoAttuale() {
        ViewSessionContext ctx = ViewSessionContext.instance();
        String idUser = ctx.getUsername();
        String ruolo  = ctx.getRuolo();
        // Per i CLIENTE il sostituto deve appartenere allo stesso Kunnr —
        // per AMS/ADMIN (nessun Kunnr associato) kunnr è null: nessun filtro.
        String kunnrHint = ctx.isCliente() ? ctx.getKunnr() : null;

        try {
            // Combobox sostituto: solo utenti con lo stesso ruolo (e, se
            // CLIENTE, stesso Kunnr), esclusi sé stesso
            m_sostitutoVVS = new ValidValuesBinding();
            List<RequesterInfo> colleghi = substitutionService.getUtentiStessoRuolo(idUser, ruolo, kunnrHint);
            for (RequesterInfo r : colleghi) {
                String label = (r.getNome() != null && !r.getNome().trim().isEmpty())
                    ? r.getNome().trim() + " (" + r.getId_user() + ")" : r.getId_user();
                m_sostitutoVVS.addValidValue(r.getId_user(), label);
            }

            m_esistente = substitutionService.getByUser(idUser);
            if (m_esistente != null) {
                m_idUserSostituto = m_esistente.getIdUserSostituto();
                m_dataInizio      = m_esistente.getDataInizio();
                m_dataFine        = m_esistente.getDataFine();
            } else {
                m_idUserSostituto = null;
                m_dataInizio = LocalDate.now();
                m_dataFine   = LocalDate.now().plusDays(7);
            }

            // Avviso non bloccante: se l'utente sta già coprendo altri come
            // sostituto, il SUO sostituto non erediterebbe automaticamente
            // quei ticket (sostituzione a un solo livello, non a catena) —
            // lo segnaliamo, ma non impediamo il salvataggio: chi imposta la
            // sostituzione può comunque decidere come gestirlo altrove.
            List<String> copertiDaMe = substitutionService.getSostituitiAttivi(idUser);
            m_copreAltriUtenti = new ArrayList<>();
            for (String idCoperto : copertiDaMe) {
                RequesterInfo info = requesterService.getById(idCoperto);
                m_copreAltriUtenti.add(info != null ? info.getNomeOReqid() : idCoperto);
            }
        } catch (Exception e) {
            Statusbar.outputError("Errore caricamento sostituzioni: " + e.getMessage());
            System.err.println("[SubstitutionUI] Errore caricaStatoAttuale: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================
    // AZIONI
    // =========================

    public void backToMenu(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnBackToMenu();
    }

    public void salva(ActionEvent ae) {
        if (m_idUserSostituto == null || m_idUserSostituto.trim().isEmpty()) {
            Statusbar.outputWarning("Selezionare un sostituto");
            return;
        }
        if (m_dataInizio == null || m_dataFine == null) {
            Statusbar.outputWarning("Indicare data di inizio e fine del periodo");
            return;
        }
        if (m_dataFine.isBefore(m_dataInizio)) {
            Statusbar.outputWarning("La data di fine non può essere precedente alla data di inizio");
            return;
        }

        ViewSessionContext ctx = ViewSessionContext.instance();
        String idUser = ctx.getUsername();

        try {
            substitutionService.save(idUser, m_idUserSostituto, m_dataInizio, m_dataFine);
            Statusbar.outputSuccess("Sostituzione salvata: dal " + m_dataInizio + " al " + m_dataFine);
            inviaNotificaSostituto(ctx, idUser, m_idUserSostituto, m_dataInizio, m_dataFine);
            caricaStatoAttuale();
        } catch (Exception e) {
            Statusbar.outputError("Errore salvataggio: " + e.getMessage());
            System.err.println("[SubstitutionUI] Errore salva: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Avvisa il sostituto via email, con l'elenco dei ticket attualmente a
     * carico del sostituito (SAP attivi + eventuali DRAFT, per i CLIENTE) —
     * così arriva già informato di cosa dovrà seguire, invece di scoprirlo
     * aprendo l'app. Un errore qui non annulla il salvataggio già avvenuto:
     * viene solo loggato.
     */
    private void inviaNotificaSostituto(ViewSessionContext ctx, String idUserSostituito,
                                         String idUserSostituto, LocalDate dataInizio, LocalDate dataFine) {
        try {
            RequesterInfo sostituto = requesterService.getById(idUserSostituto);
            if (sostituto == null || sostituto.getEmail() == null || sostituto.getEmail().trim().isEmpty()) {
                System.out.println("[SubstitutionUI] Notifica sostituto saltata: utente non trovato o senza email — " + idUserSostituto);
                return;
            }
            String nomeSostituito = ctx.getRequesterInfo() != null ? ctx.getRequesterInfo().getNomeOReqid() : idUserSostituito;

            List<String> righeTicket = new ArrayList<>();
            if (ctx.isCliente() && ctx.getKunnr() != null && !ctx.getKunnr().isEmpty()) {
                String reqid = ctx.getRichiedente();
                SAPTicketService.TicketResponse resp = sapService.getTickets(ctx.getKunnr(), reqid, null, null, null, "ne:CLO");
                if (resp.isSuccess() && resp.getTickets() != null) {
                    for (Ticket t : resp.getTickets()) {
                        if (isNonGestibile(t.getRstat())) continue; // niente CLO/RES: non da gestire
                        righeTicket.add(t.getTickt() + ": " + nn(t.getTitle()));
                    }
                }
                try {
                    List<TicketDraft> drafts = draftService.getDraftsByRequester(ctx.getKunnr(), reqid);
                    for (TicketDraft d : drafts) {
                        if (d.isDraft()) righeTicket.add(d.getTicktKey() + ": " + nn(d.getTitolo()) + " (DRAFT)");
                    }
                } catch (Exception e) {
                    System.err.println("[SubstitutionUI] Errore lettura DRAFT per notifica sostituto: " + e.getMessage());
                }
            } else if (ctx.isAms()) {
                SAPTicketService.TicketResponse resp = sapService.getTickets(null, null, null, null, null, "ne:CLO");
                if (resp.isSuccess() && resp.getTickets() != null) {
                    for (Ticket t : resp.getTickets()) {
                        if (isNonGestibile(t.getRstat())) continue; // niente CLO/RES: non da gestire
                        if (idUserSostituito.equalsIgnoreCase(t.getAmusr())) {
                            righeTicket.add(t.getTickt() + ": " + nn(t.getTitle()));
                        }
                    }
                }
            }

            mailService.sendNotificaSostituzione(sostituto.getEmail(), nomeSostituito, dataInizio, dataFine, righeTicket);
        } catch (Exception e) {
            System.err.println("[SubstitutionUI] Errore invio notifica sostituto: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** CLO (Chiuso) e RES (Risolto) — non da gestire, esclusi dall'elenco inviato al sostituto. */
    private static boolean isNonGestibile(String rstat) {
        return "CLO".equalsIgnoreCase(rstat) || "RES".equalsIgnoreCase(rstat);
    }

    private static String nn(String s) { return s != null ? s : ""; }

    public void annulla(ActionEvent ae) {
        ViewSessionContext ctx = ViewSessionContext.instance();
        String idUser = ctx.getUsername();
        try {
            substitutionService.delete(idUser);
            Statusbar.outputSuccess("Sostituzione rimossa");
            caricaStatoAttuale();
        } catch (Exception e) {
            Statusbar.outputError("Errore rimozione: " + e.getMessage());
            System.err.println("[SubstitutionUI] Errore annulla: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================
    // GETTERS / SETTERS
    // =========================

    @Override public String getPageName()                 { return "/Substitution.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.SubstitutionUI}"; }

    public ValidValuesBinding getSostitutoVVS() { return m_sostitutoVVS; }

    public String getSostitutoHint() {
        return ViewSessionContext.instance().isCliente()
            ? "Solo utenti del tuo stesso cliente e con il tuo stesso ruolo."
            : "Solo utenti con il tuo stesso ruolo.";
    }

    public String getIdUserSostituto()          { return m_idUserSostituto; }
    public void setIdUserSostituto(String v)    { this.m_idUserSostituto = v; }

    public LocalDate getDataInizio()            { return m_dataInizio; }
    public void setDataInizio(LocalDate v)      { this.m_dataInizio = v; }

    public LocalDate getDataFine()              { return m_dataFine; }
    public void setDataFine(LocalDate v)        { this.m_dataFine = v; }

    public boolean getHasEsistente() { return m_esistente != null; }

    public boolean getHasCoperturaAltri() { return !m_copreAltriUtenti.isEmpty(); }

    public String getCoperturaAltriMessaggio() {
        if (m_copreAltriUtenti.isEmpty()) return "";
        return "Stai già sostituendo: " + String.join(", ", m_copreAltriUtenti) +
               ". Il tuo sostituto non erediterà automaticamente quei ticket — valuta un'azione aggiuntiva se necessario.";
    }

    public String getEsistenteRiepilogo() {
        if (m_esistente == null) return "";
        String stato = m_esistente.isAttivaOggi() ? "in corso"
                      : m_esistente.isFutura() ? "programmata"
                      : "scaduta";
        return "Sostituto attuale: " + m_idUserSostituto +
               " — dal " + m_esistente.getDataInizioFormatted() +
               " al " + m_esistente.getDataFineFormatted() +
               " (" + stato + ")";
    }
}