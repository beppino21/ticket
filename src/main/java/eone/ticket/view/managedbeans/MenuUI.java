package eone.ticket.view.managedbeans;

import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.ModalPopup;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.pagebean.PageBean;
import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.RequesterInfo;
import eone.ticket.model.TicketSummary;
import eone.ticket.service.TicketRiassegnazioneService;


/**
 * Menu principale post-logon: punto di ingresso che mostra le funzioni
 * disponibili come voci selezionabili (card), simile a un mini-workplace
 * senza la complessità del framework Workplace completo (function tree,
 * perspective, container multipli) — non necessaria per un menu a poche voci.
 *
 * Ogni voce richiama un callback su OutestUI per sostituire il contentUI
 * con la pagina corrispondente, esattamente come avviene oggi dopo il logon.
 */

@CCGenClass(expressionBase = "#{d.MenuUI}")
public class MenuUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void reactOnMenuChoice(String choiceId);

        /**
         * Richiesta di uscita dall'applicazione, confermata dall'utente.
         * MenuUI non ha accesso al t:clientcloser (che vive nel
         * beanprocessing di Outest.xml, unica pagina realmente caricata),
         * quindi delega a OutestUI l'eventuale logout applicativo e
         * l'attivazione del trigger di chiusura client.
         */
        void reactOnExitRequest();

        /** Richiesta di logout dal bottone "Logout" — stesso comportamento del bottone in TicketList */
        default void reactOnLogoutRequest() {}
    }

    private IListener m_listener;
    private FIXGRIDListBinding<MenuItemInfo> m_items = new FIXGRIDListBinding<>();
    private TicketSummary m_summary;
    private boolean m_summaryLoaded = false;
    private final TicketRiassegnazioneService riassegnazioneService = new TicketRiassegnazioneService();

    // =========================
    // EXIT APPLICAZIONE
    // =========================

    private boolean m_exitConfirmVisible = false;

    /** Click sull'icona uscita in alto a destra — mostra il pannello di conferma. */
    public void onExitClick(ActionEvent ae) {
        m_exitConfirmVisible = true;
    }

    /** Conferma: chiude il pannello e delega a OutestUI la chiusura client-side. */
    public void onExitConfirmYes(ActionEvent ae) {
        m_exitConfirmVisible = false;
        if (m_listener != null) {
            m_listener.reactOnExitRequest();
        }
    }

    /** Annulla: richiude il pannello senza fare nulla. */
    public void onExitConfirmNo(ActionEvent ae) {
        m_exitConfirmVisible = false;
    }

    public boolean getExitConfirmVisible() { return m_exitConfirmVisible; }

    /** Bottone "Logout" nel Menu — stesso comportamento del bottone in TicketList */
    public void logout(ActionEvent ae) {
        if (m_listener != null) {
            m_listener.reactOnLogoutRequest();
        }
    }

    // =========================
    // POLICY PASSWORD (v3)
    // =========================
    // Niente "blocco secco": la password scaduta non impedisce l'uso
    // dell'app. Mostriamo un banner (10 giorni prima della scadenza, poi
    // sempre finché non viene cambiata) e proponiamo automaticamente il
    // popup di cambio password una sola volta per sessione — l'utente può
    // sempre annullarlo e continuare a usare l'app normalmente.

    private boolean m_passwordPolicyChecked = false;
    private boolean m_passwordWarningVisible = false;
    private String  m_passwordWarningMessage = "";

    public boolean getPasswordWarningVisible() { return m_passwordWarningVisible; }
    public String  getPasswordWarningMessage() { return m_passwordWarningMessage; }

    private void checkPasswordPolicy() {
        if (m_passwordPolicyChecked) return; // solo al primo ingresso in Menu dopo il logon
        m_passwordPolicyChecked = true;

        RequesterInfo info = ViewSessionContext.instance().getRequesterInfo();
        if (info == null) return; // caso legacy SAP senza RequesterInfo — nessuna policy da applicare

        if (info.isPasswordScaduta()) {
            m_passwordWarningVisible = true;
            m_passwordWarningMessage = "La tua password è scaduta. Ti consigliamo di cambiarla appena possibile.";
            apriPopupCambioPassword(); // proposto automaticamente, ma annullabile — nessun blocco
        } else if (info.isPasswordInAvvisoScadenza()) {
            long giorni = info.getGiorniAllaScadenzaPassword();
            m_passwordWarningVisible = true;
            m_passwordWarningMessage = giorni == 0
                ? "La tua password scade oggi."
                : "La tua password scade tra " + giorni + " giorn" + (giorni == 1 ? "o" : "i") + ".";
        }
    }

    /** Bottone "Cambia password" nel banner di avviso. */
    public void onCambiaPasswordClick(ActionEvent ae) {
        apriPopupCambioPassword();
    }

    private void apriPopupCambioPassword() {
        ViewSessionContext ctx = ViewSessionContext.instance();
        final ChangePasswordUI cpUI = new ChangePasswordUI();
        cpUI.prepare(
            ctx.getUsername() != null ? ctx.getUsername() : "",
            new ChangePasswordUI.IListener() {
                @Override
                public void reactOnPasswordChanged() {
                    closePopup(cpUI);
                    m_passwordWarningVisible = false;
                    // Aggiorna lo stato in sessione così la policy resta coerente
                    // senza dover rifare il logon.
                    RequesterInfo info = ViewSessionContext.instance().getRequesterInfo();
                    if (info != null) info.setPasswordImpostataIl(java.time.LocalDateTime.now());
                    Statusbar.outputSuccess("Password aggiornata.");
                }
                @Override
                public void reactOnCancel() {
                    closePopup(cpUI); // annullabile — l'utente prosegue senza vincoli
                }
            }
        );

        openModalPopup(
            cpUI,
            "Cambio Password",
            440,
            510,
            new ModalPopup.IModalPopupListener() {
                @Override
                public void reactOnPopupClosedByUser() {
                    closePopup(cpUI);
                }
            }
        );
    }

    public void updateSummary(TicketSummary summary) {
        // Non sovrascrivere il summary completo caricato al logon
        // con quello parziale della lista operativa (che esclude CLO/CAN)
        if (m_summaryLoaded) return;
        this.m_summary = summary;
        m_summaryLoaded = true;
    }

    /** Forza l'aggiornamento — usato solo da OutestUI al logon */
    public void forceUpdateSummary(TicketSummary summary) {
        this.m_summary = summary;
        m_summaryLoaded = true;
    }

    private TicketSummary m_summaryReferente;

    /**
     * Riepilogo "Ticket come Referente" (campo SAP Refer) — solo per AMS,
     * null se non applicabile o se non ci sono ticket di questo tipo (il
     * box in Menu.xml resta nascosto in quel caso). Forzato da OutestUI al
     * logon, stesso pattern di forceUpdateSummary().
     */
    public void forceUpdateSummaryReferente(TicketSummary summary) {
        this.m_summaryReferente = summary;
    }

    // =========================
    // INNER CLASS — VOCE DI MENU
    // =========================

    public class MenuItemInfo extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String  id;
        private final String  titolo;
        private final String  descrizione;
        private final boolean abilitato;
        private int m_badgeCount; // 0 = nessun badge — richieste di riattribuzione aperte, vedi buildMenu()

        public MenuItemInfo(String id, String titolo, String descrizione, boolean abilitato) {
            this.id = id;
            this.titolo = titolo;
            this.descrizione = descrizione;
            this.abilitato = abilitato;
        }

        public String  getTitolo()      { return titolo; }
        public String  getDescrizione() { return descrizione; }
        public boolean getAbilitato()   { return abilitato; }

        public void setBadgeCount(int v) { this.m_badgeCount = v; }
        public boolean getHasBadge()     { return m_badgeCount > 0; }
        public String  getBadgeLabel()   {
            return m_badgeCount + (m_badgeCount == 1 ? " richiesta di riattribuzione aperta" : " richieste di riattribuzione aperte");
        }
        /** Colore fisso e acceso — deve saltare all'occhio, non è legato allo stato abilitato/disabilitato della card. */
        public String getBadgeColor() { return "#C62828"; }

        /** Colore di sfondo della card: grigio chiaro se disabilitata */
        public String getBackground() {
            return abilitato ? "#FFFFFF" : "#F0F0F0";
        }

        public String getTextColor() {
            return abilitato ? "#000000" : "#999999";
        }

        public void onSelect(ActionEvent ae) {
            if (!abilitato) {
                Statusbar.outputWarning(titolo + " — funzione non ancora disponibile");
                return;
            }
            if (m_listener != null) {
                m_listener.reactOnMenuChoice(id);
            }
        }
    }

    // =========================
    // COSTRUTTORE / INIT
    // =========================

    public MenuUI() {}

    public void prepare(IListener listener) {
        m_listener = listener;
        buildMenu();
        checkPasswordPolicy();
    }

    private void buildMenu() {
        m_items.getItems().clear();
        ViewSessionContext ctx = ViewSessionContext.instance();
        boolean isCliente    = ctx.isCliente();
        boolean isAms        = ctx.isAms();
        boolean isDispatcher = "DISPATCHER".equalsIgnoreCase(ctx.getRuolo());
        boolean isAdmin      = "ADMIN".equalsIgnoreCase(ctx.getRuolo());
        boolean isAmsAdmin   = ctx.isAmsAdmin();
        boolean isReqAdmin   = ctx.isReqAdmin();

        // AMS_ADMIN e REQ_ADMIN sono account di sola amministrazione utenti:
        // non possiedono ticket propri, quindi non vedono Gestione ticket,
        // Archivio, Nuovo ticket né Sostituzione — solo le loro voci
        // dedicate, come richiesto ("poche voci di menu"). REQ_ADMIN ha
        // anche "Gestione referenti", vincolata al proprio kunnr.
        if (isAmsAdmin || isReqAdmin) {
            m_items.getItems().add(new MenuItemInfo(
                "USER_ADMIN",
                isAmsAdmin ? "Gestione utenti AMS" : "Gestione richiedenti",
                isAmsAdmin ? "Amministra gli utenti AMS e DISPATCHER"
                           : "Amministra i richiedenti del tuo cliente (nessuna creazione — solo backoffice AMS)",
                true));
            if (isReqAdmin) {
                m_items.getItems().add(new MenuItemInfo(
                    "REFERENTI_ADMIN",
                    "Gestione referenti",
                    "Crea e disattiva i referenti_cli del tuo cliente",
                    true));
            }
            return;
        }

        // Gestione ticket: visibile a tutti
        m_items.getItems().add(new MenuItemInfo(
            "TICKET_LIST",
            "Gestione ticket",
            "Visualizza, commenta e gestisci i ticket esistenti",
            true));

        // Nuovo ticket: solo per CLIENTE — richiede obbligatoriamente
        // kunnr e reqid valorizzati (vedi validazione in NewTicketUI.saveDraft()).
        // Nessun altro ruolo (ADMIN compreso) può aprire un DRAFT.
        if (isCliente) {
            m_items.getItems().add(new MenuItemInfo(
                "NEW_TICKET",
                "Apri nuovo ticket",
                "Apri una nuova richiesta di assistenza",
                true));
        }

        // Ticket come Referente: solo per AMS (o ADMIN in test) — vista
        // personale, filtrata sul campo Refer invece che Amusr.
        if (isAms || isAdmin) {
            m_items.getItems().add(new MenuItemInfo(
                "REFERENTE_LIST",
                "Ticket come Referente",
                "Visualizza i ticket in cui risulti Referente",
                true));
        }

        // Smistamento DRAFT: solo per DISPATCHER (o ADMIN)
        if (isDispatcher || isAdmin) {
            m_items.getItems().add(new MenuItemInfo(
                "DISPATCHER",
                "Smistamento ticket",
                "Gestisci i ticket in attesa di fusione con SAP",
                true));
        }

        // Richieste di riattribuzione: per AMS (apre/gestisce le proprie) e
        // per DISPATCHER (consulta, sola lettura, tutte quelle aperte) —
        // due voci distinte perché aprono la stessa schermata in due
        // modalità diverse (vedi RiassegnazioneUI). Badge rosso col
        // conteggio delle richieste APERTE nel perimetro del ruolo — un
        // errore di lettura non deve bloccare il menu, resta semplicemente
        // senza badge (0).
        if (isAms || isAdmin) {
            MenuItemInfo item = new MenuItemInfo(
                "RIASSEGNAZIONE_AMS",
                "Richieste di riattribuzione",
                "Chiedi al DISPATCHER di riattribuire un tuo ticket non di tua competenza",
                true);
            try {
                item.setBadgeCount(riassegnazioneService.listAperteByRichiedente(ctx.getUsername()).size());
            } catch (Exception e) {
                System.err.println("[MenuUI] Errore conteggio richieste riattribuzione AMS: " + e.getMessage());
            }
            m_items.getItems().add(item);
        }
        if (isDispatcher || isAdmin) {
            MenuItemInfo item = new MenuItemInfo(
                "RIASSEGNAZIONE_DISPATCHER",
                "Richieste di riattribuzione (AMS)",
                "Consulta le richieste di riattribuzione aperte dal servizio assistenza",
                true);
            try {
                item.setBadgeCount(riassegnazioneService.listAperteAll().size());
            } catch (Exception e) {
                System.err.println("[MenuUI] Errore conteggio richieste riattribuzione DISPATCHER: " + e.getMessage());
            }
            m_items.getItems().add(item);
        }

        // Clienti abilitati: solo ADMIN — gestione migrazione cliente-per-cliente
        if (isAdmin) {
            m_items.getItems().add(new MenuItemInfo(
                "CLIENTE_CONFIG",
                "Clienti abilitati",
                "Gestisci quali clienti sono attivi sulla nuova procedura",
                true));
        }

        // Gestione richiedenti: solo ADMIN — unico ruolo che può creare
        // nuovi richiedenti per qualsiasi cliente abilitato (REQ_ADMIN può
        // solo manutenere quelli già esistenti del proprio kunnr).
        if (isAdmin) {
            m_items.getItems().add(new MenuItemInfo(
                "USER_ADMIN",
                "Gestione richiedenti",
                "Crea e amministra i richiedenti (CLIENTE) di qualsiasi cliente abilitato",
                true));
        }

        // Gestione referenti: per CLIENTE con permesso gestisce_referenti
        // (self-service sul proprio kunnr) e per ADMIN (kunnr libero).
        if (ctx.isGestisceReferenti() || isAdmin) {
            m_items.getItems().add(new MenuItemInfo(
                "REFERENTI_ADMIN",
                "Gestione referenti",
                "Crea e disattiva i referenti_cli",
                true));
        }

        // Ticket conclusi (CLO + RES): visibile a tutti
        m_items.getItems().add(new MenuItemInfo(
            "ARCHIVIO",
            "Ticket conclusi",
            "Consulta i ticket chiusi o risolti sul backend SAP",
            true));

        // Sostituzione temporanea: per CLIENTE e AMS, i ruoli la cui
        // visibilità sui ticket è legata alla propria identità (reqid/amusr)
        // — non ha senso per DISPATCHER/ADMIN che già vedono tutto.
        if (isCliente || isAms || isAdmin) {
            m_items.getItems().add(new MenuItemInfo(
                "SUBSTITUTION",
                "Sostituzione temporanea",
                "Definisci chi vedrà i tuoi ticket in un periodo di assenza",
                true));
        }
    }

    // =========================
    // GETTERS SUMMARY
    // =========================

    public boolean getHasSummary() { return m_summary != null; }
    public boolean needsSummaryLoad() { return !m_summaryLoaded; }
    public int getSummaryTotale()  { return m_summary != null ? m_summary.getTotale() : 0; }
    public int getSummaryAttivi()  { return m_summary != null ? m_summary.getTotaleAttivi() : 0; }
    public int getSummaryDraft()   { return m_summary != null ? m_summary.getTotaleDraft() : 0; }
    public boolean getHasSostituiti()      { return m_summary != null && m_summary.getSostituitiCount() > 0; }
    public int     getSummarySostituiti()  { return m_summary != null ? m_summary.getSostituitiCount() : 0; }
    public java.util.List<TicketSummary.StatoCount> getSummaryVoci() {
        return m_summary != null ? m_summary.getVoci() : java.util.Collections.emptyList();
    }

    /** Box "Ticket come Referente" (SAP) — visibile solo se non vuoto. */
    public boolean getHasSummaryReferente() { return m_summaryReferente != null && m_summaryReferente.getTotale() > 0; }
    public int getSummaryReferenteTotale()  { return m_summaryReferente != null ? m_summaryReferente.getTotale() : 0; }
    public int getSummaryReferenteAttivi()  { return m_summaryReferente != null ? m_summaryReferente.getTotaleAttivi() : 0; }
    public java.util.List<TicketSummary.StatoCount> getSummaryReferenteVoci() {
        return m_summaryReferente != null ? m_summaryReferente.getVoci() : java.util.Collections.emptyList();
    }

    /** Etichetta sintesi da mostrare nel menu sotto "Gestione ticket" */
    public String getSummaryLabel() {
        if (m_summary == null) return "Clicca per aggiornare";
        long attivi = m_summary.getVoci().stream()
            .filter(v -> !"CLO".equals(v.getRstat()) && !"RES".equals(v.getRstat()) && !"CAN".equals(v.getRstat()) && !"DRAFT".equals(v.getRstat()))
            .mapToInt(TicketSummary.StatoCount::getCount).sum();
        return attivi > 0 ? "Ticket attivi: " + attivi : "Nessun ticket attivo";
    }

    // =========================
    // GETTERS
    // =========================

    @Override public String getPageName()                 { return "/Menu.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.MenuUI}"; }

    public FIXGRIDListBinding<MenuItemInfo> getItems() { return m_items; }

    public String getUtenteLabel() {
        return ViewSessionContext.instance().getUtente();
    }
}