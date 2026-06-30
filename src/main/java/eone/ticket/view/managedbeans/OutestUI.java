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

        TicketListUI ui = new TicketListUI(getOwningDispatcher());
        ui.init();
        m_contentUI = ui;
    }
}