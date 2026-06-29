package eone.ticket.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Modello per un allegato associato a un commento ticket.
 * Il campo fileData (BYTEA) viene caricato SOLO su richiesta esplicita (download),
 * non nella lista allegati, per non caricare tutto in memoria.
 */
public class TicketAttachment implements Serializable {

    private static final long serialVersionUID = 1L;

    private long          id;
    private long          commentId;
    private String        filename;
    private String        mimeType;
    private long          fileSize;
    private byte[]        fileData;   // null nella lista, valorizzato solo per download
    private LocalDateTime createdAt;

    // =========================
    // GETTERS / SETTERS
    // =========================

    public long getId()                  { return id; }
    public void setId(long id)           { this.id = id; }

    public long getCommentId()                   { return commentId; }
    public void setCommentId(long commentId)     { this.commentId = commentId; }

    public String getFilename()                  { return filename; }
    public void setFilename(String filename)     { this.filename = filename; }

    public String getMimeType()                  { return mimeType; }
    public void setMimeType(String mimeType)     { this.mimeType = mimeType; }

    public long getFileSize()                    { return fileSize; }
    public void setFileSize(long fileSize)       { this.fileSize = fileSize; }

    public byte[] getFileData()                  { return fileData; }
    public void setFileData(byte[] fileData)     { this.fileData = fileData; }

    public LocalDateTime getCreatedAt()                    { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt)      { this.createdAt = createdAt; }

    // =========================
    // UTILITY
    // =========================

    /** Dimensione formattata per visualizzazione (es. "1.2 MB") */
    public String getFileSizeFormatted() {
        if (fileSize <= 0)           return "0 B";
        if (fileSize < 1024)         return fileSize + " B";
        if (fileSize < 1024 * 1024)  return String.format("%.1f KB", fileSize / 1024.0);
        return String.format("%.1f MB", fileSize / (1024.0 * 1024));
    }

    @Override
    public String toString() {
        return "TicketAttachment{id=" + id + ", commentId=" + commentId
             + ", filename='" + filename + "', size=" + fileSize + "}";
    }
}
