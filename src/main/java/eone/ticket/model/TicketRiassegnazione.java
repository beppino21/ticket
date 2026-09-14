package eone.ticket.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Richiesta AMS di riattribuzione di un ticket, indirizzata al DISPATCHER.
 * La modifica effettiva avviene sul backend SAP (fuori da quest'app) — qui
 * si traccia solo la richiesta e la sua auto-conclusione, che avviene
 * quando l'Amusr corrente del ticket risulta diverso da amusrRichiedente
 * (vedi TicketRiassegnazioneService.chiudiSeCambiate()).
 */
public class TicketRiassegnazione implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATO_APERTA   = "APERTA";
    public static final String STATO_CONCLUSA = "CONCLUSA";

    /** Valore convenzionale di nuovoAmusr quando il DISPATCHER rifiuta la richiesta (non cancellazione fisica). */
    public static final String NUOVO_AMUSR_RIFIUTATA = "DELETED";

    private long id;
    private String tickt;
    private String amusrRichiedente;
    private String testo;
    private String stato = STATO_APERTA;
    private String nuovoAmusr;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime concludedAt;

    // =========================================================
    // BUSINESS LOGIC
    // =========================================================

    public boolean isAperta()   { return STATO_APERTA.equalsIgnoreCase(stato); }
    public boolean isConclusa() { return STATO_CONCLUSA.equalsIgnoreCase(stato); }

    /** True se conclusa per rifiuto esplicito del DISPATCHER, non per riattribuzione SAP effettiva. */
    public boolean isRifiutata() { return NUOVO_AMUSR_RIFIUTATA.equalsIgnoreCase(nuovoAmusr); }

    /** Etichetta leggibile per la colonna "Nuovo AMS" — distingue il rifiuto dalla riattribuzione reale. */
    public String getNuovoAmusrLabel() {
        if (isRifiutata()) return "Rifiutata dal DISPATCHER";
        return getNuovoAmusr();
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public String getCreatedAtFormatted() {
        return createdAt != null ? createdAt.format(FMT) : "";
    }

    public String getConcludedAtFormatted() {
        return concludedAt != null ? concludedAt.format(FMT) : "";
    }

    // =========================================================
    // GETTERS / SETTERS
    // =========================================================

    public long getId()                         { return id; }
    public void setId(long v)                   { this.id = v; }

    public String getTickt()                    { return tickt; }
    public void setTickt(String v)              { this.tickt = v; }

    public String getAmusrRichiedente()         { return amusrRichiedente; }
    public void setAmusrRichiedente(String v)   { this.amusrRichiedente = v; }

    public String getTesto()                    { return testo; }
    public void setTesto(String v)              { this.testo = v; }

    public String getStato()                    { return stato; }
    public void setStato(String v)              { this.stato = v; }

    public String getNuovoAmusr()               { return nuovoAmusr != null ? nuovoAmusr : ""; }
    public void setNuovoAmusr(String v)         { this.nuovoAmusr = v; }

    public LocalDateTime getCreatedAt()         { return createdAt; }
    public void setCreatedAt(LocalDateTime v)   { this.createdAt = v; }

    public LocalDateTime getUpdatedAt()         { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v)   { this.updatedAt = v; }

    public LocalDateTime getConcludedAt()       { return concludedAt; }
    public void setConcludedAt(LocalDateTime v) { this.concludedAt = v; }
}