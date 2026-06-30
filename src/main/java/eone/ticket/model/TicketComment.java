package eone.ticket.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Modello per un commento associato a un ticket.
 * I commenti sono in ordine cronologico e agganciati al numero ticket SAP.
 * autore_tipo: 'CLIENTE' o 'ASSISTENZA'
 *
 * Stato del commento (statoTicket): 7 valori, distinti per chi li può impostare.
 * I colori seguono una scala "termica" basata sullo spettro visibile:
 * urgenza crescente verso il rosso (infrarosso), calma crescente verso il
 * violetto (ultravioletto). Gli stati di chiusura sono "freddi" (blu/viola),
 * gli stati di attesa neutra sono al centro (verde/giallo), i solleciti
 * sono "caldi" (arancione/rosso).
 */
public class TicketComment implements Serializable {

    private static final long serialVersionUID = 1L;

    // Valori costanti per autore_tipo
    public static final String TIPO_CLIENTE    = "CLIENTE";
    public static final String TIPO_ASSISTENZA = "ASSISTENZA";

    // Valori costanti per stato_ticket — settabili dal CLIENTE
    public static final String STATO_CLI_ATTESA_ASSISTENZA    = "CLI_ATTESA_ASSISTENZA";
    public static final String STATO_CLI_SOLLECITO_ASSISTENZA = "CLI_SOLLECITO_ASSISTENZA";
    public static final String STATO_CLI_RICHIESTA_CHIUSURA   = "CLI_RICHIESTA_CHIUSURA";
    public static final String STATO_CLI_RISOLTO              = "CLI_RISOLTO";

    // Valori costanti per stato_ticket — settabili dall'ASSISTENZA
    public static final String STATO_ASS_ATTESA_CLIENTE    = "ASS_ATTESA_CLIENTE";
    public static final String STATO_ASS_SOLLECITO_CLIENTE = "ASS_SOLLECITO_CLIENTE";
    public static final String STATO_ASS_CONCLUSO          = "ASS_CONCLUSO";

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
            case STATO_CLI_ATTESA_ASSISTENZA:    return "Attesa attività Assistenza";
            case STATO_CLI_SOLLECITO_ASSISTENZA: return "Sollecito attività Assistenza";
            case STATO_CLI_RICHIESTA_CHIUSURA:   return "Richiesta chiusura ticket";
            case STATO_CLI_RISOLTO:              return "Ticket risolto";
            case STATO_ASS_ATTESA_CLIENTE:       return "Attesa attività Cliente";
            case STATO_ASS_SOLLECITO_CLIENTE:    return "Sollecito attività Cliente";
            case STATO_ASS_CONCLUSO:             return "Ticket concluso";
            default:                             return statoTicket;
        }
    }

    /**
     * Colore associato allo stato, su scala "termica" dello spettro visibile.
     * Freddo (viola/blu) = stati conclusi/risolti.
     * Centrale (verde/giallo) = attese neutre.
     * Caldo (arancione/rosso) = solleciti, massima urgenza.
     */
    public String getStatoColor() {
        if (statoTicket == null) return "#CCCCCC";
        switch (statoTicket) {
            case STATO_ASS_CONCLUSO:             return "#7B68EE"; // viola
            case STATO_CLI_RISOLTO:              return "#4A90D9"; // blu
            case STATO_CLI_RICHIESTA_CHIUSURA:   return "#26A69A"; // ciano
            case STATO_ASS_ATTESA_CLIENTE:       return "#66BB6A"; // verde
            case STATO_CLI_ATTESA_ASSISTENZA:    return "#FBC02D"; // giallo
            case STATO_ASS_SOLLECITO_CLIENTE:    return "#FB8C00"; // arancione
            case STATO_CLI_SOLLECITO_ASSISTENZA: return "#E53935"; // rosso
            default:                             return "#CCCCCC";
        }
    }

    /** Testo contrastante (bianco/nero) leggibile sopra getStatoColor() */
    public String getStatoTextColor() {
        if (statoTicket == null) return "#000000";
        switch (statoTicket) {
            case STATO_CLI_ATTESA_ASSISTENZA: // giallo, troppo chiaro per testo bianco
                return "#3A3000";
            default:
                return "#FFFFFF";
        }
    }

    @Override
    public String toString() {
        return "TicketComment{id=" + id + ", tickt='" + tickt + "'"
             + ", autoreId='" + autoreId + "', stato='" + statoTicket + "'}";
    }
}