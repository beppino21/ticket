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
import eone.ticket.model.PasswordPolicy;
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

    public ClienteConfigUI() {
        // Secondo livello di difesa: pagina non apribile senza utente autorizzato (vedi SessionGuard)
        eone.ticket.context.SessionGuard.richiediRuolo("ClienteConfig", "ADMIN");
    }

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
    private String  m_formPrefissoReferente;
    private boolean m_formPromemoriaRichiedentiAbilitato = true;
    // Policy password — numerici come stringa per il binding col campo (stesso schema di UserAdminUI)
    private String  m_formPwdLunghezzaMinimaStr;
    private String  m_formPwdMinSpecialiStr;
    private String  m_formPwdMinNumericiStr;
    private boolean m_formPwdMaiuscMinusc;
    private boolean m_formMfaObbligatoria;
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
        m_formPrefissoReferente = null;
        m_formPromemoriaRichiedentiAbilitato = true;
        caricaPolicyNelForm(PasswordPolicy.defaultPolicy());
        m_formMfaObbligatoria = false;
    }

    private void caricaPolicyNelForm(PasswordPolicy p) {
        m_formPwdLunghezzaMinimaStr = String.valueOf(p.getLunghezzaMinima());
        m_formPwdMinSpecialiStr     = String.valueOf(p.getMinCaratteriSpeciali());
        m_formPwdMinNumericiStr     = String.valueOf(p.getMinCaratteriNumerici());
        m_formPwdMaiuscMinusc       = p.isObbligoMaiuscMinusc();
    }

    /** Converte un campo numerico del form; null (+ warning in statusbar) se non valido o fuori range. */
    private Integer leggiIntero(String valore, String nomeCampo, int min, int max) {
        try {
            int v = Integer.parseInt(valore == null ? "" : valore.trim());
            if (v >= min && v <= max) return v;
        } catch (NumberFormatException e) { /* gestito sotto */ }
        Statusbar.outputWarning(nomeCampo + ": indicare un numero intero tra " + min + " e " + max);
        return null;
    }

    public void onAnnullaForm(ActionEvent ae) {
        m_formVisible = false;
    }

    public void onSalva(ActionEvent ae) {
        if (m_formKunnr == null || m_formKunnr.trim().isEmpty()) {
            Statusbar.outputWarning("Il Kunnr è obbligatorio");
            return;
        }
        if (m_formPrefissoReferente != null && !m_formPrefissoReferente.trim().isEmpty()
                && !m_formPrefissoReferente.trim().matches("[A-Za-z0-9]{1,10}")) {
            Statusbar.outputWarning("Il prefisso può contenere solo lettere e cifre (max 10 caratteri)");
            return;
        }
        Integer lunghezza = leggiIntero(m_formPwdLunghezzaMinimaStr, "Lunghezza minima password",
                                        PasswordPolicy.LIMITE_LUNGHEZZA_MIN, PasswordPolicy.LIMITE_LUNGHEZZA_MAX);
        if (lunghezza == null) return;
        Integer speciali = leggiIntero(m_formPwdMinSpecialiStr, "N. minimo caratteri speciali", 0, PasswordPolicy.LIMITE_CONTEGGI_MAX);
        if (speciali == null) return;
        Integer numerici = leggiIntero(m_formPwdMinNumericiStr, "N. minimo caratteri numerici", 0, PasswordPolicy.LIMITE_CONTEGGI_MAX);
        if (numerici == null) return;
        PasswordPolicy policy = new PasswordPolicy(lunghezza, speciali, numerici, m_formPwdMaiuscMinusc);
        try {
            clienteConfigService.save(m_formKunnr.trim(), m_formNomeCliente, m_formAbilitato, m_formPrefissoReferente,
                                       m_formPromemoriaRichiedentiAbilitato, policy, m_formMfaObbligatoria);
            Statusbar.outputSuccess("Cliente " + m_formKunnr + " salvato");
            m_formVisible = false;
            caricaLista();
        } catch (IllegalStateException e) {
            Statusbar.outputError(e.getMessage());
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

    public String  getFormPrefissoReferente()         { return m_formPrefissoReferente; }
    public void    setFormPrefissoReferente(String v) { this.m_formPrefissoReferente = v; }

    public boolean isFormPromemoriaRichiedentiAbilitato()         { return m_formPromemoriaRichiedentiAbilitato; }
    public void    setFormPromemoriaRichiedentiAbilitato(boolean v) { this.m_formPromemoriaRichiedentiAbilitato = v; }

    public String  getFormPwdLunghezzaMinimaStr()          { return m_formPwdLunghezzaMinimaStr; }
    public void    setFormPwdLunghezzaMinimaStr(String v)  { this.m_formPwdLunghezzaMinimaStr = v; }

    public String  getFormPwdMinSpecialiStr()              { return m_formPwdMinSpecialiStr; }
    public void    setFormPwdMinSpecialiStr(String v)      { this.m_formPwdMinSpecialiStr = v; }

    public String  getFormPwdMinNumericiStr()              { return m_formPwdMinNumericiStr; }
    public void    setFormPwdMinNumericiStr(String v)      { this.m_formPwdMinNumericiStr = v; }

    public boolean isFormMfaObbligatoria()                 { return m_formMfaObbligatoria; }
    public void    setFormMfaObbligatoria(boolean v)       { this.m_formMfaObbligatoria = v; }

    public boolean isFormPwdMaiuscMinusc()                 { return m_formPwdMaiuscMinusc; }
    public void    setFormPwdMaiuscMinusc(boolean v)       { this.m_formPwdMaiuscMinusc = v; }

    public class ClienteRow extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final ClienteConfig config;

        public ClienteRow(ClienteConfig config) { this.config = config; }

        public String  getKunnr()       { return config.getKunnr(); }
        public String  getNomeCliente() { return config.getNomeCliente(); }
        public String  getPrefissoReferente() { return config.getPrefissoReferente() != null ? config.getPrefissoReferente() : ""; }
        public String  getStatoLabel()  { return config.isAbilitato() ? "Abilitato" : "Non abilitato"; }
        public String  getStatoColor()  { return config.isAbilitato() ? "#E8F5E9" : "#FFEBEE"; }
        public String  getPromemoriaRichiedentiLabel() { return config.isPromemoriaRichiedentiAbilitato() ? "Sì" : "No"; }
        public String  getPasswordPolicyLabel() { return config.getPasswordPolicy().getDescrizioneBreve(); }
        public String  getMfaLabel()            { return config.isMfaObbligatoria() ? "Obbligatoria" : "No"; }

        public void onRowSelect() {
            m_isNuovo = false;
            m_formVisible = true;
            m_formKunnr = config.getKunnr();
            m_formNomeCliente = config.getNomeCliente();
            m_formAbilitato = config.isAbilitato();
            m_formPrefissoReferente = config.getPrefissoReferente();
            m_formPromemoriaRichiedentiAbilitato = config.isPromemoriaRichiedentiAbilitato();
            caricaPolicyNelForm(config.getPasswordPolicy());
            m_formMfaObbligatoria = config.isMfaObbligatoria();
        }
    }
}