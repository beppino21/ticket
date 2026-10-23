package eone.ticket.view.managedbeans;

import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;

import eone.ticket.config.AppConfig;
import eone.ticket.service.PromemoriaFrequenzaService;
import eone.ticket.service.PromemoriaFrequenzaService.Destinatario;
import eone.ticket.service.PromemoriaQuotidianoService;
import eone.ticket.service.UserAdminService;

/**
 * Pagina ADMIN per i promemoria (AMS, DISPATCHER, richiedenti/referenti — vedi
 * PromemoriaQuotidianoService): mostra la configurazione corrente, l'elenco
 * dei destinatari con la loro frequenza personale (modificabile qui) e
 * permette di lanciare subito l'invio, rispettando le frequenze oppure
 * forzandolo a tutti (con conferma).
 *
 * In MAIL_DRY_RUN (default finché MAIL_HOST non è configurato) le mail
 * vengono solo loggate, non inviate davvero — vedi il log applicativo.
 */
@CCGenClass(expressionBase = "#{d.PromemoriaAdminUI}")
public class PromemoriaAdminUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public PromemoriaAdminUI() {
        // Secondo livello di difesa: pagina non apribile senza utente autorizzato (vedi SessionGuard)
        eone.ticket.context.SessionGuard.richiediRuolo("PromemoriaAdmin", "ADMIN");
    }

    public interface IListener extends Serializable {
        void reactOnBackToMenu();
    }

    private IListener m_listener;
    private final PromemoriaQuotidianoService promemoriaService = new PromemoriaQuotidianoService();

    private final UserAdminService adminService = new UserAdminService();
    private final PromemoriaFrequenzaService frequenzaService = new PromemoriaFrequenzaService();
    private final FIXGRIDListBinding<DestRow> m_grid = new FIXGRIDListBinding<>();

    private boolean m_invioInCorso;
    private String  m_ultimoEsito = "";

    public void prepare(IListener listener) {
        this.m_listener = listener;
        m_ultimoEsito = "";
        caricaElenco();
    }

    public FIXGRIDListBinding<DestRow> getGrid() { return m_grid; }

    private void caricaElenco() {
        try {
            List<Destinatario> elenco = frequenzaService.elenco();
            m_grid.getItems().clear();
            for (Destinatario d : elenco) m_grid.getItems().add(new DestRow(d));
        } catch (Exception e) {
            Statusbar.outputError("Errore caricamento destinatari: " + e.getMessage());
            System.err.println("[PromemoriaAdminUI] Errore caricaElenco: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void onAggiornaElenco(ActionEvent ae) { caricaElenco(); }

    /** Salva le frequenze modificate in tabella (solo le righe cambiate); se una è non valida non salva nulla. */
    public void onSalvaFrequenze(ActionEvent ae) {
        for (DestRow r : m_grid.getItems()) {
            if (PromemoriaFrequenzaService.parseGiorni(r.m_giorniStr) < 0) {
                Statusbar.outputWarning("Frequenza non valida per " + r.d.idUser + " — indicare un intero da 0 a " +
                    PromemoriaFrequenzaService.MAX_GIORNI + " (0 = mai). Nessuna modifica salvata.");
                return;
            }
        }
        int salvati = 0;
        try {
            for (DestRow r : m_grid.getItems()) {
                int nuovo = PromemoriaFrequenzaService.parseGiorni(r.m_giorniStr);
                if (nuovo != r.d.giorni) {
                    adminService.updatePromemoriaFrequenza(r.d.idUser, nuovo);
                    salvati++;
                }
            }
            Statusbar.outputSuccess(salvati == 0 ? "Nessuna modifica da salvare." : salvati + " frequenze aggiornate.");
        } catch (Exception e) {
            Statusbar.outputError("Errore salvataggio frequenze: " + e.getMessage());
            System.err.println("[PromemoriaAdminUI] Errore onSalvaFrequenze: " + e.getMessage());
            e.printStackTrace();
        }
        caricaElenco();
    }

    public class DestRow extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Destinatario d;
        private String m_giorniStr;
        private final LocalDate oggi = LocalDate.now();
        private String fmt(LocalDate x) { return x.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")); }

        DestRow(Destinatario d) { this.d = d; this.m_giorniStr = String.valueOf(d.giorni); }

        public String getIdUser() { return d.idUser; }
        public String getNome()   { return d.nome; }
        public String getRuolo()  { return d.ruolo; }
        public String getGiorniStr()         { return m_giorniStr; }
        public void   setGiorniStr(String v) { this.m_giorniStr = v; }
        public String getUltimoInvio() { return d.ultimoInvio != null ? fmt(d.ultimoInvio) : "—"; }
        public String getStato() {
            if (d.giorni <= 0) return "Mai (disattivato)";
            if (d.dovutoOggi(oggi)) return "Dovuto oggi";
            return "Dal " + fmt(d.prossimoDovuto());
        }
        public String getStatoColor() {
            if (d.giorni <= 0) return "#EEEEEE";
            return d.dovutoOggi(oggi) ? "#E8F5E9" : "#FFF8E1";
        }
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
            ? "Attivo — controllo ogni giorno feriale alle " + getOrarioSchedulato() + " (ogni utente riceve la mail secondo la propria frequenza)"
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

    /** Come lo scheduler: invia solo a chi è dovuto secondo la propria frequenza. */
    public void onInviaDovuti(ActionEvent ae) {
        eseguiInvio(false);
    }

    /** Ignora le frequenze (non lo 0 = mai): chiede prima conferma, perché raggiunge anche chi riceve la mail di rado. */
    public void onForzaInvio(ActionEvent ae) {
        YESNOPopup ynp = YESNOPopup.createInstance(
            "Conferma invio forzato",
            "Inviare il promemoria a TUTTI i destinatari con attività pendenti, anche a chi ha scelto " +
            "una frequenza più rada (resta escluso solo chi ha 0 = mai)?\n\n" +
            "Le mail partono subito.",
            new YESNOPopup.IYesNoListener() {
                @Override public void reactOnYes() { eseguiInvio(true); }
                @Override public void reactOnNo()  { Statusbar.outputMessage("Invio forzato annullato."); }
            });
        ynp.setHeadline("Invio forzato a tutti");
        ynp.setTextAlign("left");
        ynp.getModalPopup().setWidth(520);
        ynp.getModalPopup().setHeight(260);
        ynp.getModalPopup().hideCloseIcon();
    }

    private void eseguiInvio(boolean forza) {
        m_invioInCorso = true;
        try {
            promemoriaService.eseguiPromemoriaQuotidiano(forza);
            m_ultimoEsito = "Elaborazione " + (forza ? "forzata " : "") + "completata alle " +
                java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")) +
                (getDryRun() ? " — dry-run: nessuna mail realmente inviata, vedi il log applicativo." : ".") +
                " " + promemoriaService.getUltimoRiepilogo() +
                " (non partono mail a chi non ha attività pendenti).";
            Statusbar.outputSuccess("Promemoria elaborato.");
        } catch (Exception e) {
            m_ultimoEsito = "Errore: " + e.getMessage();
            Statusbar.outputError("Errore invio promemoria: " + e.getMessage());
            System.err.println("[PromemoriaAdminUI] Errore invio promemoria: " + e.getMessage());
            e.printStackTrace();
        } finally {
            m_invioInCorso = false;
            caricaElenco();
        }
    }
}
