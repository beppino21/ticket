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
import org.eclnt.jsfserver.pagebean.PageBean;
import org.eclnt.jsfserver.util.tempfile.TempFileManager;

import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.TicketAttachment;
import eone.ticket.model.TicketComment;
import eone.ticket.model.TicketDraft;
import eone.ticket.service.CommentService;
import eone.ticket.service.MailService;
import eone.ticket.service.TicketDraftService;

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

    private String m_titolo;
    private String m_commentoTesto;
    private String m_commentoStato;

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

    public NewTicketUI() {}

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
        if (m_commentoStato == null || m_commentoStato.trim().isEmpty()) {
            Statusbar.outputWarning("Selezionare lo stato del ticket");
            return;
        }

        ViewSessionContext ctx = ViewSessionContext.instance();

        try {
            // 1. Crea il draft
            TicketDraft draft = new TicketDraft();
            draft.setKunnr (ctx.getKunnr());
            draft.setReqid (ctx.getRichiedente());
            draft.setIdUser(ctx.getUsername());
            draft.setTitolo(m_titolo.trim());

            long draftId = draftService.createDraft(draft);

            // 2. Salva il commento iniziale con gli allegati
            TicketComment comment = new TicketComment();
            comment.setTickt     (draft.getTicktKey());   // "DRAFT-{id}"
            comment.setKunnr     (ctx.getKunnr());
            comment.setAutoreTipo(TicketComment.TIPO_CLIENTE);
            comment.setAutoreId  (ctx.getUsername());
            comment.setTesto     (m_commentoTesto.trim());
            comment.setStatoTicket(m_commentoStato);

            commentService.saveComment(comment, m_pendingAttachments);

            Statusbar.outputSuccess("Ticket aperto correttamente (DRAFT-" + draftId + ")");
            System.out.println("[NewTicketUI] Draft creato: DRAFT-" + draftId +
                               " da utente " + ctx.getUsername());

            // 3. Notifica (dry-run finché SMTP non configurato)
            // Il DISPATCHER non ha ancora un id_user specifico qui —
            // la notifica al DISPATCHER è opzionale in questa fase.

            if (m_listener != null) m_listener.reactOnDraftCreated(draftId);

        } catch (Exception e) {
            Statusbar.outputError("Errore creazione ticket: " + e.getMessage());
            System.err.println("[NewTicketUI] Errore saveDraft: " + e.getMessage());
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

    public String getCommentoStato()          { return m_commentoStato; }
    public void setCommentoStato(String v)    { this.m_commentoStato = v; }

    public FIXGRIDListBinding<GridAttachItem> getGridPending() { return m_gridPending; }
    public boolean getHasPending()  { return !m_pendingAttachments.isEmpty(); }
    public int     getPendingCount() { return m_pendingAttachments.size(); }

    public Trigger getDownloadTrigger() { return m_downloadTrigger; }
    public String  getDownloadUrl()     { return m_downloadUrl; }

    private String nn(String s) { return s != null ? s : ""; }
}