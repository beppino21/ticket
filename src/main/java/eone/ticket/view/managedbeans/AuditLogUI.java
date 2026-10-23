package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import eone.ticket.service.AuditService;

/**
 * Consultazione (sola lettura) del registro attività ticket_audit_log —
 * solo ADMIN. Nessuna modifica possibile da qui: il registro è append-only.
 */
@CCGenClass(expressionBase = "#{d.AuditLogUI}")
public class AuditLogUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public AuditLogUI() {
        // Secondo livello di difesa: pagina non apribile senza utente autorizzato (vedi SessionGuard)
        eone.ticket.context.SessionGuard.richiediRuolo("AuditLog", "ADMIN");
    }
    private static final int  MAX_RIGHE = 1000;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public interface IListener extends Serializable {
        void reactOnBackToMenu();
    }

    private IListener m_listener;
    private final FIXGRIDListBinding<VoceRow> m_grid = new FIXGRIDListBinding<>();
    private final ValidValuesBinding m_eventoVVB = new ValidValuesBinding();

    private String    m_filtroUtente;
    private String    m_filtroEvento;
    private String    m_filtroOggetto;
    private LocalDate m_da = LocalDate.now().minusDays(7);
    private LocalDate m_a  = LocalDate.now();
    private String    m_dettaglioSelezionato = "";

    public void prepare(IListener listener) {
        m_listener = listener;
        for (String ev : AuditService.codiciEvento()) m_eventoVVB.addValidValue(ev, ev);
        cerca();
    }

    private void cerca() {
        try {
            List<AuditService.Voce> voci = AuditService.cerca(m_filtroUtente, m_filtroEvento, m_filtroOggetto,
                m_da != null ? m_da.atStartOfDay() : null,
                m_a  != null ? m_a.plusDays(1).atStartOfDay() : null,
                MAX_RIGHE);
            m_grid.getItems().clear();
            for (AuditService.Voce v : voci) m_grid.getItems().add(new VoceRow(v));
            m_dettaglioSelezionato = "";
            Statusbar.outputSuccess(voci.size() + " eventi" + (voci.size() >= MAX_RIGHE ? " (mostrati i più recenti " + MAX_RIGHE + ": restringere i filtri)" : ""));
        } catch (Exception e) {
            Statusbar.outputError("Errore lettura registro: " + e.getMessage());
            System.err.println("[AuditLogUI] Errore cerca: " + e.getMessage());
        }
    }

    public void onCerca(ActionEvent ae)      { cerca(); }
    public void backToMenu(ActionEvent ae)   { if (m_listener != null) m_listener.reactOnBackToMenu(); }

    @Override public String getPageName()                 { return "/AuditLog.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.AuditLogUI}"; }

    public FIXGRIDListBinding<VoceRow> getGrid() { return m_grid; }
    public ValidValuesBinding getEventoVVB()     { return m_eventoVVB; }

    public String    getFiltroUtente()             { return m_filtroUtente; }
    public void      setFiltroUtente(String v)     { m_filtroUtente = v; }
    public String    getFiltroEvento()             { return m_filtroEvento; }
    public void      setFiltroEvento(String v)     { m_filtroEvento = v; }
    public String    getFiltroOggetto()            { return m_filtroOggetto; }
    public void      setFiltroOggetto(String v)    { m_filtroOggetto = v; }
    public LocalDate getDa()                       { return m_da; }
    public void      setDa(LocalDate v)            { m_da = v; }
    public LocalDate getA()                        { return m_a; }
    public void      setA(LocalDate v)             { m_a = v; }
    public String    getDettaglioSelezionato()     { return m_dettaglioSelezionato; }

    public class VoceRow extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final AuditService.Voce v;
        public VoceRow(AuditService.Voce v) { this.v = v; }

        public String getTs()        { return v.ts != null ? v.ts.format(FMT) : ""; }
        public String getIdUser()    { return nn(v.idUser); }
        public String getKunnr()     { return nn(v.kunnr); }
        public String getRuolo()     { return nn(v.ruolo); }
        public String getEvento()    { return nn(v.evento); }
        public String getEsito()     { return nn(v.esito); }
        public String getOggetto()   { return nn(v.oggetto); }
        public String getDettaglio() { return nn(v.dettaglio); }
        public String getIp()        { return nn(v.ip); }
        public String getRigaColor() { return AuditService.ESITO_FAIL.equals(v.esito) ? "#FFEBEE" : "#FFFFFF"; }

        public void onRowSelect() {
            m_dettaglioSelezionato = getTs() + " — " + getEvento() + " (" + getEsito() + ") — utente " + getIdUser()
                + " — IP " + getIp() + "\n" + getOggetto() + "\n" + getDettaglio();
        }
    }

    private static String nn(String s) { return s != null ? s : ""; }
}
