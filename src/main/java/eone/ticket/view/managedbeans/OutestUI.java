package eone.ticket.view.managedbeans;

import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.elements.util.Trigger;
import org.eclnt.jsfserver.pagebean.IPageBean;
import org.eclnt.jsfserver.pagebean.PageBean;
import org.eclnt.jsfserver.util.HttpSessionAccess;
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

    // =========================
    // DEEP LINK DA EMAIL ("Apri il ticket")
    // =========================
    // Se l'URL di ingresso è .../Outest.risc?ticket=6000001234, il parametro
    // viene letto qui (unica vera richiesta HTTP verso Outest.xml — il resto
    // della navigazione avviene via eventi CC, non nuove richieste HTTP) e
    // tenuto in sospeso finché l'utente non completa il logon: a quel punto
    // showRealUI() apre direttamente il ticket invece del Menu.
    private String m_pendingDeepLinkTicket;

    // =========================
    // EXIT APPLICAZIONE (client closer)
    // =========================

    /**
     * Trigger legato al componente t:clientcloser nel beanprocessing di
     * Outest.xml — unica pagina realmente caricata dal browser (Menu.xml
     * e gli altri contentUI sono inclusi via ROWPAGEBEANINCLUDE e non
     * possono avere un proprio beanprocessing/clientcloser).
     */
    Trigger m_clientCloseTrigger = new Trigger();
    public Trigger getClientCloseTrigger() { return m_clientCloseTrigger; }

    public OutestUI(IWorkpageDispatcher dispatcher) {
        super(dispatcher);
        leggiParametroDeepLink();
        showLogonUI();
    }

    /** Legge ?ticket=... dalla request iniziale, se presente. */
    private void leggiParametroDeepLink() {
        try {
            jakarta.servlet.http.HttpServletRequest req = HttpSessionAccess.getCurrentRequest();
            if (req != null) {
                String tickt = req.getParameter("ticket");
                if (tickt != null && !tickt.trim().isEmpty()) {
                    m_pendingDeepLinkTicket = tickt.trim();
                    System.out.println("[OutestUI] Deep link rilevato — ticket=" + m_pendingDeepLinkTicket);
                }
            }
        } catch (Exception e) {
            System.err.println("[OutestUI] Errore lettura parametro deep link: " + e.getMessage());
        }
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

    /**
     * Riporta l'utente alla pagina di Logon, con lo stesso effetto pratico
     * di un refresh del browser (che oggi già riporta correttamente al
     * login). Usata dal bottone "Logout" in TicketList.xml.
     * Non tocca la sessione HTTP: si limita a scartare i dati utente
     * correnti e a ripresentare LogonUI — al prossimo accesso showRealUI()
     * sovrascrive comunque tutti i campi di ViewSessionContext.
     */
    private void logout() {
        System.out.println("[OutestUI] logout() — ripresento LogonUI");
        m_userData = null;
        m_menuUI   = null;
        showLogonUI();
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

        showMenuOrPendingDeepLink();
    }

    /** Dopo il logon: apre direttamente il ticket del deep link, se presente, altrimenti il Menu. */
    private void showMenuOrPendingDeepLink() {
        if (m_pendingDeepLinkTicket != null) {
            String tickt = m_pendingDeepLinkTicket;
            m_pendingDeepLinkTicket = null; // consumato — un solo tentativo
            System.out.println("[OutestUI] Apertura diretta post-logon per deep link — ticket=" + tickt);

            if (tickt.startsWith("DRAFT-")) {
                // Deep link da mail di notifica DISPATCHER — ha senso solo per
                // chi ha accesso alla vista di smistamento.
                if ("DISPATCHER".equalsIgnoreCase(ViewSessionContext.instance().getRuolo())) {
                    showDispatcher(tickt);
                } else {
                    System.out.println("[OutestUI] Deep link a DRAFT ignorato — ruolo utente non DISPATCHER");
                    showMenuUI();
                }
            } else {
                showTicketList(false, tickt);
            }
            return;
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
                    case "REFERENTE_LIST": showReferenteList(false); break;
                    case "REFERENTE_ARCHIVIO": showReferenteList(true); break;
                    case "NEW_TICKET":  showNewTicket();       break;
                    case "DISPATCHER":  showDispatcher();      break;
                    case "CLIENTE_CONFIG": showClienteConfig(); break;
                    case "ARCHIVIO":    showTicketList(true);  break;
                    case "SUBSTITUTION": showSubstitution();   break;
                    case "USER_ADMIN":  showUserAdmin();       break;
                    case "REFERENTI_ADMIN": showReferentiAdmin(); break;
                    case "RIASSEGNAZIONE_AMS": showRiassegnazione(false); break;
                    case "RIASSEGNAZIONE_DISPATCHER": showRiassegnazione(true); break;
                    default: System.err.println("[OutestUI] Scelta menu non gestita: " + choiceId);
                }
            }

            @Override
            public void reactOnExitRequest() {
                System.out.println("[OutestUI] reactOnExitRequest — chiusura applicazione richiesta");
                // TODO: eventuale logout applicativo esplicito, se necessario
                // separatamente dalla scadenza naturale della sessione HTTP,
                // es. invalidazione di ViewSessionContext / audit log.
                m_clientCloseTrigger.trigger();
            }

            @Override
            public void reactOnLogoutRequest() {
                logout();
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
     *
     * NOTA: è un percorso di caricamento separato da TicketListUI (storico,
     * pensato per includere anche CLO/CAN/DRAFT che la lista operativa
     * esclude) — per questo la logica di sostituzione va replicata qui e non
     * solo in TicketListUI, altrimenti il riepilogo iniziale del Menu non
     * la riflette finché non viene esplicitamente ricalcolato.
     */
    private void loadSummaryForMenu() {
        try {
            ViewSessionContext ctx = ViewSessionContext.instance();
            String kunnr  = ctx.getKunnr();
            boolean vedeTutti = "ALL".equalsIgnoreCase(ctx.getOwnAll());
            // isClienteOReferente(): un REFERENTE_CLI deve essere scoped sul
            // proprio reqid esattamente come un CLIENTE — con ctx.isCliente()
            // da solo (ruolo strettamente 'CLIENTE') un referente cadeva nel
            // ramo "nessun reqid", con la query SAP che tornava TUTTI i
            // ticket dell'intero kunnr invece dei soli propri.
            String reqid  = ctx.isClienteOReferente() && !vedeTutti ? ctx.getRichiedente() : null;
            String amusr  = ctx.isAms() ? ctx.getUsername() : null;

            // Colleghi (stesso ruolo) attualmente sostituiti dall'utente loggato
            java.util.Set<String> reqidSostituiti = new java.util.HashSet<>();
            java.util.Set<String> amusrSostituiti = new java.util.HashSet<>();
            try {
                String idUser = ctx.getUsername();
                if (idUser != null && !idUser.trim().isEmpty()) {
                    eone.ticket.service.SubstitutionService subSvc = new eone.ticket.service.SubstitutionService();
                    eone.ticket.service.RequesterService reqSvc = new eone.ticket.service.RequesterService();
                    for (String idUserSostituito : subSvc.getSostituitiAttivi(idUser)) {
                        if (ctx.isAms()) {
                            amusrSostituiti.add(idUserSostituito);
                        } else {
                            eone.ticket.model.RequesterInfo info = reqSvc.getById(idUserSostituito);
                            if (info != null && info.getReqid() != null && !info.getReqid().trim().isEmpty()) {
                                reqidSostituiti.add(info.getReqid().trim());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[OutestUI] Errore caricamento sostituzioni per summary: " + e.getMessage());
            }

            // Se ci sono sostituzioni attive (e non vede_tutti) amplia la query
            // al Kunnr intero, come fa TicketListUI, e filtra lato client dopo.
            boolean reqidAmpliatoPerSostituzione = reqid != null && !reqidSostituiti.isEmpty();
            String reqidQuery = reqidAmpliatoPerSostituzione ? null : reqid;

            eone.ticket.service.SAPTicketService svc = new eone.ticket.service.SAPTicketService();
            // SAP non supporta 'ne' — carichiamo tutto e filtriamo client-side
            eone.ticket.service.SAPTicketService.TicketResponse resp =
                svc.getTickets(kunnr, reqidQuery, null, null, null, null);

            if (resp.isSuccess()) {
                java.util.List<eone.ticket.model.Ticket> tickets = resp.getTickets();

                // Nessun filtro per stato: il summary mostra TUTTI gli stati
                // (inclusi CLO, CAN, DRAFT) indipendentemente dall'ultima lista visualizzata

                if (!ctx.isClienteOReferente()) {
                    // Vista che attraversa tutti i clienti (AMS/DISPATCHER/ADMIN) —
                    // scarta i clienti non ancora abilitati alla nuova gestione,
                    // stesso filtro applicato in TicketListUI.
                    // NOTA: qui si controlla il ruolo, non "kunnr == null" — per
                    // un utente AMS/DISPATCHER kunnr è valorizzato a "" (stringa
                    // vuota) in LogonUI, mai a null, quindi un controllo su
                    // kunnr==null non scattava mai e questo filtro veniva
                    // silenziosamente saltato nel conteggio del badge, pur
                    // restando applicato nella lista vera (TicketListUI) —
                    // da cui il disallineamento badge/lista per i clienti non
                    // abilitati.
                    try {
                        java.util.Set<String> abilitati = new eone.ticket.service.ClienteConfigService().getKunnrAbilitati();
                        tickets = tickets.stream()
                            .filter(t -> abilitati.contains(eone.ticket.service.ClienteConfigService.normalizeKunnr(t.getKunnr())))
                            .collect(java.util.stream.Collectors.toList());
                    } catch (Exception e) {
                        System.err.println("[OutestUI] Errore lettura clienti abilitati per summary: " + e.getMessage());
                    }
                }

                // Copia PRIMA del filtro per Amusr — serve al conteggio
                // indipendente "Ticket come Referente" (campo Refer), più
                // sotto, solo per AMS.
                java.util.List<eone.ticket.model.Ticket> ticketsPerReferSap = tickets;

                if (reqidAmpliatoPerSostituzione) {
                    // Filtra su {proprio reqid} ∪ {reqid dei sostituiti}
                    String reqidProprio = reqid;
                    tickets = tickets.stream()
                        .filter(t -> {
                            String r = t.getReqid() != null ? t.getReqid().trim() : "";
                            return r.equalsIgnoreCase(reqidProprio) || reqidSostituiti.contains(r);
                        })
                        .collect(java.util.stream.Collectors.toList());
                } else if (amusr != null && !amusr.trim().isEmpty()) {
                    // Filtra per AMS, includendo anche i colleghi sostituiti
                    tickets = tickets.stream()
                        .filter(t -> amusr.equalsIgnoreCase(t.getAmusr()) ||
                                     (t.getAmusr() != null && amusrSostituiti.contains(t.getAmusr().trim())))
                        .collect(java.util.stream.Collectors.toList());
                }

                // Se la query era ristretta a un singolo reqid (proprio),
                // aggiunge anche i ticket SAP dove l'utente è referente_cli
                // ma non richiedente — stessa estensione già applicata in
                // TicketListUI.aggiungiTicketDoveReferente(); senza questa,
                // il badge del menu e la lista vera tornano a disallinearsi
                // (stesso tipo di bug già risolto una volta per i clienti
                // non abilitati). Riguarda sia CLIENTE (referente di altri,
                // oltre che di se stesso) sia REFERENTE_CLI (che qui trova
                // TUTTI i propri ticket, dato che da solo il reqid proprio
                // non incrocia mai nessun ticket SAP).
                if (reqidQuery != null && !reqidQuery.trim().isEmpty()) {
                    try {
                        eone.ticket.service.TicketReferenteService refSvc = new eone.ticket.service.TicketReferenteService();
                        java.util.List<String> ticktReferente = refSvc.getTicktsByReferente(reqidQuery);
                        if (!ticktReferente.isEmpty()) {
                            tickets = new java.util.ArrayList<>(tickets); // Collectors.toList() non garantisce mutabilità
                            for (String t : ticktReferente) {
                                if (t == null || t.startsWith("DRAFT-")) continue; // vedi nota in TicketListUI
                                final String tickt = t;
                                boolean giaPresente = tickets.stream().anyMatch(x -> tickt.equalsIgnoreCase(x.getTickt()));
                                if (giaPresente) continue;
                                try {
                                    eone.ticket.model.Ticket extra = svc.getTicketById(tickt, kunnr);
                                    if (extra != null) tickets.add(extra);
                                } catch (Exception e) {
                                System.err.println("[OutestUI] Errore recupero ticket " + tickt +
                                                   " (referente=" + reqidQuery + ") per summary: " + e.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("[OutestUI] Errore lookup ticket dove referente per summary: " + e.getMessage());
                    }
                }

                // Conta DRAFT (solo per CLIENTE e DISPATCHER — vedi nota in TicketListUI)
                int draftCount = 0;
                int sostituitiCount = 0;
                try {
                    eone.ticket.service.TicketDraftService draftSvc =
                        new eone.ticket.service.TicketDraftService();
                    if (ctx.isCliente() && kunnr != null) {
                        String reqidPerDraft = ctx.getRichiedente();
                        java.util.List<eone.ticket.model.TicketDraft> drafts;
                        if (!reqidSostituiti.isEmpty()) {
                            java.util.List<String> reqids = new java.util.ArrayList<>(reqidSostituiti);
                            reqids.add(reqidPerDraft != null ? reqidPerDraft : "");
                            drafts = draftSvc.getDraftsByRequesters(kunnr, reqids);
                        } else {
                            drafts = draftSvc.getDraftsByRequester(kunnr, reqidPerDraft != null ? reqidPerDraft : "");
                        }
                        draftCount = (int) drafts.stream().filter(d -> d.isDraft()).count();
                        sostituitiCount += (int) drafts.stream()
                            .filter(d -> d.isDraft() && d.getReqid() != null
                                      && reqidSostituiti.contains(d.getReqid().trim()))
                            .count();
                    } else if ("DISPATCHER".equalsIgnoreCase(ctx.getRuolo())) {
                        // DISPATCHER: tutti i DRAFT in attesa
                        draftCount = draftSvc.getPendingDrafts().size();
                    }
                    // Altri ruoli (AMS, ADMIN...): nessun conteggio DRAFT nel badge,
                    // coerente con il fatto che non vedono i DRAFT nella lista ticket.
                    System.out.println("[OutestUI] DRAFT contati: " + draftCount);
                } catch (Exception e) {
                    System.err.println("[OutestUI] Errore conteggio DRAFT: " + e.getMessage());
                }

                // Conta anche i ticket SAP (non DRAFT) dei colleghi sostituiti
                for (eone.ticket.model.Ticket t : tickets) {
                    String r = t.getReqid() != null ? t.getReqid().trim() : "";
                    String a = t.getAmusr() != null ? t.getAmusr().trim() : "";
                    if (reqidSostituiti.contains(r) || amusrSostituiti.contains(a)) sostituitiCount++;
                }

                eone.ticket.model.TicketSummary summary =
                    eone.ticket.model.TicketSummary.build(tickets, draftCount, sostituitiCount);
                m_menuUI.forceUpdateSummary(summary);
                System.out.println("[OutestUI] Summary caricato al logon: " +
                                   tickets.size() + " ticket attivi + " + draftCount + " DRAFT");

                // "Ticket come Referente" (campo SAP Refer) — box separato
                // nel menu, solo per AMS, e solo se non vuoto. Nessun DRAFT
                // qui: un DRAFT non ha ancora un Refer, è un record locale
                // pre-fusione SAP (stessa logica di TicketListUI.loadTicketsForReferente).
                if (ctx.isAms() && amusr != null && !amusr.trim().isEmpty()) {
                    try {
                        java.util.List<eone.ticket.model.Ticket> ticketsReferSap = ticketsPerReferSap.stream()
                            .filter(t -> amusr.equalsIgnoreCase(t.getRefer()) ||
                                         (t.getRefer() != null && amusrSostituiti.contains(t.getRefer().trim())))
                            .collect(java.util.stream.Collectors.toList());
                        eone.ticket.model.TicketSummary summaryReferSap =
                            eone.ticket.model.TicketSummary.build(ticketsReferSap, 0);
                        m_menuUI.forceUpdateSummaryReferente(summaryReferSap);
                    } catch (Exception e) {
                        System.err.println("[OutestUI] Errore calcolo summary Referente SAP: " + e.getMessage());
                        m_menuUI.forceUpdateSummaryReferente(null);
                    }
                } else {
                    m_menuUI.forceUpdateSummaryReferente(null);
                }
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
                // Il riepilogo del menu si ricalcola solo alla PRIMA visita
                // dopo il logon (needsSummaryLoad) — un DRAFT appena creato
                // non lo aggiornerebbe altrimenti finché non si fa logout e
                // login di nuovo. Forziamo qui un ricalcolo, così il badge
                // riflette subito il nuovo DRAFT.
                loadSummaryForMenu();
                // Dopo la creazione torna al menu — il cliente vedrà il DRAFT
                // nella lista ticket al prossimo caricamento
                showMenuUI();
            }
        });
        m_contentUI = ui;
    }

    private void showSubstitution() {
        SubstitutionUI ui = new SubstitutionUI();
        ui.prepare(new SubstitutionUI.IListener() {
            @Override
            public void reactOnBackToMenu() {
                showMenuUI();
            }
        });
        m_contentUI = ui;
    }

    private void showUserAdmin() {
        UserAdminUI ui = new UserAdminUI();
        ui.prepare(new UserAdminUI.IListener() {
            @Override
            public void reactOnBackToMenu() {
                showMenuUI();
            }
        });
        m_contentUI = ui;
    }

    private void showReferentiAdmin() {
        ReferentiUI ui = new ReferentiUI();
        ui.prepare(new ReferentiUI.IListener() {
            @Override
            public void reactOnBackToMenu() {
                showMenuUI();
            }
        });
        m_contentUI = ui;
    }

    private void showRiassegnazione(boolean modeDispatcher) {
        RiassegnazioneUI ui = new RiassegnazioneUI();
        ui.prepare(modeDispatcher, new RiassegnazioneUI.IListener() {
            @Override
            public void reactOnBackToMenu() {
                showMenuUI();
            }
        });
        m_contentUI = ui;
    }

    private void showClienteConfig() {
        ClienteConfigUI ui = new ClienteConfigUI();
        ui.prepare(new ClienteConfigUI.IListener() {
            @Override
            public void reactOnBackToMenu() {
                showMenuUI();
            }
        });
        m_contentUI = ui;
    }

    private void showDispatcher() {
        showDispatcher(null);
    }

    /**
     * @param selectDraftKey se valorizzato (deep link da mail notifica DISPATCHER,
     *                        es. "DRAFT-42"), il DRAFT viene selezionato subito
     *                        dopo il caricamento della lista.
     */
    private void showDispatcher(String selectDraftKey) {
        DispatcherUI ui = new DispatcherUI(getOwningDispatcher());
        ui.prepare(new DispatcherUI.IListener() {
            @Override
            public void reactOnBackToMenu() {
                showMenuUI();
            }
        });
        ui.init();
        if (selectDraftKey != null && !selectDraftKey.trim().isEmpty()) {
            ui.selectDraft(selectDraftKey.trim());
        }
        m_contentUI = ui;
    }

    private void showTicketList(boolean archivio) {
        showTicketList(archivio, null);
    }

    /**
     * @param selectTicket se valorizzato (deep link da email), il ticket viene
     *                      selezionato e il pannello commenti aperto subito
     *                      dopo il caricamento della lista.
     */
    private void showTicketList(boolean archivio, String selectTicket) {
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
            @Override
            public void reactOnLogoutRequest() {
                logout();
            }
        });
        ui.init(archivio);
        if (selectTicket != null && !selectTicket.trim().isEmpty()) {
            ui.selectAndOpenTicket(selectTicket.trim());
        }
        m_contentUI = ui;
    }

    /** "Ticket come Referente" — stessa vista di showTicketList, filtrata sul campo Refer. */
    private void showReferenteList(boolean archivio) {
        TicketListUI ui = new TicketListUI(getOwningDispatcher());
        ui.prepare(new TicketListUI.IListener() {
            @Override
            public void reactOnBackToMenu() {
                showMenuUI();
            }
            @Override
            public void reactOnSummaryUpdated(eone.ticket.model.TicketSummary summary) {
                // Non aggiorna il riepilogo del Menu — è una vista personale
                // separata dai ticket assegnati come AMS.
            }
            @Override
            public void reactOnLogoutRequest() {
                logout();
            }
        });
        ui.init(archivio, true);
        m_contentUI = ui;
    }
}