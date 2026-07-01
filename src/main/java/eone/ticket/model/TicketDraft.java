package eone.ticket.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Modello per un ticket DRAFT — ticket locale creato dal cliente via
 * WebApp, non ancora fuso in SAP dal DISPATCHER.
 *
 * Il campo tickt_sap viene valorizzato alla fusione.
 * I commenti/allegati sono in ticket_comment con tickt = "DRAFT-{id}".
 */
public class TicketDraft implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATO_DRAFT  = "DRAFT";
    public static final String STATO_MERGED = "MERGED";

    /** Prefisso usato come chiave in ticket_comment.tickt */
    public static String toDraftTickt(long id) { return "DRAFT-" + id; }

    private long          id;
    private String        kunnr;
    private String        reqid;
    private String        idUser;
    private String        titolo;
    private String        stato       = STATO_DRAFT;
    private String        ticktSap;   // null finché non fuso
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // =========================
    // GETTERS / SETTERS
    // =========================

    public long getId()                          { return id; }
    public void setId(long id)                   { this.id = id; }

    public String getKunnr()                     { return kunnr; }
    public void setKunnr(String kunnr)           { this.kunnr = kunnr; }

    public String getReqid()                     { return reqid; }
    public void setReqid(String reqid)           { this.reqid = reqid; }

    public String getIdUser()                    { return idUser; }
    public void setIdUser(String idUser)         { this.idUser = idUser; }

    public String getTitolo()                    { return titolo; }
    public void setTitolo(String titolo)         { this.titolo = titolo; }

    public String getStato()                     { return stato; }
    public void setStato(String stato)           { this.stato = stato; }

    public String getTicktSap()                  { return ticktSap; }
    public void setTicktSap(String ticktSap)     { this.ticktSap = ticktSap; }

    public LocalDateTime getCreatedAt()                   { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt)     { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt()                   { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt)     { this.updatedAt = updatedAt; }

    public boolean isDraft()  { return STATO_DRAFT.equals(stato); }
    public boolean isMerged() { return STATO_MERGED.equals(stato); }

    /** Chiave usata in ticket_comment.tickt per i commenti di questo draft */
    public String getTicktKey() { return toDraftTickt(id); }

    public String getCreatedAtFormatted() {
        if (createdAt == null) return "";
        return createdAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }
}