package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.RequesterInfo;
import eone.ticket.model.Ticket;
import eone.ticket.model.TicketRiassegnazione;
import eone.ticket.service.MailService;
import eone.ticket.service.RequesterService;
import eone.ticket.service.SAPTicketService;
import eone.ticket.service.TicketRiassegnazioneService;

/**
 * UI per le richieste di riattribuzione ticket (ticket_riassegnazione).
 *
 * Due modalità, stessa schermata (stesso pattern dual-mode di ReferentiUI):
 *  - AMS (m_modeDispatcher=false): vede/gestisce SOLO le proprie richieste —
 *    può aprirne di nuove (su un ticket a lui assegnato, Amusr), modificarne
 *    il testo o cancellarle finché sono APERTA.
 *  - DISPATCHER/ADMIN (m_modeDispatcher=true): vede TUTTE le richieste aperte
 *    da qualunque AMS, può aprirne il dettaglio (sola lettura sui dati) e
 *    RIFIUTARLE — non cancellazione fisica: la riga resta come storico,
 *    marcata CONCLUSA con nuovo_amusr="DELETED" (vedi
 *    TicketRiassegnazione.isRifiutata()), col richiedente avvisato via mail
 *    che il ticket resta assegnato a lui. La riattribuzione VERA avviene
 *    invece sul backend SAP, fuori da quest'app.
 *
 * In entrambe le modalità un toggle "Mostra storico" passa dalla vista
 * attiva (APERTA) a quella conclusa (CONCLUSA) — sola lettura in entrambi
 * i casi quando si è in storico.
 *
 * Auto-chiusura (Amusr cambiato -> CONCLUSA): la modifica reale avviene sul
 * backend SAP dal DISPATCHER, fuori da quest'app, e non è notificata in
 * tempo reale — va quindi VERIFICATA ad ogni rilettura. Oltre all'innesto
 * in TicketListUI (che la verifica sulla lista ticket generale, quando
 * disponibile), questa schermata la verifica autonomamente ad ogni
 * caricamento/aggiornamento della vista attiva, interrogando SAP
 * direttamente sui soli tickt con una richiesta APERTA — così la richiesta
 * si chiude da sola anche se questa è l'unica schermata che l'utente apre.
 * concluded_at prende la data della rilettura che se ne accorge per prima,
 * non quella (ignota) del cambio reale su SAP.
 */
@CCGenClass(expressionBase = "#{d.RiassegnazioneUI}")
public class RiassegnazioneUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void reactOnBackToMenu();
    }

    private IListener m_listener;

    private final TicketRiassegnazioneService riassegnazioneService = new TicketRiassegnazioneService();
    private final SAPTicketService sapService = new SAPTicketService();
    private final RequesterService requesterService = new RequesterService();
    private final MailService mailService = new MailService();

    private boolean m_modeDispatcher; // true = DISPATCHER/ADMIN
    private boolean m_storico;        // true = vista storico (CONCLUSA)

    private FIXGRIDListBinding<RigaRichiesta> m_grid = new FIXGRIDListBinding<>();

    // Form nuova richiesta — solo AMS, solo vista attiva
    private final ValidValuesBinding m_ticketVVB = new ValidValuesBinding();
    private boolean m_formNuovaVisible;
    private String  m_nuovoTickt;
    private String  m_nuovoTesto;

    // Form modifica testo — solo AMS, riga selezionata, solo vista attiva
    private boolean m_formModificaVisible;
    private long    m_selezionatoId;
    private String  m_selezionatoTickt;
    private String  m_testoModifica;

    // Dettaglio — solo DISPATCHER, riga selezionata (attiva o storico)
    private boolean m_formDettaglioVisible;
    private long    m_dettaglioId;
    private String  m_dettaglioTickt;
    private String  m_dettaglioAmusrRichiedente;
    private String  m_dettaglioTesto;
    private String  m_dettaglioCreatedAtFormatted;
    private String  m_dettaglioNuovoAmusrLabel;
    private String  m_dettaglioConcludedAtFormatted;

    // Doppio-click di conferma, condiviso tra elimina (AMS) e rifiuta (DISPATCHER) — un solo id alla volta selezionato
    private Long m_confermaWaitingId;

    public class RigaRichiesta extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final TicketRiassegnazione r;

        public RigaRichiesta(TicketRiassegnazione r) { this.r = r; }

        public long   getId()               { return r.getId(); }
        public String getTickt()            { return nn(r.getTickt()); }
        public String getAmusrRichiedente() { return nn(r.getAmusrRichiedente()); }
        public String getTesto()            { return nn(r.getTesto()); }
        public String getNuovoAmusr()       { return r.getNuovoAmusrLabel(); }
        public String getCreatedAtFormatted()   { return r.getCreatedAtFormatted(); }
        public String getConcludedAtFormatted() { return r.getConcludedAtFormatted(); }

        public void onSeleziona(ActionEvent ae) {
            if (m_modeDispatcher) {
                apriDettaglio(r);
                return;
            }
            if (m_storico) return; // AMS in storico resta sola lettura da griglia, nessun form dedicato
            m_formNuovaVisible = false;
            m_formDettaglioVisible = false;
            m_formModificaVisible = true;
            m_selezionatoId = r.getId();
            m_selezionatoTickt = r.getTickt();
            m_testoModifica = r.getTesto();
            m_confermaWaitingId = null;
        }

        private String nn(String s) { return s != null ? s : ""; }
    }

    private void apriDettaglio(TicketRiassegnazione r) {
        m_formNuovaVisible = false;
        m_formModificaVisible = false;
        m_formDettaglioVisible = true;
        m_dettaglioId = r.getId();
        m_dettaglioTickt = r.getTickt();
        m_dettaglioAmusrRichiedente = r.getAmusrRichiedente();
        m_dettaglioTesto = r.getTesto();
        m_dettaglioCreatedAtFormatted = r.getCreatedAtFormatted();
        m_dettaglioNuovoAmusrLabel = r.getNuovoAmusrLabel();
        m_dettaglioConcludedAtFormatted = r.getConcludedAtFormatted();
        m_confermaWaitingId = null;
    }

    // =========================
    // INIT
    // =========================

    public void prepare(boolean modeDispatcher, IListener listener) {
        this.m_listener = listener;
        this.m_modeDispatcher = modeDispatcher;
        this.m_storico = false;
        this.m_formNuovaVisible = false;
        this.m_formModificaVisible = false;
        this.m_formDettaglioVisible = false;
        caricaLista();
        if (!m_modeDispatcher) caricaTicketDisponibili();
    }

    private void caricaLista() {
        if (!m_storico) verificaAutoChiusura();
        m_grid.getItems().clear();
        try {
            ViewSessionContext ctx = ViewSessionContext.instance();
            List<TicketRiassegnazione> lista;
            if (m_modeDispatcher) {
                lista = m_storico ? riassegnazioneService.listStoricoAll() : riassegnazioneService.listAperteAll();
            } else {
                String amusr = ctx.getUsername();
                lista = m_storico ? riassegnazioneService.listStoricoByRichiedente(amusr)
                                   : riassegnazioneService.listAperteByRichiedente(amusr);
            }
            for (TicketRiassegnazione r : lista) m_grid.getItems().add(new RigaRichiesta(r));
            String tipo = m_storico ? "concluse" : "aperte";
            Statusbar.outputSuccess(lista.size() + " richieste di riattribuzione " + tipo);
        } catch (Exception e) {
            Statusbar.outputError("Errore caricamento richieste: " + e.getMessage());
            System.err.println("[RiassegnazioneUI] Errore caricaLista: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * La riattribuzione reale avviene sul backend SAP e non è notificata in
     * tempo reale a quest'app: prima di mostrare la vista attiva, si
     * rilegge da SAP l'Amusr corrente di ciascun tickt con una richiesta
     * ancora APERTA (nel perimetro della modalità corrente) e si chiude
     * chi risulta cambiato. Un errore sul singolo ticket non blocca gli
     * altri; concluded_at prende la data di QUESTA rilettura, non quella
     * (ignota) del cambio reale su SAP.
     */
    private void verificaAutoChiusura() {
        try {
            ViewSessionContext ctx = ViewSessionContext.instance();
            List<TicketRiassegnazione> aperteAttuali = m_modeDispatcher
                ? riassegnazioneService.listAperteAll()
                : riassegnazioneService.listAperteByRichiedente(ctx.getUsername());
            if (aperteAttuali.isEmpty()) return;

            Map<String, String> amusrPerTickt = new HashMap<>();
            for (TicketRiassegnazione r : aperteAttuali) {
                try {
                    Ticket t = sapService.getTicketById(r.getTickt());
                    if (t != null && t.getAmusr() != null && !t.getAmusr().trim().isEmpty()) {
                        amusrPerTickt.put(r.getTickt(), t.getAmusr());
                    }
                } catch (Exception e) {
                    System.err.println("[RiassegnazioneUI] Errore lettura SAP per " + r.getTickt() + ": " + e.getMessage());
                }
            }
            riassegnazioneService.chiudiSeCambiate(amusrPerTickt);
        } catch (Exception e) {
            System.err.println("[RiassegnazioneUI] Errore verifica auto-chiusura: " + e.getMessage());
        }
    }

    /**
     * Solo AMS, vista attiva: elenco dei propri ticket assegnati (Amusr),
     * non ancora chiusi (CLO/RES/CAN esclusi) e senza già una richiesta
     * APERTA — per la combobox "Nuova richiesta".
     */
    private void caricaTicketDisponibili() {
        m_ticketVVB.clear();
        if (m_storico) return;
        try {
            ViewSessionContext ctx = ViewSessionContext.instance();
            String amusr = ctx.getUsername();
            if (amusr == null || amusr.trim().isEmpty()) return;

            SAPTicketService.TicketResponse response = sapService.getTickets(null, null, null, null, null, null);
            if (!response.isSuccess()) {
                System.err.println("[RiassegnazioneUI] Errore SAP caricamento ticket disponibili: " + response.getErrorMessage());
                return;
            }
            for (Ticket t : response.getTickets()) {
                if (t.getTickt() == null || t.getTickt().isEmpty()) continue;
                if (!amusr.equalsIgnoreCase(t.getAmusr())) continue;
                if ("CLO".equals(t.getRstat()) || "RES".equals(t.getRstat()) || "CAN".equals(t.getRstat())) continue;
                if (riassegnazioneService.getAperta(t.getTickt()) != null) continue; // già richiesta aperta
                String label = t.getTickt() + " — " + (t.getTitle() != null ? t.getTitle() : "");
                m_ticketVVB.addValidValue(t.getTickt(), label);
            }
        } catch (Exception e) {
            System.err.println("[RiassegnazioneUI] Errore caricaTicketDisponibili: " + e.getMessage());
        }
    }

    // =========================
    // AZIONI COMUNI
    // =========================

    public void backToMenu(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnBackToMenu();
    }

    public void refresh(ActionEvent ae) {
        caricaLista();
        if (!m_modeDispatcher) caricaTicketDisponibili();
    }

    public void onToggleStorico(ActionEvent ae) {
        m_storico = !m_storico;
        m_formNuovaVisible = false;
        m_formModificaVisible = false;
        m_formDettaglioVisible = false;
        caricaLista();
        if (!m_modeDispatcher) caricaTicketDisponibili();
    }

    // =========================
    // AZIONI AMS — nuova richiesta
    // =========================

    public void onNuovaRichiesta(ActionEvent ae) {
        if (m_ticketVVB.size() == 0) {
            Statusbar.outputWarning("Nessun ticket disponibile: hai già richieste aperte su tutti i tuoi ticket assegnati, oppure non hai ticket attivi.");
            return;
        }
        m_formModificaVisible = false;
        m_formNuovaVisible = true;
        m_nuovoTickt = null;
        m_nuovoTesto = null;
    }

    public void onAnnullaNuova(ActionEvent ae) {
        m_formNuovaVisible = false;
    }

    public void onSalvaNuova(ActionEvent ae) {
        if (m_nuovoTickt == null || m_nuovoTickt.trim().isEmpty()) {
            Statusbar.outputWarning("Selezionare il ticket");
            return;
        }
        if (m_nuovoTesto == null || m_nuovoTesto.trim().isEmpty()) {
            Statusbar.outputWarning("Indicare il motivo della richiesta");
            return;
        }
        try {
            ViewSessionContext ctx = ViewSessionContext.instance();
            riassegnazioneService.create(m_nuovoTickt.trim(), ctx.getUsername(), m_nuovoTesto.trim());
            notificaNuovaRichiesta(m_nuovoTickt.trim(), ctx.getUsername(), m_nuovoTesto.trim());
            Statusbar.outputSuccess("Richiesta di riattribuzione inviata al DISPATCHER per il ticket " + m_nuovoTickt);
            m_formNuovaVisible = false;
            caricaLista();
            caricaTicketDisponibili();
        } catch (Exception e) {
            Statusbar.outputError("Errore invio richiesta: " + e.getMessage());
            System.err.println("[RiassegnazioneUI] Errore onSalvaNuova: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Notifica via mail tutti i DISPATCHER attivi — stesso meccanismo usato per i nuovi DRAFT. */
    private void notificaNuovaRichiesta(String tickt, String amusrRichiedente, String testo) {
        try {
            List<eone.ticket.model.RequesterInfo> dispatchers = requesterService.getActiveDispatchers();
            if (dispatchers.isEmpty()) {
                System.out.println("[RiassegnazioneUI] Notifica DISPATCHER saltata: nessun DISPATCHER attivo con email");
                return;
            }
            for (eone.ticket.model.RequesterInfo dispatcher : dispatchers) {
                if (dispatcher.getEmail() == null || dispatcher.getEmail().trim().isEmpty()) continue;
                try {
                    mailService.sendNotificaNuovaRiassegnazione(dispatcher.getEmail(), tickt, amusrRichiedente, testo);
                } catch (Exception e) {
                    System.err.println("[RiassegnazioneUI] Errore invio notifica a DISPATCHER " + dispatcher.getId_user() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[RiassegnazioneUI] Errore recupero DISPATCHER per notifica: " + e.getMessage());
        }
    }

    // =========================
    // AZIONI AMS — modifica/elimina propria richiesta
    // =========================

    public void onAnnullaModifica(ActionEvent ae) {
        m_formModificaVisible = false;
        m_confermaWaitingId = null;
    }

    public void onSalvaModifica(ActionEvent ae) {
        if (m_testoModifica == null || m_testoModifica.trim().isEmpty()) {
            Statusbar.outputWarning("Il testo non può essere vuoto");
            return;
        }
        try {
            ViewSessionContext ctx = ViewSessionContext.instance();
            boolean ok = riassegnazioneService.updateTesto(m_selezionatoId, ctx.getUsername(), m_testoModifica.trim());
            if (ok) {
                Statusbar.outputSuccess("Richiesta aggiornata");
            } else {
                Statusbar.outputWarning("La richiesta non è più modificabile — probabilmente è stata conclusa nel frattempo. Aggiorno la lista.");
            }
            m_formModificaVisible = false;
            caricaLista();
        } catch (Exception e) {
            Statusbar.outputError("Errore aggiornamento: " + e.getMessage());
            System.err.println("[RiassegnazioneUI] Errore onSalvaModifica: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Primo click: chiede conferma. Secondo click: cancella davvero (solo la propria, ancora APERTA). */
    public void onElimina(ActionEvent ae) {
        if (m_confermaWaitingId == null || m_confermaWaitingId != m_selezionatoId) {
            m_confermaWaitingId = m_selezionatoId;
            Statusbar.outputWarning("Premi di nuovo \"Elimina\" per confermare la cancellazione della richiesta.");
            return;
        }
        try {
            ViewSessionContext ctx = ViewSessionContext.instance();
            boolean ok = riassegnazioneService.delete(m_selezionatoId, ctx.getUsername());
            if (ok) {
                Statusbar.outputSuccess("Richiesta cancellata");
            } else {
                Statusbar.outputWarning("La richiesta non è più cancellabile — probabilmente è stata conclusa nel frattempo.");
            }
            m_formModificaVisible = false;
            caricaLista();
            caricaTicketDisponibili();
        } catch (Exception e) {
            Statusbar.outputError("Errore cancellazione: " + e.getMessage());
            System.err.println("[RiassegnazioneUI] Errore onElimina: " + e.getMessage());
            e.printStackTrace();
        } finally {
            m_confermaWaitingId = null;
        }
    }

    // =========================
    // AZIONI DISPATCHER — dettaglio/rifiuto
    // =========================

    public void onAnnullaDettaglio(ActionEvent ae) {
        m_formDettaglioVisible = false;
        m_confermaWaitingId = null;
    }

    /**
     * Primo click: chiede conferma. Secondo click: rifiuta davvero — la
     * riga resta come storico (CONCLUSA, nuovo_amusr="DELETED",
     * concluded_at=ora) e il richiedente riceve una mail: il ticket resta
     * assegnato a lui.
     */
    public void onRifiuta(ActionEvent ae) {
        if (m_confermaWaitingId == null || m_confermaWaitingId != m_dettaglioId) {
            m_confermaWaitingId = m_dettaglioId;
            Statusbar.outputWarning("Premi di nuovo \"Rifiuta\" per confermare — il richiedente riceverà una mail di notifica.");
            return;
        }
        try {
            TicketRiassegnazione aggiornata = riassegnazioneService.rifiuta(m_dettaglioId);
            if (aggiornata != null) {
                notificaRifiuto(aggiornata);
                Statusbar.outputSuccess("Richiesta rifiutata — notifica inviata a " + aggiornata.getAmusrRichiedente());
            } else {
                Statusbar.outputWarning("La richiesta non è più APERTA — probabilmente già conclusa nel frattempo (es. Amusr cambiato su SAP).");
            }
            m_formDettaglioVisible = false;
            caricaLista();
        } catch (Exception e) {
            Statusbar.outputError("Errore rifiuto richiesta: " + e.getMessage());
            System.err.println("[RiassegnazioneUI] Errore onRifiuta: " + e.getMessage());
            e.printStackTrace();
        } finally {
            m_confermaWaitingId = null;
        }
    }

    private void notificaRifiuto(TicketRiassegnazione r) {
        try {
            RequesterInfo richiedente = requesterService.getById(r.getAmusrRichiedente());
            if (richiedente != null && richiedente.getEmail() != null && !richiedente.getEmail().trim().isEmpty()) {
                mailService.sendNotificaRiassegnazioneRifiutata(richiedente.getEmail(), r.getTickt(), r.getTesto());
            } else {
                System.out.println("[RiassegnazioneUI] Nessuna email per " + r.getAmusrRichiedente() + " — notifica rifiuto non inviata");
            }
        } catch (Exception e) {
            System.err.println("[RiassegnazioneUI] Errore invio notifica rifiuto: " + e.getMessage());
        }
    }

    // =========================
    // GETTERS / SETTERS
    // =========================

    @Override public String getPageName()                 { return "/Riassegnazione.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.RiassegnazioneUI}"; }

    public boolean getModeDispatcher() { return m_modeDispatcher; }
    public boolean getModeAms()        { return !m_modeDispatcher; }
    public boolean getStorico()        { return m_storico; }
    public String  getToggleStoricoLabel() { return m_storico ? "« Attive" : "Storico »"; }
    public String  getTitolo() {
        if (m_modeDispatcher) return m_storico ? "Riattribuzioni concluse (tutte)" : "Richieste di riattribuzione aperte";
        return m_storico ? "Le mie riattribuzioni concluse" : "Le mie richieste di riattribuzione";
    }

    /** Bottone "Nuova richiesta": solo AMS, solo vista attiva. */
    public boolean getShowNuovaRichiestaButton() { return !m_modeDispatcher && !m_storico && !m_formNuovaVisible; }

    public FIXGRIDListBinding<RigaRichiesta> getGrid() { return m_grid; }

    public ValidValuesBinding getTicketVVB() { return m_ticketVVB; }

    public boolean getFormNuovaVisible() { return m_formNuovaVisible; }
    public String  getNuovoTickt()        { return m_nuovoTickt; }
    public void    setNuovoTickt(String v){ this.m_nuovoTickt = v; }
    public String  getNuovoTesto()        { return m_nuovoTesto; }
    public void    setNuovoTesto(String v){ this.m_nuovoTesto = v; }

    public boolean getFormModificaVisible() { return m_formModificaVisible; }
    public String  getSelezionatoTickt()    { return nn(m_selezionatoTickt); }
    public String  getTestoModifica()        { return m_testoModifica; }
    public void    setTestoModifica(String v){ this.m_testoModifica = v; }

    public boolean getFormDettaglioVisible()          { return m_formDettaglioVisible; }
    public String  getDettaglioTickt()                { return nn(m_dettaglioTickt); }
    public String  getDettaglioAmusrRichiedente()     { return nn(m_dettaglioAmusrRichiedente); }
    public String  getDettaglioTesto()                { return nn(m_dettaglioTesto); }
    public String  getDettaglioCreatedAtFormatted()   { return nn(m_dettaglioCreatedAtFormatted); }
    public String  getDettaglioNuovoAmusrLabel()      { return nn(m_dettaglioNuovoAmusrLabel); }
    public String  getDettaglioConcludedAtFormatted() { return nn(m_dettaglioConcludedAtFormatted); }
    /** Bottone "Rifiuta": solo su una richiesta ancora APERTA (vista attiva, non storico). */
    public boolean getShowRifiutaButton() { return m_modeDispatcher && m_formDettaglioVisible && !m_storico; }
    /** Riquadro "Esito" nel dettaglio: solo in storico (in vista attiva l'esito non esiste ancora). */
    public boolean getShowEsitoStorico() { return m_formDettaglioVisible && m_storico; }
    /** Nessun pannello aperto a destra — precalcolato in Java: EL con più "and"/"!" concatenati si è già dimostrato inaffidabile in CC (vedi ReferentiUI). */
    public boolean getNessunFormVisible() { return !m_formNuovaVisible && !m_formModificaVisible && !m_formDettaglioVisible; }

    private String nn(String s) { return s != null ? s : ""; }
}