package eone.ticket.view.managedbeans;

import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.pagebean.PageBean;

import eone.ticket.config.AppConfig;
import eone.ticket.service.PromemoriaQuotidianoService;

/**
 * "Programmino" ADMIN per il promemoria quotidiano AMS/DISPATCHER (vedi
 * PromemoriaQuotidianoService): mostra la configurazione corrente e
 * permette di forzare subito l'invio, senza aspettare l'orario schedulato
 * (utile per verificare il contenuto delle mail dopo una modifica, o se un
 * giorno lo scheduler automatico è saltato per un riavvio di Tomcat).
 *
 * In MAIL_DRY_RUN (default finché MAIL_HOST non è configurato) le mail
 * vengono solo loggate, non inviate davvero — vedi il log applicativo.
 */
@CCGenClass(expressionBase = "#{d.PromemoriaAdminUI}")
public class PromemoriaAdminUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void reactOnBackToMenu();
    }

    private IListener m_listener;
    private final PromemoriaQuotidianoService promemoriaService = new PromemoriaQuotidianoService();

    private boolean m_invioInCorso;
    private String  m_ultimoEsito = "";

    public void prepare(IListener listener) {
        this.m_listener = listener;
        m_ultimoEsito = "";
    }

    public void backToMenu(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnBackToMenu();
    }

    @Override public String getPageName()                 { return "/PromemoriaAdmin.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.PromemoriaAdminUI}"; }

    // =========================
    // CONFIGURAZIONE CORRENTE (sola lettura — si cambia in config.properties)
    // =========================

    public boolean getAbilitato() {
        return AppConfig.getBoolean("PROMEMORIA_QUOTIDIANO_ABILITATO", true);
    }

    public String getOrarioSchedulato() {
        String ora = AppConfig.get("PROMEMORIA_QUOTIDIANO_ORA", "8");
        String minuto = AppConfig.get("PROMEMORIA_QUOTIDIANO_MINUTO", "30");
        try {
            return String.format("%02d:%02d", Integer.parseInt(ora.trim()), Integer.parseInt(minuto.trim()));
        } catch (NumberFormatException e) {
            return ora + ":" + minuto;
        }
    }

    public String getStatoLabel() {
        return getAbilitato()
            ? "Attivo — invio automatico ogni giorno feriale alle " + getOrarioSchedulato()
            : "Disattivato da configurazione (PROMEMORIA_QUOTIDIANO_ABILITATO=false)";
    }

    public boolean getDryRun() {
        String host = AppConfig.get("MAIL_HOST", null);
        if (host == null || host.trim().isEmpty()) return true;
        return AppConfig.getBoolean("MAIL_DRY_RUN", true);
    }

    public String getDryRunColor() {
        return getDryRun() ? "#C62828" : "#1B5E20";
    }

    public String getDryRunLabel() {
        return getDryRun()
            ? "NO — le mail vengono solo loggate, non inviate davvero"
            : "SÌ — invio reale via SMTP";
    }

    // =========================
    // INVIO MANUALE
    // =========================

    public boolean getInvioInCorso() { return m_invioInCorso; }
    public String  getUltimoEsito()  { return m_ultimoEsito; }
    public boolean getHasUltimoEsito() { return m_ultimoEsito != null && !m_ultimoEsito.isEmpty(); }

    public void onInviaOra(ActionEvent ae) {
        m_invioInCorso = true;
        try {
            promemoriaService.eseguiPromemoriaQuotidiano();
            m_ultimoEsito = "Elaborazione completata alle " +
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")) +
                (getDryRun() ? " — dry-run: nessuna mail realmente inviata, vedi il log applicativo per il dettaglio."
                             : " — mail inviate (nessuna se nessun AMS/DISPATCHER aveva attività pendenti).");
            Statusbar.outputSuccess("Promemoria elaborato.");
        } catch (Exception e) {
            m_ultimoEsito = "Errore: " + e.getMessage();
            Statusbar.outputError("Errore invio promemoria: " + e.getMessage());
            System.err.println("[PromemoriaAdminUI] Errore onInviaOra: " + e.getMessage());
            e.printStackTrace();
        } finally {
            m_invioInCorso = false;
        }
    }
}