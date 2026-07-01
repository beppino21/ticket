package eone.ticket.view.managedbeans;

import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.pagebean.IPageBean;
import org.eclnt.jsfserver.pagebean.PageBean;
import org.eclnt.workplace.IWorkpageDispatcher;
import org.eclnt.workplace.WorkpageDispatchedPageBean;

import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.UserSessionData;

@CCGenClass(expressionBase = "#{d.OutestUI}")
public class OutestUI
        extends WorkpageDispatchedPageBean
        implements Serializable {

    private static final long serialVersionUID = 1L;

    IPageBean       m_contentUI;
    UserSessionData m_userData;
    MenuUI          m_menuUI;    // tenuto in memoria per aggiornare il summary

    public OutestUI(IWorkpageDispatcher dispatcher) {
        super(dispatcher);
        showLogonUI();
    }

    @Override
    public String getPageName()                 { return "/Outest.xml"; }
    @Override
    public String getRootExpressionUsedInPage() { return "#{d.OutestUI}"; }

    public IPageBean getContentUI() { return m_contentUI; }

    // =========================================================
    // LOGON
    // =========================================================

    private void showLogonUI() {
        System.out.println("[OutestUI] showLogonUI()");
        LogonUI ui = new LogonUI();
        ui.prepare(new LogonUI.IListener() {
            @Override
            public void reactOnLogon(UserSessionData userData) {
                System.out.println("[OutestUI] reactOnLogon — utente: " + userData.getUtente());
                m_userData = userData;
                showRealUI();
            }
        });
        m_contentUI = ui;
    }

    // =========================================================
    // POST-LOGON
    // =========================================================

    protected void showRealUI() {
        if (m_userData == null) {
            System.err.println("[OutestUI] ❌ m_userData è null in showRealUI()");
            return;
        }

        System.out.println("[OutestUI] showRealUI — salvo in ViewSessionContext: " + m_userData);

        ViewSessionContext ctx = ViewSessionContext.instance();

        // Campi base (retrocompatibilità)
        ctx.setUtente      (m_userData.getUtente());
        ctx.setKunnr       (m_userData.getKunnr());
        ctx.setRichiedente (m_userData.getRichiedente());
        ctx.setUsername    (m_userData.getUsername());
        ctx.setOwnAll      (m_userData.getOwnAll());

        // Dati estesi da PostgreSQL (nuovo)
        if (m_userData.getRequesterInfo() != null) {
            ctx.setRequesterInfo(m_userData.getRequesterInfo());
            System.out.println("[OutestUI] RequesterInfo in sessione: " + m_userData.getRequesterInfo());
        }

        showMenuUI();
    }

    // =========================================================
    // MENU PRINCIPALE
    // =========================================================

    private void showMenuUI() {
        System.out.println("[OutestUI] showMenuUI()");
        if (m_menuUI == null) m_menuUI = new MenuUI();
        m_menuUI.prepare(new MenuUI.IListener() {
            @Override
            public void reactOnMenuChoice(String choiceId) {
                System.out.println("[OutestUI] reactOnMenuChoice — " + choiceId);
                switch (choiceId) {
                    case "TICKET_LIST": showTicketList(false); break;
                    case "NEW_TICKET":  showNewTicket();       break;
                    case "DISPATCHER":  showDispatcher();      break;
                    case "ARCHIVIO":    showTicketList(true);  break;
                    default: System.err.println("[OutestUI] Scelta menu non gestita: " + choiceId);
                }
            }
        });
        // Prima visita al menu dopo logon: carica subito il summary in background
        if (m_menuUI.needsSummaryLoad()) {
            loadSummaryForMenu();
        }
        m_contentUI = m_menuUI;
    }

    /**
     * Carica i ticket SAP (escludendo CLO) e costruisce il summary per la dashboard.
     * Chiamato una sola volta al primo accesso al menu dopo il logon.
     */
    private void loadSummaryForMenu() {
        try {
            ViewSessionContext ctx = ViewSessionContext.instance();
            String kunnr  = ctx.getKunnr();
            String reqid  = ctx.isCliente() && !"ALL".equalsIgnoreCase(ctx.getOwnAll())
                            ? ctx.getRichiedente() : null;
            String amusr  = ctx.isAms() ? ctx.getUsername() : null;

            eone.ticket.service.SAPTicketService svc = new eone.ticket.service.SAPTicketService();
            // SAP non supporta 'ne' — carichiamo tutto e filtriamo client-side
            eone.ticket.service.SAPTicketService.TicketResponse resp =
                svc.getTickets(kunnr, reqid, null, null, null, null);

            if (resp.isSuccess()) {
                java.util.List<eone.ticket.model.Ticket> tickets = resp.getTickets();

                // Nessun filtro per stato: il summary mostra TUTTI gli stati
                // (inclusi CLO, CAN, DRAFT) indipendentemente dall'ultima lista visualizzata

                // Filtra per AMS se necessario
                if (amusr != null && !amusr.trim().isEmpty()) {
                    tickets = tickets.stream()
                        .filter(t -> amusr.equalsIgnoreCase(t.getAmusr()))
                        .collect(java.util.stream.Collectors.toList());
                }

                // Conta DRAFT (solo per CLIENTE)
                int draftCount = 0;
                try {
                    eone.ticket.service.TicketDraftService draftSvc =
                        new eone.ticket.service.TicketDraftService();
                    if (ctx.isCliente() && kunnr != null) {
                        // CLIENTE: solo i suoi DRAFT
                        String reqidPerDraft = ctx.getRichiedente();
                        java.util.List<eone.ticket.model.TicketDraft> drafts =
                            draftSvc.getDraftsByRequester(kunnr, reqidPerDraft != null ? reqidPerDraft : "");
                        draftCount = (int) drafts.stream().filter(d -> d.isDraft()).count();
                    } else {
                        // DISPATCHER / ADMIN / AMS: tutti i DRAFT in attesa
                        draftCount = draftSvc.getPendingDrafts().size();
                    }
                    System.out.println("[OutestUI] DRAFT contati: " + draftCount);
                } catch (Exception e) {
                    System.err.println("[OutestUI] Errore conteggio DRAFT: " + e.getMessage());
                }

                eone.ticket.model.TicketSummary summary =
                    eone.ticket.model.TicketSummary.build(tickets, draftCount);
                m_menuUI.forceUpdateSummary(summary);
                System.out.println("[OutestUI] Summary caricato al logon: " +
                                   tickets.size() + " ticket attivi + " + draftCount + " DRAFT");
            }
        } catch (Exception e) {
            System.err.println("[OutestUI] Errore caricamento summary: " + e.getMessage());
        }
    }

    private void showNewTicket() {
        NewTicketUI ui = new NewTicketUI();
        ui.prepare(new NewTicketUI.IListener() {
            @Override
            public void reactOnBackToMenu() {
                showMenuUI();
            }
            @Override
            public void reactOnDraftCreated(long draftId) {
                // Dopo la creazione torna al menu — il cliente vedrà il DRAFT
                // nella lista ticket al prossimo caricamento
                showMenuUI();
            }
        });
        m_contentUI = ui;
    }

    private void showDispatcher() {
        DispatcherUI ui = new DispatcherUI(getOwningDispatcher());
        ui.prepare(new DispatcherUI.IListener() {
            @Override
            public void reactOnBackToMenu() {
                showMenuUI();
            }
        });
        ui.init();
        m_contentUI = ui;
    }

    private void showTicketList(boolean archivio) {
        TicketListUI ui = new TicketListUI(getOwningDispatcher());
        ui.prepare(new TicketListUI.IListener() {
            @Override
            public void reactOnBackToMenu() {
                System.out.println("[OutestUI] reactOnBackToMenu — torno al menu");
                showMenuUI();
            }
            @Override
            public void reactOnSummaryUpdated(eone.ticket.model.TicketSummary summary) {
                if (m_menuUI != null) m_menuUI.updateSummary(summary);
            }
        });
        ui.init(archivio);
        m_contentUI = ui;
    }
}