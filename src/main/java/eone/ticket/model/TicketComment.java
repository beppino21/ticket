package eone.ticket.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Modello per un commento associato a un ticket.
 * I commenti sono in ordine cronologico e agganciati al numero ticket SAP.
 * autore_tipo: 'CLIENTE' o 'ASSISTENZA'
 */
public class TicketComment implements Serializable {

    private static final long serialVersionUID = 1L;

    // Valori costanti per autore_tipo
    public static final String TIPO_CLIENTE    = "CLIENTE";
    public static final String TIPO_ASSISTENZA = "ASSISTENZA";

    // Valori costanti per stato_ticket
    public static final String STATO_WAIT_AMS    = "WAIT_AMS";
    public static final String STATO_WAIT_CLI    = "WAIT_CLI";
    public static final String STATO_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATO_RESOLVED    = "RESOLVED";
    public static final String STATO_CLOSED      = "CLOSED";

    private long          id;
    private String        tickt;
    private String        kunnr;
    private String        autoreTipo;
    private String        autoreId;
    private String        testo;
    private String        statoTicket;
    private LocalDateTime createdAt;
    private List<TicketAttachment> attachments = new ArrayList<>();

    // =========================
    // GETTERS / SETTERS
    // =========================

    public long getId()                      { return id; }
    public void setId(long id)               { this.id = id; }

    public String getTickt()                 { return tickt; }
    public void setTickt(String tickt)       { this.tickt = tickt; }

    public String getKunnr()                 { return kunnr; }
    public void setKunnr(String kunnr)       { this.kunnr = kunnr; }

    public String getAutoreTipo()                        { return autoreTipo; }
    public void setAutoreTipo(String autoreTipo)         { this.autoreTipo = autoreTipo; }

    public String getAutoreId()                          { return autoreId; }
    public void setAutoreId(String autoreId)             { this.autoreId = autoreId; }

    public String getTesto()                             { return testo; }
    public void setTesto(String testo)                   { this.testo = testo; }

    public String getStatoTicket()                       { return statoTicket; }
    public void setStatoTicket(String statoTicket)       { this.statoTicket = statoTicket; }

    public LocalDateTime getCreatedAt()                  { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt)    { this.createdAt = createdAt; }

    public List<TicketAttachment> getAttachments()                       { return attachments; }
    public void setAttachments(List<TicketAttachment> attachments)       { this.attachments = attachments; }

    // =========================
    // UTILITY
    // =========================

    public boolean isFromCliente() {
        return TIPO_CLIENTE.equals(autoreTipo);
    }

    public boolean isFromAssistenza() {
        return TIPO_ASSISTENZA.equals(autoreTipo);
    }

    public int getAttachCount() {
        return attachments != null ? attachments.size() : 0;
    }

    /** Data/ora formattata per visualizzazione: dd/MM/yyyy HH:mm */
    public String getCreatedAtFormatted() {
        if (createdAt == null) return "";
        return String.format("%02d/%02d/%04d %02d:%02d",
            createdAt.getDayOfMonth(),
            createdAt.getMonthValue(),
            createdAt.getYear(),
            createdAt.getHour(),
            createdAt.getMinute());
    }

    /** Etichetta stato ticket leggibile */
    public String getStatoTicketLabel() {
        if (statoTicket == null) return "";
        switch (statoTicket) {
            case STATO_WAIT_AMS:    return "Attesa Servizio Assistenza";
            case STATO_WAIT_CLI:    return "Attesa risposta Cliente";
            case STATO_IN_PROGRESS: return "In lavorazione";
            case STATO_RESOLVED:    return "Risolto";
            case STATO_CLOSED:      return "Chiuso";
            default:                return statoTicket;
        }
    }

    @Override
    public String toString() {
        return "TicketComment{id=" + id + ", tickt='" + tickt + "'"
             + ", autoreId='" + autoreId + "', stato='" + statoTicket + "'}";
    }
}
