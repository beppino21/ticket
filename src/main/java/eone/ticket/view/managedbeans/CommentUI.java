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
import eone.ticket.service.CommentService;

@CCGenClass(expressionBase = "#{d.CommentUI}")
public class CommentUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private final CommentService commentService = new CommentService();

    private String m_currentTickt;
    private FIXGRIDListBinding<GridCommentItem>    m_gridComments   = new FIXGRIDListBinding<>();
    private FIXGRIDListBinding<GridAttachListItem> m_gridAttachList = new FIXGRIDListBinding<>();
    private FIXGRIDListBinding<GridAttachItem>     m_gridPending    = new FIXGRIDListBinding<>();
    private List<TicketAttachment> m_pendingAttachments             = new ArrayList<>();
    private boolean m_attachListVisible = false;
    private String  m_selectedCommentTesto = "";
    private String  m_newCommentText;
    private String  m_newCommentStato;
    private Trigger m_downloadTrigger = new Trigger();
    private String  m_downloadUrl;
    private String  m_testoEspanso;
    private Trigger m_expandTrigger = new Trigger();

    // =========================
    // INNER CLASS — GRID COMMENTI
    // =========================

    public class GridCommentItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final TicketComment comment;

        public GridCommentItem(TicketComment c) { this.comment = c; }

        public long   getCommentId()    { return comment.getId(); }
        public String getCreatedAt()    { return comment.getCreatedAtFormatted(); }
        public String getAutoreId()     { return nn(comment.getAutoreId()); }
        public String getAutoreTipo()   { return nn(comment.getAutoreTipo()); }
        public String getStatoLabel()   { return comment.getStatoTicketLabel(); }
        public boolean isFromCliente()  { return comment.isFromCliente(); }

        /** Testo completo — textpane con dynamicheightsizing gestisce l'altezza */
        public String getTesto() {
            return comment.getTesto() != null ? comment.getTesto() : "";
        }

        /**
         * Label bottone allegati: "N all." se presenti, "" altrimenti.
         * Bottone sempre presente — click su stringa vuota non fa nulla di visibile.
         */
        public String getAllegatiLabel() {
            int n = comment.getAttachCount();
            if (n == 0) return "Dettaglio";
            return n == 1 ? "1 all." : n + " all.";
        }

        public void onEspandi(ActionEvent ae) {
            if (comment.getTesto() == null) return;
            m_testoEspanso = comment.getTesto();
            m_expandTrigger.trigger();
        }

        public void onMostraAllegati(ActionEvent ae) {
        	
            m_gridAttachList.getItems().clear();
            String testoCompleto = comment.getTesto();
            System.out.println("[DEBUG] onMostraAllegati - lunghezza testo: " + 
                (testoCompleto != null ? testoCompleto.length() : "NULL"));
            m_selectedCommentTesto = testoCompleto != null ? testoCompleto : "";
            
            m_gridAttachList.getItems().clear();
            m_selectedCommentTesto = comment.getTesto() != null ? comment.getTesto() : "";
            List<TicketAttachment> atts = comment.getAttachments();
            if (atts != null) {
                for (TicketAttachment a : atts) {
                    m_gridAttachList.getItems().add(new GridAttachListItem(a));
                }
            }
            m_attachListVisible = true;
        }
    }

    // =========================
    // INNER CLASS — ALLEGATI COMMENTO SELEZIONATO
    // =========================

    public class GridAttachListItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final TicketAttachment attachment;

        public GridAttachListItem(TicketAttachment a) { this.attachment = a; }

        public String getFilename()          { return nn(attachment.getFilename()); }
        public String getFileSizeFormatted() { return attachment.getFileSizeFormatted(); }

        public void onDownload(ActionEvent ae) {
            prepareDownload(attachment.getId());
        }
    }

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
    // COSTRUTTORE / INIT
    // =========================

    public CommentUI() {}

    public void init(String tickt) {
        this.m_currentTickt = tickt;
        m_newCommentText    = null;
        m_newCommentStato   = null;
        m_testoEspanso      = null;
        m_attachListVisible = false;
        m_pendingAttachments.clear();
        m_gridPending.getItems().clear();
        m_gridAttachList.getItems().clear();
        loadComments();
    }

    private void loadComments() {
        m_gridComments.getItems().clear();
        m_gridAttachList.getItems().clear();
        m_attachListVisible = false;
        try {
            List<TicketComment> list = commentService.getComments(m_currentTickt);
            for (TicketComment c : list) {
                m_gridComments.getItems().add(new GridCommentItem(c));
            }
        } catch (Exception e) {
            Statusbar.outputError("Errore caricamento commenti: " + e.getMessage());
            System.err.println("[CommentUI] Errore loadComments: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void closeAttachList(ActionEvent ae) {
        m_gridAttachList.getItems().clear();
        m_attachListVisible = false;
    }

    public void saveComment(ActionEvent ae) {
        System.out.println(">>> saveComment: stato=[" + m_newCommentStato + "] testo=[" + m_newCommentText + "]");
        if (m_newCommentText == null || m_newCommentText.trim().isEmpty()) {
            Statusbar.outputWarning("Il testo del commento è obbligatorio");
            return;
        }
        if (m_newCommentStato == null || m_newCommentStato.isEmpty()) {
            Statusbar.outputWarning("Selezionare lo stato del ticket");
            return;
        }
        ViewSessionContext ctx = ViewSessionContext.instance();
        String kunnr = ctx.getKunnr();
        String autoreTipo = (kunnr != null && !kunnr.trim().isEmpty())
            ? TicketComment.TIPO_CLIENTE : TicketComment.TIPO_ASSISTENZA;

        TicketComment comment = new TicketComment();
        comment.setTickt      (m_currentTickt);
        comment.setKunnr      (kunnr);
        comment.setAutoreTipo (autoreTipo);
        comment.setAutoreId   (ctx.getUsername());
        comment.setTesto      (m_newCommentText.trim());
        comment.setStatoTicket(m_newCommentStato);

        try {
            commentService.saveComment(comment, m_pendingAttachments);
            m_newCommentText  = null;
            m_newCommentStato = null;
            m_pendingAttachments.clear();
            m_gridPending.getItems().clear();
            loadComments();
            Statusbar.outputSuccess("Commento salvato correttamente");
        } catch (Exception e) {
            Statusbar.outputError("Errore salvataggio: " + e.getMessage());
            System.err.println("[CommentUI] Errore saveComment: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void setStatoWaitAms(ActionEvent ae)    { m_newCommentStato = "WAIT_AMS"; }
    public void setStatoWaitCli(ActionEvent ae)    { m_newCommentStato = "WAIT_CLI"; }
    public void setStatoInProgress(ActionEvent ae) { m_newCommentStato = "IN_PROGRESS"; }
    public void setStatoResolved(ActionEvent ae)   { m_newCommentStato = "RESOLVED"; }
    public void setStatoClosed(ActionEvent ae)     { m_newCommentStato = "CLOSED"; }

    public void onFileUpload(ActionEvent ae) {
        if (!(ae instanceof BaseActionEventUpload)) return;
        BaseActionEventUpload bae = (BaseActionEventUpload) ae;
        for (int i = 0; i < bae.getNumberOfUploadedFiles(); i++) {
            String filename = bae.getClientFileName(i);
            String hex = bae.getHexByteString(i);
            if (hex == null || hex.isEmpty()) { Statusbar.outputWarning("File vuoto: " + filename); continue; }
            byte[] data = hexToBytes(hex);
            TicketAttachment a = new TicketAttachment();
            a.setFilename(filename); a.setFileData(data);
            a.setFileSize(data.length); a.setMimeType(CommentService.detectMimeType(filename));
            m_pendingAttachments.add(a);
        }
        rebuildGridPending();
        Statusbar.outputSuccess(bae.getNumberOfUploadedFiles() + " file aggiunto/i");
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length(); byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        return data;
    }

    private void rebuildGridPending() {
        m_gridPending.getItems().clear();
        for (TicketAttachment a : m_pendingAttachments)
            m_gridPending.getItems().add(new GridAttachItem(a));
    }

    void prepareDownload(long attachmentId) {
        try {
            TicketAttachment a = commentService.getAttachmentData(attachmentId);
            if (a == null) { Statusbar.outputError("Allegato non trovato (id=" + attachmentId + ")"); return; }
            m_downloadUrl = TempFileManager.saveTempFile(
                a.getFilename(),
                a.getMimeType() != null ? a.getMimeType() : "application/octet-stream",
                a.getFileData());
            m_downloadTrigger.trigger();
        } catch (Exception e) {
            Statusbar.outputError("Errore download: " + e.getMessage());
        }
    }

    private String nn(String s) { return s != null ? s : ""; }

    @Override public String getPageName()                 { return "/CommentDialog.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.CommentUI}"; }

    public String getCurrentTickt()             { return m_currentTickt; }
    public FIXGRIDListBinding<GridCommentItem>    getGridComments()   { return m_gridComments; }
    public FIXGRIDListBinding<GridAttachItem>     getGridPending()    { return m_gridPending; }
    public FIXGRIDListBinding<GridAttachListItem> getGridAttachList() { return m_gridAttachList; }
    public boolean getAttachListVisible()       { return m_attachListVisible; }
    public String  getSelectedCommentTesto()    { return m_selectedCommentTesto; }
    public String getNewCommentText()           { return m_newCommentText; }
    public void setNewCommentText(String t)     { this.m_newCommentText = t; }
    public String getNewCommentStato()          { return m_newCommentStato; }
    public void setNewCommentStato(String s)    { this.m_newCommentStato = s; }
    public Trigger getDownloadTrigger()         { return m_downloadTrigger; }
    public String  getDownloadUrl()             { return m_downloadUrl; }
    public Trigger getExpandTrigger()           { return m_expandTrigger; }
    public String  getTestoEspanso()            { return m_testoEspanso != null ? m_testoEspanso : ""; }
    public boolean getHasPending()              { return !m_pendingAttachments.isEmpty(); }
    public int     getPendingCount()            { return m_pendingAttachments.size(); }
}
