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

/**
 * CommentUI v2 - layout a due colonne (split verticale):
 *   sinistra: elenco commenti stile "lista messaggi" (click riga = seleziona)
 *   destra in alto: dettaglio del commento selezionato (testo completo + allegati)
 *   destra in basso: form di inserimento nuovo commento
 *
 * Eliminato il bottone "Dettaglio"/"Espandi": la selezione di riga sostituisce
 * entrambi i meccanismi precedenti.
 */
@CCGenClass(expressionBase = "#{d.CommentUI}")
public class CommentUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private final CommentService commentService = new CommentService();

    private String m_currentTickt;

    // Lista commenti (colonna sinistra)
    private FIXGRIDListBinding<GridCommentItem> m_gridComments = new FIXGRIDListBinding<>();

    // Commento attualmente selezionato (pannello destro in alto)
    private TicketComment m_selectedComment;
    private FIXGRIDListBinding<GridAttachListItem> m_gridAttachList = new FIXGRIDListBinding<>();

    // Form nuovo commento (pannello destro in basso)
    private String m_newCommentText;
    private String m_newCommentStato;
    private FIXGRIDListBinding<GridAttachItem> m_gridPending = new FIXGRIDListBinding<>();
    private List<TicketAttachment> m_pendingAttachments      = new ArrayList<>();

    // Download
    private Trigger m_downloadTrigger = new Trigger();
    private String  m_downloadUrl;

    public class GridCommentItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final TicketComment comment;

        public GridCommentItem(TicketComment c) { this.comment = c; }

        public long   getCommentId()   { return comment.getId(); }
        public String getCreatedAt()   { return comment.getCreatedAtFormatted(); }
        public String getAutoreId()    { return nn(comment.getAutoreId()); }
        public String getAutoreTipo()  { return nn(comment.getAutoreTipo()); }
        public String getStatoLabel()  { return comment.getStatoTicketLabel(); }
        public boolean isFromCliente() { return comment.isFromCliente(); }
        public int    getAttachCount() { return comment.getAttachCount(); }

        public String getAnteprimaTesto() {
            String t = comment.getTesto();
            if (t == null) return "";
            int nl = t.indexOf('\n');
            String prima = (nl > 0) ? t.substring(0, nl) : t;
            return prima.length() > 80 ? prima.substring(0, 77) + "..." : prima;
        }

        public String getAllegatiLabel() {
            int n = comment.getAttachCount();
            if (n == 0) return "";
            return n == 1 ? "1 allegato" : n + " allegati";
        }

        public String getRowBackground() {
            if (m_selectedComment != null && m_selectedComment.getId() == comment.getId()) {
                return "#D6E8FB";
            }
            return isFromCliente() ? "#FAFAFA" : "#FFFFFF";
        }

        /** Colore del badge di stato (scala termica) */
        public String getStatoColor()     { return comment.getStatoColor(); }
        public String getStatoTextColor() { return comment.getStatoTextColor(); }

        /** Click riga: seleziona il commento e popola il pannello destro */
        @Override
        public void onRowSelect() {
            selectComment(comment);
        }
        @Override
        public void onRowExecute() { onRowSelect(); }

        private String nn(String s) { return s != null ? s : ""; }
    }

    public class GridAttachListItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final TicketAttachment attachment;

        public GridAttachListItem(TicketAttachment a) { this.attachment = a; }

        public String getFilename()          { return nn(attachment.getFilename()); }
        public String getFileSizeFormatted() { return attachment.getFileSizeFormatted(); }

        public void onDownload(ActionEvent ae) {
            prepareDownload(attachment.getId());
        }

        private String nn(String s) { return s != null ? s : ""; }
    }

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

        private String nn(String s) { return s != null ? s : ""; }
    }

    public CommentUI() {}

    public void init(String tickt) {
        this.m_currentTickt = tickt;
        m_newCommentText    = null;
        m_newCommentStato   = null;
        m_selectedComment   = null;
        m_pendingAttachments.clear();
        m_gridPending.getItems().clear();
        m_gridAttachList.getItems().clear();
        loadComments();
    }

    private void loadComments() {
        m_gridComments.getItems().clear();
        try {
            List<TicketComment> list = commentService.getComments(m_currentTickt);
            for (TicketComment c : list) {
                m_gridComments.getItems().add(new GridCommentItem(c));
            }
            if (!list.isEmpty()) {
                selectComment(list.get(list.size() - 1));
            } else {
                m_selectedComment = null;
                m_gridAttachList.getItems().clear();
            }
        } catch (Exception e) {
            Statusbar.outputError("Errore caricamento commenti: " + e.getMessage());
            System.err.println("[CommentUI] Errore loadComments: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void selectComment(TicketComment comment) {
        m_selectedComment = comment;
        m_gridAttachList.getItems().clear();
        if (comment != null && comment.getAttachments() != null) {
            for (TicketAttachment a : comment.getAttachments()) {
                m_gridAttachList.getItems().add(new GridAttachListItem(a));
            }
        }
    }

    public void saveComment(ActionEvent ae) {
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
        String autoreTipo = ctx.isCliente()
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
            Statusbar.outputError("Errore salvataggio commento: " + e.getMessage());
            System.err.println("[CommentUI] Errore saveComment: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Metodi legacy non più usati direttamente (la combobox usa value= diretto),
    // mantenuti come scorciatoie eventualmente richiamabili da bottoni rapidi.
    public void setStatoCliAttesaAss(ActionEvent ae)    { m_newCommentStato = TicketComment.STATO_CLI_ATTESA_ASSISTENZA; }
    public void setStatoCliSollecitoAss(ActionEvent ae) { m_newCommentStato = TicketComment.STATO_CLI_SOLLECITO_ASSISTENZA; }
    public void setStatoCliRichChiusura(ActionEvent ae) { m_newCommentStato = TicketComment.STATO_CLI_RICHIESTA_CHIUSURA; }
    public void setStatoCliRisolto(ActionEvent ae)      { m_newCommentStato = TicketComment.STATO_CLI_RISOLTO; }
    public void setStatoAssAttesaCli(ActionEvent ae)    { m_newCommentStato = TicketComment.STATO_ASS_ATTESA_CLIENTE; }
    public void setStatoAssSollecitoCli(ActionEvent ae) { m_newCommentStato = TicketComment.STATO_ASS_SOLLECITO_CLIENTE; }
    public void setStatoAssConcluso(ActionEvent ae)     { m_newCommentStato = TicketComment.STATO_ASS_CONCLUSO; }

    /** True se l'utente loggato è CLIENTE — pilota quali opzioni di stato mostrare nel form */
    public boolean getIsCliente() { return ViewSessionContext.instance().isCliente(); }

    /** True se l'utente loggato è ASSISTENZA */
    public boolean getIsAssistenza() { return ViewSessionContext.instance().isAms(); }

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
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private void rebuildGridPending() {
        m_gridPending.getItems().clear();
        for (TicketAttachment a : m_pendingAttachments) {
            m_gridPending.getItems().add(new GridAttachItem(a));
        }
    }

    void prepareDownload(long attachmentId) {
        try {
            TicketAttachment a = commentService.getAttachmentData(attachmentId);
            if (a == null) {
                Statusbar.outputError("Allegato non trovato (id=" + attachmentId + ")");
                return;
            }
            m_downloadUrl = TempFileManager.saveTempFile(
                a.getFilename(),
                a.getMimeType() != null ? a.getMimeType() : "application/octet-stream",
                a.getFileData()
            );
            m_downloadTrigger.trigger();
        } catch (Exception e) {
            Statusbar.outputError("Errore download allegato: " + e.getMessage());
            System.err.println("[CommentUI] Errore download: " + e.getMessage());
        }
    }

    @Override public String getPageName()                 { return "/CommentDialog.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.CommentUI}"; }

    public String getCurrentTickt() { return m_currentTickt; }

    public FIXGRIDListBinding<GridCommentItem>    getGridComments()   { return m_gridComments; }
    public FIXGRIDListBinding<GridAttachItem>     getGridPending()    { return m_gridPending; }
    public FIXGRIDListBinding<GridAttachListItem> getGridAttachList() { return m_gridAttachList; }

    public boolean getHasSelectedComment() { return m_selectedComment != null; }

    public String getSelectedCommentTesto() {
        return m_selectedComment != null && m_selectedComment.getTesto() != null
            ? m_selectedComment.getTesto() : "";
    }

    public String getSelectedCommentHeader() {
        if (m_selectedComment == null) return "";
        return m_selectedComment.getCreatedAtFormatted() + " - "
             + nn(m_selectedComment.getAutoreId()) + " ("
             + m_selectedComment.getStatoTicketLabel() + ")";
    }

    public String getSelectedStatoColor() {
        return m_selectedComment != null ? m_selectedComment.getStatoColor() : "#CCCCCC";
    }

    public String getSelectedStatoTextColor() {
        return m_selectedComment != null ? m_selectedComment.getStatoTextColor() : "#000000";
    }

    public String getSelectedStatoLabel() {
        return m_selectedComment != null ? m_selectedComment.getStatoTicketLabel() : "";
    }

    public boolean getSelectedHasAttachments() {
        return m_selectedComment != null && m_selectedComment.getAttachCount() > 0;
    }

    public String getNewCommentText()        { return m_newCommentText; }
    public void setNewCommentText(String t)  { this.m_newCommentText = t; }

    public String getNewCommentStato()       { return m_newCommentStato; }
    public void setNewCommentStato(String s) { this.m_newCommentStato = s; }

    public Trigger getDownloadTrigger() { return m_downloadTrigger; }
    public String  getDownloadUrl()     { return m_downloadUrl; }

    public boolean getHasPending()  { return !m_pendingAttachments.isEmpty(); }
    public int     getPendingCount() { return m_pendingAttachments.size(); }

    private String nn(String s) { return s != null ? s : ""; }
}