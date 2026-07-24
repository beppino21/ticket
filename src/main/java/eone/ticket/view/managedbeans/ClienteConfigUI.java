package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.util.List;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import eone.ticket.model.ClienteConfig;
import eone.ticket.service.ClienteConfigService;

/**
 * Amministrazione clienti (Kunnr) abilitati alla nuova gestione ticket —
 * usata durante la migrazione cliente-per-cliente dalla vecchia procedura.
 * Un Kunnr assente dall'elenco è considerato non abilitato: i suoi ticket
 * restano esclusi dalle viste AMS/DISPATCHER.
 */
@CCGenClass(expressionBase = "#{d.ClienteConfigUI}")
public class ClienteConfigUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void reactOnBackToMenu();
    }

    private IListener m_listener;
    private final ClienteConfigService clienteConfigService = new ClienteConfigService();

    private FIXGRIDListBinding<ClienteRow> m_grid = new FIXGRIDListBinding<>();

    private boolean m_formVisible;
    private String  m_formKunnr;
    private String  m_formNomeCliente;
    private boolean m_formAbilitato = true;
    private boolean m_isNuovo;

    public void prepare(IListener listener) {
        this.m_listener = listener;
        caricaLista();
    }

    private void caricaLista() {
        try {
            List<ClienteConfig> clienti = clienteConfigService.listAll();
            m_grid.getItems().clear();
            for (ClienteConfig c : clienti) m_grid.getItems().add(new ClienteRow(c));
            Statusbar.outputSuccess(clienti.size() + " clienti configurati");
        } catch (Exception e) {
            Statusbar.outputError("Errore caricamento: " + e.getMessage());
            System.err.println("[ClienteConfigUI] Errore caricaLista: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void backToMenu(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnBackToMenu();
    }

    public void refresh(ActionEvent ae) {
        caricaLista();
    }

    public void onNuovo(ActionEvent ae) {
        m_isNuovo = true;
        m_formVisible = true;
        m_formKunnr = null;
        m_formNomeCliente = null;
        m_formAbilitato = true;
    }

    public void onAnnullaForm(ActionEvent ae) {
        m_formVisible = false;
    }

    public void onSalva(ActionEvent ae) {
        if (m_formKunnr == null || m_formKunnr.trim().isEmpty()) {
            Statusbar.outputWarning("Il Kunnr è obbligatorio");
            return;
        }
        try {
            clienteConfigService.save(m_formKunnr.trim(), m_formNomeCliente, m_formAbilitato);
            Statusbar.outputSuccess("Cliente " + m_formKunnr + " salvato");
            m_formVisible = false;
            caricaLista();
        } catch (Exception e) {
            Statusbar.outputError("Errore salvataggio: " + e.getMessage());
            System.err.println("[ClienteConfigUI] Errore onSalva: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void onElimina(ActionEvent ae) {
        if (m_formKunnr == null) return;
        try {
            clienteConfigService.delete(m_formKunnr);
            Statusbar.outputSuccess("Cliente " + m_formKunnr + " rimosso dall'elenco (ora non abilitato)");
            m_formVisible = false;
            caricaLista();
        } catch (Exception e) {
            Statusbar.outputError("Errore eliminazione: " + e.getMessage());
            System.err.println("[ClienteConfigUI] Errore onElimina: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override public String getPageName()                 { return "/ClienteConfig.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.ClienteConfigUI}"; }

    public FIXGRIDListBinding<ClienteRow> getGrid() { return m_grid; }

    public boolean isFormVisible()          { return m_formVisible; }
    public boolean isIsNuovo()              { return m_isNuovo; }
    public boolean isShowEliminaButton()    { return !m_isNuovo; }
    public boolean isFormKunnrEditable()    { return m_isNuovo; }

    public String  getFormKunnr()           { return m_formKunnr; }
    public void    setFormKunnr(String v)   { this.m_formKunnr = v; }

    public String  getFormNomeCliente()         { return m_formNomeCliente; }
    public void    setFormNomeCliente(String v) { this.m_formNomeCliente = v; }

    public boolean isFormAbilitato()           { return m_formAbilitato; }
    public void    setFormAbilitato(boolean v) { this.m_formAbilitato = v; }

    public class ClienteRow extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final ClienteConfig config;

        public ClienteRow(ClienteConfig config) { this.config = config; }

        public String  getKunnr()       { return config.getKunnr(); }
        public String  getNomeCliente() { return config.getNomeCliente(); }
        public String  getStatoLabel()  { return config.isAbilitato() ? "Abilitato" : "Non abilitato"; }
        public String  getStatoColor()  { return config.isAbilitato() ? "#E8F5E9" : "#FFEBEE"; }

        public void onRowSelect() {
            m_isNuovo = false;
            m_formVisible = true;
            m_formKunnr = config.getKunnr();
            m_formNomeCliente = config.getNomeCliente();
            m_formAbilitato = config.isAbilitato();
        }
    }
}