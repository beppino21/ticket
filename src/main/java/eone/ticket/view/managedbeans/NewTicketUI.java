package eone.ticket.view.managedbeans;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.events.BaseActionEventUpload;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.Trigger;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;
import org.eclnt.jsfserver.util.tempfile.TempFileManager;

import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.RequesterInfo;
import eone.ticket.model.TicketAttachment;
import eone.ticket.model.TicketComment;
import eone.ticket.model.TicketDraft;
import eone.ticket.service.CommentService;
import eone.ticket.service.MailService;
import eone.ticket.service.RequesterService;
import eone.ticket.service.TicketDraftService;
import eone.ticket.service.TicketReferenteService;

/**
 * UI per la creazione di un nuovo ticket DRAFT da parte del cliente.
 * Campi: titolo (obbligatorio) + commento iniziale (obbligatorio) + allegati (opzionali).
 * Il resto dei campi (categoria, prodotto, modulo...) viene lasciato vuoto —
 * li completerà l'AMS DISPATCHER in fase di fusione con SAP.
 */
@CCGenClass(expressionBase = "#{d.NewTicketUI}")
public class NewTicketUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void reactOnBackToMenu();
        void reactOnDraftCreated(long draftId);
    }

    private IListener m_listener;

    private final TicketDraftService draftService   = new TicketDraftService();
    private final CommentService     commentService  = new CommentService();
    private final MailService        mailService     = new MailService();
    private final RequesterService   requesterService = new RequesterService();
    private final TicketReferenteService referenteService = new TicketReferenteService();

    private String m_titolo;
    private String m_commentoTesto;

    // Referente (obbligatorio): richiedenti + referenti_cli dello stesso
    // kunnr, incluso il richiedente stesso che sta aprendo il ticket.
    private final ValidValuesBinding m_referenteVVB = new ValidValuesBinding();
    private String m_reqidReferente;

    private FIXGRIDListBinding<GridAttachItem> m_gridPending = new FIXGRIDListBinding<>();
    private List<TicketAttachment> m_pendingAttachments      = new ArrayList<>();

    private Trigger m_downloadTrigger = new Trigger();
    private String  m_downloadUrl;

    // =========================
    // INNER CLASS — ALLEGATI PENDING
    // =========================

    public class GridAttachItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final TicketAttachment attachment;

        public GridAttachItem(TicketAttachment a) { this.attachment = a; }

        public String getFilename()          { return nn(attachment.getFilename()); }
        public String getFileSizeFormatted() { return attachment.getFileSizeFormatted(); }

        public void onRemove(ActionEvent ae) {
            m_pendingAttachments.remove(attachment);
            rebuildGridPending();
        }
    }

    // =========================
    // COSTRUTTORE / PREPARE
    // =========================

    public NewTicketUI() {
        caricaEligibiliReferente();
    }

    /**
     * Carica in m_referenteVVB i soggetti selezionabili come referente:
     * richiedenti + referenti_cli dello stesso kunnr, incluso il
     * richiedente stesso (così per un cliente senza REFERENTE_CLI
     * configurati la procedura resta di fatto identica a oggi: basta
     * selezionare se stesso).
     */
    private void caricaEligibiliReferente() {
        try {
            ViewSessionContext ctx = ViewSessionContext.instance();
            String kunnr = ctx.getKunnr();
            m_referenteVVB.clear();
            for (RequesterInfo r : requesterService.getEligibiliReferente(kunnr, ctx.getRichiedente())) {
                m_referenteVVB.addValidValue(r.getReqid(), r.getNomeOReqid());
            }
            // Preseleziona il richiedente stesso, se presente tra le opzioni.
            if (ctx.getRichiedente() != null) {
                m_reqidReferente = ctx.getRichiedente();
            }
        } catch (Exception e) {
            System.err.println("[NewTicketUI] Errore caricamento elenco referenti: " + e.getMessage());
        }
    }

    public void prepare(IListener listener) {
        this.m_listener = listener;
    }

    // =========================
    // AZIONI
    // =========================

    public void backToMenu(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnBackToMenu();
    }

    public void saveDraft(ActionEvent ae) {
        // Validazione
        if (m_titolo == null || m_titolo.trim().isEmpty()) {
            Statusbar.outputWarning("Il titolo del ticket è obbligatorio");
            return;
        }
        if (m_commentoTesto == null || m_commentoTesto.trim().isEmpty()) {
            Statusbar.outputWarning("Il commento iniziale è obbligatorio");
            return;
        }
        if (m_reqidReferente == null || m_reqidReferente.trim().isEmpty()) {
            Statusbar.outputWarning("Il referente è obbligatorio");
            return;
        }

        ViewSessionContext ctx = ViewSessionContext.instance();

        // DIAGNOSTICA — verificare in log se richiedente arriva già vuoto
        // dalla sessione (problema a monte, in Logon/Outest) oppure si perde
        // dopo (problema in questo metodo o nell'INSERT).
        System.out.println("[NewTicketUI] saveDraft() — sessione: kunnr='" + ctx.getKunnr() +
                            "', richiedente='" + ctx.getRichiedente() +
                            "', ruolo='" + ctx.getRuolo() +
                            "', username='" + ctx.getUsername() + "'");

        // Validazione obbligatoria: chi apre un DRAFT deve essere un CLIENTE
        // con kunnr e reqid valorizzati. Nessun altro ruolo (ADMIN incluso)
        // può creare un DRAFT — la voce di menu è già nascosta per gli altri
        // ruoli, ma il controllo va ripetuto qui perché è la sede in cui il
        // dato viene davvero scritto su ticket_draft.
        if (!ctx.isCliente()
                || ctx.getKunnr() == null || ctx.getKunnr().trim().isEmpty()
                || ctx.getRichiedente() == null || ctx.getRichiedente().trim().isEmpty()) {
            Statusbar.outputError("Impossibile aprire il ticket: utente non riconosciuto come richiedente cliente " +
                                   "(kunnr/reqid mancanti). Contattare l'assistenza.");
            System.err.println("[NewTicketUI] saveDraft() bloccato — dati richiedente incompleti: " +
                                "kunnr='" + ctx.getKunnr() + "', richiedente='" + ctx.getRichiedente() +
                                "', ruolo='" + ctx.getRuolo() + "', username='" + ctx.getUsername() + "'");
            return;
        }

        try {
            // 1. Crea il draft
            TicketDraft draft = new TicketDraft();
            draft.setKunnr (ctx.getKunnr());
            draft.setReqid (ctx.getRichiedente());
            draft.setIdUser(ctx.getUsername());
            draft.setTitolo(m_titolo.trim());

            long draftId = draftService.createDraft(draft);

            // 1bis. Referente obbligatorio: salvato in ticket_referente,
            // riassegnabile in seguito da richiedente o referente stesso.
            referenteService.setReferente(draft.getTicktKey(), m_reqidReferente.trim(), ctx.getUsername());

            // 2. Salva il commento iniziale con gli allegati
            TicketComment comment = new TicketComment();
            comment.setTickt     (draft.getTicktKey());   // "DRAFT-{id}"
            comment.setKunnr     (ctx.getKunnr());
            comment.setAutoreTipo(TicketComment.TIPO_CLIENTE);
            comment.setAutoreId  (ctx.getUsername());
            comment.setTesto     (m_commentoTesto.trim());
            // Un DRAFT appena creato ha un solo stato possibile — non è più
            // una scelta dell'utente (non avrebbe senso aprire un ticket
            // nuovo già "risolto" o "in attesa di chiusura").
            comment.setStatoTicket(TicketComment.STATO_CLI_ATTESA_ASSISTENZA);

            commentService.saveComment(comment, m_pendingAttachments);

            Statusbar.outputSuccess("Ticket aperto correttamente (DRAFT-" + draftId + ")");
            System.out.println("[NewTicketUI] Draft creato: DRAFT-" + draftId +
                               " da utente " + ctx.getUsername());

            // 3. Notifica ai DISPATCHER attivi — stesso formato/meccanismo usato
            // per l'interlocuzione CLIENTE↔AMS. Un errore di invio non deve far
            // fallire la creazione del DRAFT (già avvenuta) — viene solo loggato.
            inviaNotificaDispatcher(draft, comment, m_pendingAttachments);

            if (m_listener != null) m_listener.reactOnDraftCreated(draftId);

        } catch (Exception e) {
            Statusbar.outputError("Errore creazione ticket: " + e.getMessage());
            System.err.println("[NewTicketUI] Errore saveDraft: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Notifica via email tutti gli utenti DISPATCHER attivi che un nuovo
     * DRAFT è in attesa di smistamento — stesso meccanismo/formato usato
     * per l'interlocuzione CLIENTE↔AMS (MailService.sendNotificaCommento).
     * Non blocca la creazione del DRAFT in caso di errore: viene solo loggato.
     */
    private void inviaNotificaDispatcher(TicketDraft draft, TicketComment comment,
                                          List<TicketAttachment> allegati) {
        try {
            List<RequesterInfo> dispatchers = requesterService.getActiveDispatchers();
            if (dispatchers.isEmpty()) {
                System.out.println("[NewTicketUI] Notifica DISPATCHER saltata: nessun utente DISPATCHER attivo con email");
                return;
            }
            for (RequesterInfo dispatcher : dispatchers) {
                if (dispatcher.getEmail() == null || dispatcher.getEmail().trim().isEmpty()) continue;
                try {
                    mailService.sendNotificaCommento(
                        dispatcher.getEmail(), draft.getTicktKey(), comment.getStatoTicketLabel(),
                        comment.getAutoreId(), comment.getTesto(), allegati);
                } catch (Exception e) {
                    System.err.println("[NewTicketUI] Errore invio notifica a DISPATCHER " +
                        dispatcher.getId_user() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[NewTicketUI] Errore recupero DISPATCHER per notifica: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void onFileUpload(ActionEvent ae) {
        if (!(ae instanceof BaseActionEventUpload)) return;
        BaseActionEventUpload bae = (BaseActionEventUpload) ae;
        for (int i = 0; i < bae.getNumberOfUploadedFiles(); i++) {
            String filename = bae.getClientFileName(i);
            String hex = bae.getHexByteString(i);
            if (hex == null || hex.isEmpty()) {
                Statusbar.outputWarning("File vuoto ignorato: " + filename);
                continue;
            }
            byte[] data = hexToBytes(hex);
            TicketAttachment a = new TicketAttachment();
            a.setFilename(filename);
            a.setFileData(data);
            a.setFileSize(data.length);
            a.setMimeType(CommentService.detectMimeType(filename));
            m_pendingAttachments.add(a);
        }
        rebuildGridPending();
        Statusbar.outputSuccess(bae.getNumberOfUploadedFiles() + " file aggiunto/i");
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length(); byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        return data;
    }

    private void rebuildGridPending() {
        m_gridPending.getItems().clear();
        for (TicketAttachment a : m_pendingAttachments)
            m_gridPending.getItems().add(new GridAttachItem(a));
    }

    // =========================
    // GETTERS / SETTERS
    // =========================

    @Override public String getPageName()                 { return "/NewTicket.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.NewTicketUI}"; }

    public String getTitolo()            { return m_titolo; }
    public void setTitolo(String v)      { this.m_titolo = v; }

    public String getCommentoTesto()          { return m_commentoTesto; }
    public void setCommentoTesto(String v)    { this.m_commentoTesto = v; }

    public ValidValuesBinding getReferenteVVB() { return m_referenteVVB; }
    public String getReqidReferente()           { return m_reqidReferente; }
    public void setReqidReferente(String v)     { this.m_reqidReferente = v; }

    public FIXGRIDListBinding<GridAttachItem> getGridPending() { return m_gridPending; }
    public boolean getHasPending()  { return !m_pendingAttachments.isEmpty(); }
    public int     getPendingCount() { return m_pendingAttachments.size(); }

    public Trigger getDownloadTrigger() { return m_downloadTrigger; }
    public String  getDownloadUrl()     { return m_downloadUrl; }

    private String nn(String s) { return s != null ? s : ""; }
}