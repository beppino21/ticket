package eone.ticket.view.managedbeans;

import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import eone.ticket.context.ViewSessionContext;

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

        m_items.getItems().add(new MenuItemInfo(
            "TICKET_LIST",
            "Gestione ticket",
            "Visualizza, commenta e gestisci i ticket esistenti",
            true));

        m_items.getItems().add(new MenuItemInfo(
            "NEW_TICKET",
            "Nuovo ticket",
            "Apertura di un nuovo ticket (in arrivo)",
            false));

        // Voci future (analisi, reportistica, ecc.) si aggiungono qui
        // seguendo lo stesso pattern: m_items.getItems().add(new MenuItemInfo(...))
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