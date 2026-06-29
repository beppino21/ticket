package eone.ticket.view.managedbeans;

import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.pagebean.PageBean;

import eone.ticket.model.UserSessionData;
import eone.ticket.service.SAPLogonService;
import eone.ticket.service.SAPLogonService.LogonResponse;

@CCGenClass (expressionBase="#{d.LogonUI}")
public class LogonUI extends PageBean implements Serializable
{
    private static final long serialVersionUID = 1L;
    
    // ------------------------------------------------------------------------
    // inner classes
    // ------------------------------------------------------------------------
    
    public interface IListener extends Serializable
    {
        public void reactOnLogon(UserSessionData userData);
    }
    
    // ------------------------------------------------------------------------
    // members
    // ------------------------------------------------------------------------
    
    private IListener m_listener;
    private String m_userName;
    private String m_password;
    private String m_errorMessage;
    private UserSessionData m_userData;
    
    // ------------------------------------------------------------------------
    // constructors & initialization
    // ------------------------------------------------------------------------

    public LogonUI()
    {
        System.out.println("================================================================================");
        System.out.println("[LogonUI] ===== COSTRUTTORE CHIAMATO =====");
        System.out.println("[LogonUI] Bean creato!");
        System.out.println("================================================================================");
    }

    public String getPageName() { return "/Logon.xml"; }
    public String getRootExpressionUsedInPage() { return "#{d.LogonUI}"; }

    // ------------------------------------------------------------------------
    // public usage
    // ------------------------------------------------------------------------

    public void prepare(IListener listener)
    {
        System.out.println("================================================================================");
        System.out.println("[LogonUI] ===== prepare() CHIAMATO! =====");
        System.out.println("[LogonUI] Listener ricevuto: " + listener);
        
        m_listener = listener;
        
        System.out.println("[LogonUI] m_listener impostato: " + m_listener);
        System.out.println("================================================================================");
    }
    
    // ------------------------------------------------------------------------
    // GETTER E SETTER
    // ------------------------------------------------------------------------
    
    public String getPassword() { return m_password; }
    public void setPassword(String value) { this.m_password = value; }
    public String getUserName() { return m_userName; }
    public void setUserName(String value) { this.m_userName = value; }

    // ------------------------------------------------------------------------
    // ACTION METHODS
    // ------------------------------------------------------------------------
    
    /**
     * Action chiamata dal pulsante di logon
     */
    public void onLogonAction(org.eclnt.jsfserver.base.faces.event.ActionEvent event) 
    {
        try {
            System.out.println("================================================================================");
            System.out.println("[LogonUI] onLogonAction CHIAMATO!");
            System.out.println("[LogonUI] Username: " + m_userName);
            System.out.println("================================================================================");
            
            boolean logonOK = checkLogonData();
            
            System.out.println("[LogonUI] checkLogonData() returned: " + logonOK);
            
            if (logonOK == false)
            {
                System.err.println("[LogonUI] ❌ Logon fallito!");
                Statusbar.outputError("Logon fallito - verificare le credenziali.");
            }
            else
            {
                System.out.println("[LogonUI] ✅ Logon riuscito!");
                Statusbar.outputSuccess("Logon riuscito per: " + m_userName);
                
                // Verifica listener
                if (m_listener == null) {
                    System.err.println("[LogonUI] ❌ ERRORE: m_listener è null!");
                    Statusbar.outputError("Errore interno: listener non impostato");
                    return;
                }
                
                // Verifica userData
                if (m_userData == null) {
                    System.err.println("[LogonUI] ❌ ERRORE: m_userData è null!");
                    Statusbar.outputError("Errore interno: dati utente non disponibili");
                    return;
                }
                
                System.out.println("[LogonUI] Invio dati al listener...");
                System.out.println("[LogonUI]   Utente: " + m_userData.getUtente());
                System.out.println("[LogonUI]   Kunnr: " + m_userData.getKunnr());
                
                // Chiamata al listener
                System.out.println("[LogonUI] Sto per chiamare m_listener.reactOnLogon()...");
                m_listener.reactOnLogon(m_userData);
                System.out.println("[LogonUI] ✅ Listener chiamato con successo!");
            }
            
            System.out.println("[LogonUI] onLogonAction completato!");
            
        } catch (Exception e) {
            System.err.println("================================================================================");
            System.err.println("[LogonUI] ❌❌❌ ECCEZIONE IN onLogonAction():");
            System.err.println("================================================================================");
            e.printStackTrace();
            System.err.println("================================================================================");
            Statusbar.outputError("Errore durante il login: " + e.getMessage());
        }
    }

    public void onRequestNewPasswordAction(org.eclnt.jsfserver.base.faces.event.ActionEvent event) 
    {
        System.out.println("[LogonUI] onRequestNewPasswordAction chiamato");
    }
    
    // ------------------------------------------------------------------------
    // private usage
    // ------------------------------------------------------------------------
    
    /**
     * Valida i dati di login e chiama il servizio SAP
     */
    private boolean checkLogonData()
    {
        System.out.println("[LogonUI] checkLogonData() - Inizio validazione");
        
        m_errorMessage = null;
        
        if (m_userName == null || m_userName.trim().isEmpty()) {
            m_errorMessage = "Inserire lo username";
            Statusbar.outputError(m_errorMessage);
            return false;
        }
        
        if (m_password == null || m_password.trim().isEmpty()) {
            m_errorMessage = "Inserire la password";
            Statusbar.outputError(m_errorMessage);
            return false;
        }
        
        System.out.println("[LogonUI] Validazione OK, chiamata al servizio SAP...");
        
        SAPLogonService service = null;
        
        try {
            System.out.println("[LogonUI] Creo SAPLogonService...");
            service = new SAPLogonService();
            System.out.println("[LogonUI] SAPLogonService creato: " + service);
            
            System.out.println("[LogonUI] Chiamo performLogon()...");
            LogonResponse response = service.performLogon(m_userName, m_password);
            System.out.println("[LogonUI] performLogon() completato!");

            System.out.println("[LogonUI] Risposta ricevuta:");
            System.out.println("[LogonUI] - Success: " + response.isSuccess());
            System.out.println("[LogonUI] - Status Code: " + response.getStatusCode());
            
            if (response.isSuccess()) {
                System.out.println("[LogonUI] ✅ Logon SAP RIUSCITO!");
                
                System.out.println("[LogonUI] Chiamo toUserSessionData()...");
                UserSessionData userData = response.toUserSessionData();
                System.out.println("[LogonUI] toUserSessionData() completato!");
                
                if (userData != null && userData.isLoggedIn()) {
                    System.out.println("[LogonUI] Dati utente estratti:");
                    System.out.println("[LogonUI]   Username: " + userData.getUsername());
                    System.out.println("[LogonUI]   Utente: " + userData.getUtente());
                    System.out.println("[LogonUI]   Kunnr: " + userData.getKunnr());
                    System.out.println("[LogonUI]   Richiedente: " + userData.getRichiedente());
                    
                    // Salva i dati
                    m_userData = userData;
                    System.out.println("[LogonUI] ✅ Dati salvati in m_userData");
                    
                    return true;
                    
                } else {
                    System.err.println("[LogonUI] ❌ userData è null o non loggato!");
                    m_errorMessage = "Errore nel parsing dei dati utente";
                    Statusbar.outputError(m_errorMessage);
                    return false;
                }
                
            } else {
                System.err.println("[LogonUI] ❌ Credenziali non valide!");
                m_errorMessage = "Credenziali non valide.";
                Statusbar.outputError(m_errorMessage);
                return false;
            }
            
        } catch (Exception e) {
            System.err.println("================================================================================");
            System.err.println("[LogonUI] ❌❌❌ ECCEZIONE IN checkLogonData():");
            System.err.println("================================================================================");
            e.printStackTrace();
            System.err.println("================================================================================");
            
            m_errorMessage = "Errore durante il logon: " + e.getMessage();
            Statusbar.outputError(m_errorMessage);
            return false;
            
        } finally {
            if (service != null) {
                try {
                    System.out.println("[LogonUI] Chiudo il servizio...");
                    service.close();
                    System.out.println("[LogonUI] Servizio chiuso!");
                } catch (Exception e) {
                    System.err.println("[LogonUI] ❌ Errore chiudendo il servizio:");
                    e.printStackTrace();
                }
            }
        }
    }
}