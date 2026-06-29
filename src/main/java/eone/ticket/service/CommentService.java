package eone.ticket.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import eone.ticket.config.DBConfig;
import eone.ticket.model.TicketAttachment;
import eone.ticket.model.TicketComment;

public class CommentService {

    public List<TicketComment> getComments(String tickt) throws SQLException {
        List<TicketComment> list = new ArrayList<>();
        String sql = "SELECT id, tickt, kunnr, autore_tipo, autore_id, testo, " +
                     "stato_ticket, created_at " +
                     "FROM ticket_comment WHERE tickt = ? ORDER BY created_at ASC";

        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, tickt);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TicketComment c = mapComment(rs);
                    c.setAttachments(getAttachmentsMeta(con, c.getId()));
                    list.add(c);
                }
            }
        }
        System.out.println("[CommentService] getComments(" + tickt + "): " + list.size() + " commenti");
        return list;
    }

    public long saveComment(TicketComment comment, List<TicketAttachment> attachments) throws SQLException {
        String sqlC = "INSERT INTO ticket_comment " +
                      "(tickt, kunnr, autore_tipo, autore_id, testo, stato_ticket, created_at) " +
                      "VALUES (?, ?, ?, ?, ?, ?, NOW()) RETURNING id";
        String sqlA = "INSERT INTO ticket_attachment " +
                      "(comment_id, filename, mime_type, file_size, file_data) " +
                      "VALUES (?, ?, ?, ?, ?)";

        try (Connection con = DBConfig.getConnection()) {
            con.setAutoCommit(false);
            try {
                long commentId;
                try (PreparedStatement ps = con.prepareStatement(sqlC)) {
                    ps.setString(1, comment.getTickt());
                    ps.setString(2, comment.getKunnr());
                    ps.setString(3, comment.getAutoreTipo());
                    ps.setString(4, comment.getAutoreId());
                    ps.setString(5, comment.getTesto());
                    ps.setString(6, comment.getStatoTicket());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        commentId = rs.getLong(1);
                    }
                }
                if (attachments != null && !attachments.isEmpty()) {
                    try (PreparedStatement ps = con.prepareStatement(sqlA)) {
                        for (TicketAttachment a : attachments) {
                            ps.setLong  (1, commentId);
                            ps.setString(2, a.getFilename());
                            ps.setString(3, a.getMimeType());
                            ps.setLong  (4, a.getFileSize());
                            ps.setBytes (5, a.getFileData());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                con.commit();
                System.out.println("[CommentService] Commento salvato id=" + commentId
                    + ", allegati=" + (attachments != null ? attachments.size() : 0));
                return commentId;
            } catch (SQLException e) {
                con.rollback();
                System.err.println("[CommentService] Rollback: " + e.getMessage());
                throw e;
            }
        }
    }

    public List<TicketAttachment> getAttachmentsMeta(Connection con, long commentId) throws SQLException {
        List<TicketAttachment> list = new ArrayList<>();
        String sql = "SELECT id, comment_id, filename, mime_type, file_size, created_at " +
                     "FROM ticket_attachment WHERE comment_id = ? ORDER BY id ASC";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, commentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TicketAttachment a = new TicketAttachment();
                    a.setId        (rs.getLong  ("id"));
                    a.setCommentId (rs.getLong  ("comment_id"));
                    a.setFilename  (rs.getString("filename"));
                    a.setMimeType  (rs.getString("mime_type"));
                    a.setFileSize  (rs.getLong  ("file_size"));
                    Timestamp ts = rs.getTimestamp("created_at");
                    if (ts != null) a.setCreatedAt(ts.toLocalDateTime());
                    list.add(a);
                }
            }
        }
        return list;
    }

    public TicketAttachment getAttachmentData(long attachmentId) throws SQLException {
        String sql = "SELECT id, comment_id, filename, mime_type, file_size, file_data " +
                     "FROM ticket_attachment WHERE id = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, attachmentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    TicketAttachment a = new TicketAttachment();
                    a.setId       (rs.getLong  ("id"));
                    a.setCommentId(rs.getLong  ("comment_id"));
                    a.setFilename (rs.getString("filename"));
                    a.setMimeType (rs.getString("mime_type"));
                    a.setFileSize (rs.getLong  ("file_size"));
                    a.setFileData (rs.getBytes ("file_data"));
                    return a;
                }
            }
        }
        return null;
    }

    private TicketComment mapComment(ResultSet rs) throws SQLException {
        TicketComment c = new TicketComment();
        c.setId         (rs.getLong  ("id"));
        c.setTickt      (rs.getString("tickt"));
        c.setKunnr      (rs.getString("kunnr"));
        c.setAutoreTipo (rs.getString("autore_tipo"));
        c.setAutoreId   (rs.getString("autore_id"));
        c.setTesto      (truncateText(rs.getString("testo"), 5000));
        c.setStatoTicket(rs.getString("stato_ticket"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) c.setCreatedAt(ts.toLocalDateTime());
        return c;
    }

    public static String detectMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".txt"))  return "text/plain";
        if (lower.endsWith(".zip"))  return "application/zip";
        return "application/octet-stream";
    }

    /**
     * Tronca il testo a maxLen caratteri per evitare il limite del protocollo CC.
     * 300kb in un attributo XML di una risposta CC causa troncamento silenzioso lato client.
     */
    private static String truncateText(String t, int maxLen) {
        if (t == null) return null;
        if (t.length() <= maxLen) return t;
        return t.substring(0, maxLen) + "\n\n[... testo troncato a " + maxLen + " caratteri su " + t.length() + " totali ...]";
    }
}
