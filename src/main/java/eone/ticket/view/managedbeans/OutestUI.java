package eone.ticket.view.managedbeans;
import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.pagebean.IPageBean;
import org.eclnt.jsfserver.pagebean.PageBean;
import org.eclnt.workplace.IWorkpageDispatcher;
import org.eclnt.workplace.WorkpageDispatchedPageBean;

import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.UserSessionData;

@CCGenClass (expressionBase="#{d.OutestUI}")

public class OutestUI
extends WorkpageDispatchedPageBean
//extends PageBean //WorkpageDispatchedPageBean
    implements Serializable
{
    // ------------------------------------------------------------------------
    // inner classes
    // ------------------------------------------------------------------------
    
    // ------------------------------------------------------------------------
    // members
    // ------------------------------------------------------------------------
    
    IPageBean m_contentUI;
    
    // ✅ Salva i dati utente QUI invece che in sessione HTTP
    private UserSessionData m_userData;
    
    // ------------------------------------------------------------------------
    // constructors & initialization
    // ------------------------------------------------------------------------

//    public OutestUI()
//    {
//        showLogonUI();
//    }
    
    
    public OutestUI(IWorkpageDispatcher dispatcher) 
    {
		super(dispatcher);
        showLogonUI();
	}


	public String getPageName() { return "/Outest.xml"; }
    public String getRootExpressionUsedInPage() { return "#{d.OutestUI}"; }

    // ------------------------------------------------------------------------
    // public usage
    // ------------------------------------------------------------------------

    public IPageBean getContentUI() { return m_contentUI; }

    // ------------------------------------------------------------------------
    // private usage
    // ------------------------------------------------------------------------

    private void showLogonUI()
    {
        System.out.println("================================================================================");
        System.out.println("[OutestUI] ===== SHOW LOGON UI INIZIA =====");
        System.out.println("================================================================================");
        
        LogonUI ui = new LogonUI();
        
//        System.out.println("[OutestUI] LogonUI creato, ora chiamo prepare()...");
        
        ui.prepare(new LogonUI.IListener()
        {
            @Override
            public void reactOnLogon(UserSessionData userData)
            {
                System.out.println("================================================================================");
                System.out.println("[OutestUI] ===== reactOnLogon CHIAMATO! =====");
                System.out.println("================================================================================");
                
                if (userData != null) {
//                    System.out.println("[OutestUI]   Utente: " + userData.getUtente());
                    m_userData = userData;
                    showRealUI();
                }
            }
        });
        
//        System.out.println("[OutestUI] prepare() chiamato, ora imposto m_contentUI...");
        
        m_contentUI = ui;
        
        System.out.println("[OutestUI] m_contentUI impostato: " + m_contentUI);
        System.out.println("================================================================================");
        System.out.println("[OutestUI] ===== SHOW LOGON UI FINITO =====");
        System.out.println("================================================================================");
    }

    protected void showRealUI()
    {    
        System.out.println("[OutestUI] ===== SHOW REAL UI =====");
        
        if (m_userData != null) {
            System.out.println("[OutestUI] Salvo in ViewSessionContext:");
            System.out.println("[OutestUI]   Utente: " + m_userData.getUtente());
            System.out.println("[OutestUI]   Kunnr: " + m_userData.getKunnr());
            System.out.println("[OutestUI]   Richiedente: " + m_userData.getRichiedente());
            
            // ✅ Prima salva i dati nel context
            ViewSessionContext.instance().setUtente(m_userData.getUtente());
            ViewSessionContext.instance().setKunnr(m_userData.getKunnr());
            ViewSessionContext.instance().setRichiedente(m_userData.getRichiedente());
            ViewSessionContext.instance().setUsername(m_userData.getUtente());
            
            // ✅ Poi crea la UI
            TicketListUI ui = new TicketListUI(getOwningDispatcher());
            
            // ✅ E chiamail metodo init() per caricare i dati
//            System.out.println("[OutestUI] ✅ Chiamo init() per caricare i ticket...");
            ui.init();  // ← Più pulito! La UI si autogestisce
            
            m_contentUI = ui;
            
//            System.out.println("[OutestUI] ✅ TicketListUI inizializzata!");
        } else {
//            System.err.println("[OutestUI] ❌ m_userData è null!");
        }
    }


}
