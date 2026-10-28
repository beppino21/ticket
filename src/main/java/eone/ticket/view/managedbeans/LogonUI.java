package eone.ticket.view.managedbeans;

import java.io.Serializable;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.ModalPopup;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.util.Trigger;
import org.eclnt.jsfserver.pagebean.PageBean;
import org.eclnt.jsfserver.util.tempfile.TempFileManager;

import eone.ticket.model.RequesterInfo;
import eone.ticket.model.UserSessionData;
import eone.ticket.service.AuditService;
import eone.ticket.service.LoginGuardService;
import eone.ticket.service.MfaService;
import eone.ticket.service.RequesterService;
import eone.ticket.util.Totp;

/**
 * LogonUI v2 — autenticazione contro PostgreSQL (ticket_requester).
 *
 * Modifiche rispetto alla v1:
 *  - checkLogonData() non chiama più SAPLogonService ma RequesterService.authenticate()
 *  - UserSessionData viene costruito dai campi di RequesterInfo
 *  - onRequestNewPasswordAction() apre un popup modale ChangePasswordUI
 *    (il link era già presente nell'XML ma non implementato)
 *  - link alla documentazione PDF (file statico servito da Tomcat, stesso
 *    meccanismo t:filedownload+Trigger già usato per gli allegati)
 *
 * v3 — secondo fattore TOTP e blocco tentativi:
 *  - dopo la password corretta, se l'utente ha l'MFA attiva si passa al
 *    passo "codice"; se l'MFA è obbligatoria ma non ancora attivata, al
 *    passo "attivazione" (QR code + primo codice di conferma);
 *  - l'accesso (listener.reactOnLogon) avviene solo a secondo fattore superato;
 *  - password o codice errati contano per il blocco temporaneo
 *    (LoginGuardService); i messaggi d'errore sono volutamente generici
 *    per non rivelare se uno username esiste.
 */
@CCGenClass(expressionBase = "#{d.LogonUI}")
public class LogonUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    // =========================================================
    // LISTENER
    // =========================================================

    public interface IListener extends Serializable {
        void reactOnLogon(UserSessionData userData);
    }

    // =========================================================
    // MEMBERS
    // =========================================================

    private IListener       m_listener;
    private String          m_userName;
    private String          m_password;
    private String          m_errorMessage;
    private UserSessionData m_userData;

    private final RequesterService  m_requesterService = new RequesterService();
    private final MfaService        m_mfaService       = new MfaService();
    private final LoginGuardService m_loginGuard       = new LoginGuardService();

    // --- Stato del flusso di logon ---
    private static final String STEP_PASSWORD        = "PASSWORD";
    private static final String STEP_MFA_CODICE      = "MFA_CODICE";
    private static final String STEP_MFA_ATTIVAZIONE = "MFA_ATTIVAZIONE";
    private static final String STEP_MFA_CODICI      = "MFA_CODICI_RECUPERO";
    /** Tempo massimo tra password corretta e secondo fattore. */
    private static final long   MFA_TIMEOUT_MS = 5 * 60 * 1000L;
    /** Tempo per annotare/scaricare i codici di recupero. */
    private static final long   CODICI_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final String MSG_CREDENZIALI =
        "Credenziali non valide, oppure utente temporaneamente bloccato per troppi tentativi.";

    private String        m_step = STEP_PASSWORD;
    private RequesterInfo m_pendingInfo;          // utente con password verificata, in attesa del 2° fattore
    private long          m_pendingSince;
    private String        m_pendingSegreto;       // solo in attivazione: segreto proposto, non ancora salvato
    private String        m_mfaQrUrl;
    private String        m_mfaCodice;
    private java.util.List<String> m_codiciRecupero;  // mostrati una sola volta, salvati alla conferma
    private String        m_codiciUrl;
    private final Trigger m_codiciTrigger = new Trigger();

    private static final String FALLBACK_IMAGE = "/images/Sonia_Delaunay_Diagonale_1970.jpg";
    private static final int    IMAGE_COUNT    = 10;
    private String              m_backgroundImage = null;

    // Documentazione PDF — file statico in /docs/, accanto a /images/
    private static final String DOC_URL = "/docs/TicketingDoc.pdf";
    private final Trigger m_docTrigger = new Trigger();

    // =========================================================
    // COSTRUTTORE
    // =========================================================

    public LogonUI() {
        System.out.println("[LogonUI] Bean creato (v2 — logon su PostgreSQL)");
    }

    public String getBackgroundImage() {
        if (m_backgroundImage == null) {
            try {
                int n = 1 + new java.util.Random().nextInt(IMAGE_COUNT);
                m_backgroundImage = "/images/" + n + ".jpg";
            } catch (Exception e) {
                m_backgroundImage = FALLBACK_IMAGE;
            }
            System.out.println("[LogonUI] Immagine di sfondo: " + m_backgroundImage);
        }
        return m_backgroundImage;
    }

    @Override
    public String getPageName()                 { return "/Logon.xml"; }
    @Override
    public String getRootExpressionUsedInPage() { return "#{d.LogonUI}"; }

    // =========================================================
    // SETUP
    // =========================================================

    public void prepare(IListener listener) {
        m_listener = listener;
        System.out.println("[LogonUI] prepare() — listener impostato");
    }

    // =========================================================
    // GETTER / SETTER
    // =========================================================

    public String getPassword()          { return m_password; }
    public void setPassword(String v)    { this.m_password = v; }
    public String getUserName()          { return m_userName; }
    public void setUserName(String v)    { this.m_userName = v; }

    // =========================================================
    // AVVISO SULLA PAGINA DI LOGON
    // =========================================================

    /**
     * Messaggio mostrato sopra i campi di logon (es. sessione chiusa per un
     * accesso da un'altra postazione). Impostato da OutestUI quando riporta
     * l'utente al logon fuori da un'azione, dove la barra di stato non
     * verrebbe mostrata. Si cancella al primo tentativo di logon.
     */
    private String m_avviso;

    public void    setAvviso(String v)    { this.m_avviso = v; }
    public String  getAvviso()            { return m_avviso; }
    public boolean isAvvisoPresente()     { return m_avviso != null && !m_avviso.isEmpty() && isStepPassword(); }

    // =========================================================
    // DOCUMENTAZIONE (PDF)
    // =========================================================

    public Trigger getDocTrigger() { return m_docTrigger; }
    public String  getDocUrl()     { return DOC_URL; }

    /** Apre (scarica/visualizza) il PDF di documentazione in una nuova scheda del browser. */
    public void onOpenDocumentazione(ActionEvent ae) {
        System.out.println("[LogonUI] onOpenDocumentazione — " + DOC_URL);
        m_docTrigger.trigger();
    }

    // =========================================================
    // AZIONI
    // =========================================================

    public void onLogonAction(ActionEvent event) {
        m_avviso = null;
        try {
            System.out.println("[LogonUI] onLogonAction — user: " + m_userName);

            RequesterInfo info = checkLogonData();
            m_password = null; // non tenere la password in memoria oltre il necessario
            if (info == null) return;

            MfaService.Stato st = m_mfaService.getStato(info.getId_user());
            if (st.attiva) {
                avviaPassoMfa(info, STEP_MFA_CODICE);
            } else if (m_mfaService.isObbligatoria(info)) {
                avviaPassoMfa(info, STEP_MFA_ATTIVAZIONE);
                m_pendingSegreto = Totp.nuovoSegreto();
                String uri = Totp.otpauthUri(MfaService.issuer(), info.getId_user(), m_pendingSegreto);
                m_mfaQrUrl = TempFileManager.saveTempFile("mfa_qr_" + System.nanoTime() + ".png",
                                                          MfaService.qrPng(uri, 220));
            } else {
                completaLogon(info, false);
            }
        } catch (Exception e) {
            System.err.println("[LogonUI] ❌ Eccezione in onLogonAction:");
            e.printStackTrace();
            annullaPassoMfa();
            Statusbar.outputError("Errore durante il logon. Riprovare; se il problema persiste contattare l'assistenza.");
        }
    }

    /** Passo "codice": verifica il codice a 6 cifre dell'app. */
    public void onVerificaMfa(ActionEvent event) {
        if (!passoMfaValido(STEP_MFA_CODICE)) return;
        String idUser = m_pendingInfo.getId_user();
        try {
            if (m_loginGuard.isBloccato(idUser)) {
                annullaPassoMfa();
                Statusbar.outputError(MSG_CREDENZIALI);
                return;
            }
            boolean ok;
            String  motivo;
            if (MfaService.isFormatoCodiceRecupero(m_mfaCodice)) {
                ok     = m_mfaService.usaCodiceRecupero(idUser, m_mfaCodice);
                motivo = "codice di recupero errato o già usato";
            } else {
                MfaService.Stato st = m_mfaService.getStato(idUser);
                ok     = st.attiva && m_mfaService.verificaEConsuma(idUser, st, m_mfaCodice);
                motivo = "codice MFA errato o già usato";
            }
            if (ok) {
                dopoMfaRiuscita(m_pendingInfo);
            } else {
                codiceMfaErrato(motivo);
            }
        } catch (Exception e) {
            System.err.println("[LogonUI] ❌ Errore verifica MFA: " + e.getMessage());
            e.printStackTrace();
            Statusbar.outputError("Errore durante la verifica del codice. Riprovare.");
        }
    }

    /** Passo "attivazione": conferma il primo codice e salva il segreto. */
    public void onAttivaMfa(ActionEvent event) {
        if (!passoMfaValido(STEP_MFA_ATTIVAZIONE)) return;
        String idUser = m_pendingInfo.getId_user();
        try {
            if (m_loginGuard.isBloccato(idUser)) {
                annullaPassoMfa();
                Statusbar.outputError(MSG_CREDENZIALI);
                return;
            }
            if (m_mfaService.attiva(idUser, m_pendingSegreto, m_mfaCodice)) {
                AuditService.log(idUser, m_pendingInfo.getKunnr(), m_pendingInfo.getRuolo(),
                                 AuditService.MFA_ATTIVATA, AuditService.ESITO_OK, idUser, null);
                dopoMfaRiuscita(m_pendingInfo);
            } else {
                codiceMfaErrato("codice di attivazione MFA errato");
            }
        } catch (Exception e) {
            System.err.println("[LogonUI] ❌ Errore attivazione MFA: " + e.getMessage());
            e.printStackTrace();
            Statusbar.outputError("Errore durante l'attivazione. Riprovare.");
        }
    }

    public void onAnnullaMfa(ActionEvent event) {
        annullaPassoMfa();
    }

    /**
     * Secondo fattore superato: se l'utente non ha più codici di recupero
     * validi (appena attivato, utente attivato prima di questa versione,
     * oppure ha usato l'ultimo) gliene propone una nuova serie prima di
     * entrare; altrimenti entra subito.
     */
    private void dopoMfaRiuscita(RequesterInfo info) throws Exception {
        if (m_mfaService.codiciRecuperoRimasti(info.getId_user()) > 0) {
            completaLogon(info, true);
            return;
        }
        m_codiciRecupero = MfaService.nuoviCodiciRecupero();
        StringBuilder txt = new StringBuilder();
        txt.append("Codici di recupero — ").append(MfaService.issuer()).append("\r\n")
           .append("Utente: ").append(info.getId_user()).append("\r\n")
           .append("Generati il: ").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))).append("\r\n\r\n")
           .append("Ogni codice si può usare UNA sola volta, al posto del codice dell'app,\r\n")
           .append("se il telefono non è disponibile. Conservali in un posto sicuro.\r\n\r\n");
        for (String c : m_codiciRecupero) txt.append(c).append("\r\n");
        m_codiciUrl = TempFileManager.saveTempFile("codici-recupero-" + info.getId_user() + ".txt", "text/plain",
                                                   txt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        m_pendingInfo  = info;
        m_pendingSince = System.currentTimeMillis();
        m_step         = STEP_MFA_CODICI;
        m_mfaCodice    = null;
    }

    public void onScaricaCodici(ActionEvent event) {
        if (STEP_MFA_CODICI.equals(m_step) && m_codiciUrl != null) m_codiciTrigger.trigger();
    }

    /** L'utente conferma di aver salvato i codici: solo ora vengono registrati (come hash) ed entra. */
    public void onConfermaCodici(ActionEvent event) {
        if (!passoMfaValido(STEP_MFA_CODICI)) return;
        try {
            m_mfaService.salvaCodiciRecupero(m_pendingInfo.getId_user(), m_codiciRecupero);
            completaLogon(m_pendingInfo, true);
        } catch (Exception e) {
            System.err.println("[LogonUI] ❌ Errore salvataggio codici di recupero: " + e.getMessage());
            e.printStackTrace();
            Statusbar.outputError("Errore durante il salvataggio dei codici. Riprovare.");
        }
    }

    private void avviaPassoMfa(RequesterInfo info, String step) {
        m_pendingInfo  = info;
        m_pendingSince = System.currentTimeMillis();
        m_step         = step;
        m_mfaCodice    = null;
    }

    private boolean passoMfaValido(String stepAtteso) {
        if (m_pendingInfo == null || !stepAtteso.equals(m_step)) {
            annullaPassoMfa();
            return false;
        }
        long timeout = STEP_MFA_CODICI.equals(stepAtteso) ? CODICI_TIMEOUT_MS : MFA_TIMEOUT_MS;
        if (System.currentTimeMillis() - m_pendingSince > timeout) {
            annullaPassoMfa();
            Statusbar.outputWarning("Tempo scaduto: ripetere il logon.");
            return false;
        }
        return true;
    }

    private void codiceMfaErrato(String motivo) {
        String idUser = m_pendingInfo.getId_user();
        AuditService.log(idUser, m_pendingInfo.getKunnr(), m_pendingInfo.getRuolo(),
                         AuditService.MFA_FAIL, AuditService.ESITO_FAIL, idUser, motivo);
        m_mfaCodice = null;
        if (m_loginGuard.registraFallimento(idUser)) {
            annullaPassoMfa();
            Statusbar.outputError(MSG_CREDENZIALI);
        } else {
            Statusbar.outputError("Codice non valido. Controllare l'ora del telefono e inserire il codice attuale.");
        }
    }

    private void annullaPassoMfa() {
        m_step           = STEP_PASSWORD;
        m_pendingInfo    = null;
        m_pendingSegreto = null;
        m_mfaQrUrl       = null;
        m_mfaCodice      = null;
        m_codiciRecupero = null;
        m_codiciUrl      = null;
    }

    /** Logon riuscito (password + eventuale MFA): azzera i tentativi, registra, entra nell'app. */
    private void completaLogon(RequesterInfo info, boolean conMfa) {
        m_loginGuard.registraSuccesso(info.getId_user());
        info.setCodiciRecuperoRimasti(conMfa ? m_mfaService.codiciRecuperoRimasti(info.getId_user()) : -1);
        AuditService.log(info.getId_user(), info.getKunnr(), info.getRuolo(),
                         AuditService.LOGIN_OK, AuditService.ESITO_OK, info.getId_user(), conMfa ? "con MFA" : "senza MFA");
        m_userData = buildUserSessionData(info);
        annullaPassoMfa();
        System.out.println("[LogonUI] ✅ Accesso completato: " + info.getId_user() + (conMfa ? " (MFA)" : ""));
        Statusbar.outputSuccess("Accesso effettuato: " + info.getId_user());
        if (m_listener == null) {
            Statusbar.outputError("Errore interno: listener non impostato");
            return;
        }
        m_listener.reactOnLogon(m_userData);
    }

    // --- Binding per Logon.xml ---
    public boolean isStepPassword()        { return STEP_PASSWORD.equals(m_step); }
    public boolean isStepMfaCodice()       { return STEP_MFA_CODICE.equals(m_step); }
    public boolean isStepMfaAttivazione()  { return STEP_MFA_ATTIVAZIONE.equals(m_step); }
    public boolean isStepMfaCodiciRecupero() { return STEP_MFA_CODICI.equals(m_step); }
    public Trigger getCodiciTrigger()      { return m_codiciTrigger; }
    public String  getCodiciUrl()          { return m_codiciUrl; }
    public String  getCodiciRecuperoTesto() {
        if (m_codiciRecupero == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < m_codiciRecupero.size(); i++) {
            sb.append(m_codiciRecupero.get(i)).append(i % 2 == 0 ? "      " : "\n");
        }
        return sb.toString().trim();
    }
    public String  getMfaTestoCodiciRecupero() {
        return "Conserva questi codici in un posto sicuro (stampati, oppure nel tuo gestore di password). "
             + "Se il telefono non è disponibile, ognuno di essi vale per UN accesso al posto del codice dell'app. "
             + "Non verranno più mostrati.";
    }
    public boolean isStepMfa()             { return !isStepPassword(); }
    public String  getMfaQrUrl()           { return m_mfaQrUrl; }
    public String  getMfaSegretoLeggibile(){ return m_pendingSegreto != null ? Totp.formattaPerUtente(m_pendingSegreto) : ""; }
    public String  getMfaUtente()          { return m_pendingInfo != null ? m_pendingInfo.getId_user() : ""; }
    public String  getMfaIssuer()          { return MfaService.issuer(); }
    public String  getMfaTestoCodice() {
        return "Inserisci il codice a 6 cifre che la tua app di autenticazione mostra per \"" + MfaService.issuer() + "\", "
             + "oppure uno dei codici di recupero.";
    }
    public String  getMfaTestoAttivazione() {
        return "1. Usa un'app di autenticazione a tua scelta: ad esempio Aegis, FreeOTP, 2FAS, "
             + "Microsoft o Google Authenticator, le Password di Apple, oppure un gestore di password "
             + "come Bitwarden o KeePassXC.\n"
             + "2. Nell'app aggiungi un account e inquadra il QR code (o inserisci a mano la chiave qui sotto).\n"
             + "3. Inserisci qui il codice a 6 cifre che l'app mostra.";
    }
    public String  getMfaTestoChiaveManuale() {
        return "Non riesci a inquadrare? Inserisci a mano la chiave (account " + getMfaUtente() + "):\n"
             + getMfaSegretoLeggibile();
    }
    public String  getMfaCodice()          { return m_mfaCodice; }
    public void    setMfaCodice(String v)  { m_mfaCodice = v; }

    /**
     * Apre il popup di cambio password.
     * Il campo m_userName viene passato come reqid iniziale, così l'utente
     * può inserire lo username e poi cliccare "Cambia password" senza riscrivere.
     */
    public void onRequestNewPasswordAction(ActionEvent event) {
        System.out.println("[LogonUI] onRequestNewPasswordAction — apertura popup cambio password");

        final ChangePasswordUI cpUI = new ChangePasswordUI();
        cpUI.prepare(
            m_userName != null ? m_userName : "",
            new ChangePasswordUI.IListener() {
                @Override
                public void reactOnPasswordChanged() {
                    // Chiude il popup — l'utente può ora fare logon con la nuova password
                    closePopup(cpUI);
                    Statusbar.outputSuccess("Password aggiornata. Effettua il logon con la nuova password.");
                }
                @Override
                public void reactOnCancel() {
                    closePopup(cpUI);
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

    // =========================================================
    // LOGICA PRIVATA
    // =========================================================

    /**
     * Verifica username e password contro ticket_user (con blocco tentativi
     * integrato in RequesterService.authenticate). Restituisce l'utente se
     * la password è corretta, null altrimenti (messaggio già mostrato).
     */
    private RequesterInfo checkLogonData() {
        m_errorMessage = null;

        if (m_userName == null || m_userName.trim().isEmpty()) {
            m_errorMessage = "Inserire lo username";
            Statusbar.outputError(m_errorMessage);
            return null;
        }
        if (m_password == null || m_password.trim().isEmpty()) {
            m_errorMessage = "Inserire la password";
            Statusbar.outputError(m_errorMessage);
            return null;
        }

        String user = m_userName.trim();
        try {
            RequesterInfo info = m_requesterService.authenticate(user, m_password);
            if (info == null) {
                AuditService.log(user, null, null, AuditService.LOGIN_FAIL, AuditService.ESITO_FAIL, user,
                                 "password errata, utente inesistente/disattivato o bloccato");
                m_errorMessage = MSG_CREDENZIALI;
                Statusbar.outputError(m_errorMessage);
                return null;
            }
            System.out.println("[LogonUI] ✅ Password verificata: " + info);
            return info;

        } catch (Exception e) {
            System.err.println("[LogonUI] ❌ Eccezione in checkLogonData: " + e.getMessage());
            e.printStackTrace();
            m_errorMessage = "Errore durante il logon. Riprovare; se il problema persiste contattare l'assistenza.";
            Statusbar.outputError(m_errorMessage);
            return null;
        }
    }

    /**
     * Converte RequesterInfo in UserSessionData, mantenendo la struttura
     * che OutestUI/ViewSessionContext già usano.
     */
    private UserSessionData buildUserSessionData(RequesterInfo info) {
        UserSessionData usd = new UserSessionData();
        usd.setUsername   (info.getId_user());      // chiave di login, sempre valorizzata
        usd.setUtente     (info.getNomeOReqid());   // nome visualizzabile
        usd.setKunnr      (info.getKunnr() != null ? info.getKunnr() : "");
        usd.setRichiedente(info.getReqid());         // può essere vuoto per AMS/ADMIN
        usd.setOwnAll     (info.isVedeTutti() ? "ALL" : "");
        usd.setHasError   (false);
        // Salva anche il RequesterInfo completo per uso futuro
        usd.setRequesterInfo(info);
        return usd;
    }
}