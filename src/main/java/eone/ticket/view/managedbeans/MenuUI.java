package eone.ticket.view.managedbeans;

import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.pagebean.PageBean;
import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.TicketSummary;


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
    }

    private IListener m_listener;
    private FIXGRIDListBinding<MenuItemInfo> m_items = new FIXGRIDListBinding<>();
    private TicketSummary m_summary;
    private boolean m_summaryLoaded = false;

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

    // =========================
    // INNER CLASS — VOCE DI MENU
    // =========================

    public class MenuItemInfo extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String  id;
        private final String  titolo;
        private final String  descrizione;
        private final boolean abilitato;

        public MenuItemInfo(String id, String titolo, String descrizione, boolean abilitato) {
            this.id = id;
            this.titolo = titolo;
            this.descrizione = descrizione;
            this.abilitato = abilitato;
        }

        public String  getTitolo()      { return titolo; }
        public String  getDescrizione() { return descrizione; }
        public boolean getAbilitato()   { return abilitato; }

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
    }

    private void buildMenu() {
        m_items.getItems().clear();
        ViewSessionContext ctx = ViewSessionContext.instance();
        boolean isCliente    = ctx.isCliente();
        boolean isAms        = ctx.isAms();
        boolean isDispatcher = "DISPATCHER".equalsIgnoreCase(ctx.getRuolo());
        boolean isAdmin      = "ADMIN".equalsIgnoreCase(ctx.getRuolo());

        // Gestione ticket: visibile a tutti
        m_items.getItems().add(new MenuItemInfo(
            "TICKET_LIST",
            "Gestione ticket",
            "Visualizza, commenta e gestisci i ticket esistenti",
            true));

        // Nuovo ticket: solo per CLIENTE (o ADMIN in test)
        if (isCliente || isAdmin) {
            m_items.getItems().add(new MenuItemInfo(
                "NEW_TICKET",
                "Apri nuovo ticket",
                "Apri una nuova richiesta di assistenza",
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

        // Archivio ticket chiusi: visibile a tutti
        m_items.getItems().add(new MenuItemInfo(
            "ARCHIVIO",
            "Archivio ticket chiusi",
            "Consulta i ticket in stato CLOSED",
            true));
    }

    // =========================
    // GETTERS SUMMARY
    // =========================

    public boolean getHasSummary() { return m_summary != null; }
    public boolean needsSummaryLoad() { return !m_summaryLoaded; }
    public int getSummaryTotale()  { return m_summary != null ? m_summary.getTotale() : 0; }
    public int getSummaryDraft()   { return m_summary != null ? m_summary.getTotaleDraft() : 0; }
    public java.util.List<TicketSummary.StatoCount> getSummaryVoci() {
        return m_summary != null ? m_summary.getVoci() : java.util.Collections.emptyList();
    }

    /** Etichetta sintesi da mostrare nel menu sotto "Gestione ticket" */
    public String getSummaryLabel() {
        if (m_summary == null) return "Clicca per aggiornare";
        long attivi = m_summary.getVoci().stream()
            .filter(v -> !"CLO".equals(v.getRstat()) && !"CAN".equals(v.getRstat()) && !"DRAFT".equals(v.getRstat()))
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