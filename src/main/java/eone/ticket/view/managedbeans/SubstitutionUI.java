package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.RequesterInfo;
import eone.ticket.model.TicketSubstitution;
import eone.ticket.service.SubstitutionService;

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

    private String    m_idUserSostituto;
    private LocalDate m_dataInizio;
    private LocalDate m_dataFine;

    private ValidValuesBinding m_sostitutoVVS = new ValidValuesBinding();

    /** La sostituzione già configurata (se esiste) — mostrata come riepilogo. */
    private TicketSubstitution m_esistente;

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
            caricaStatoAttuale();
        } catch (Exception e) {
            Statusbar.outputError("Errore salvataggio: " + e.getMessage());
            System.err.println("[SubstitutionUI] Errore salva: " + e.getMessage());
            e.printStackTrace();
        }
    }

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