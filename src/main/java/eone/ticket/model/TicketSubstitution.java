package eone.ticket.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Sostituzione temporanea tra due utenti di pari ruolo.
 * Un solo periodo alla volta per utente sostituito (vedi vincolo UNIQUE
 * su id_user_sostituito in ticket_substitution).
 */
public class TicketSubstitution implements Serializable {

    private static final long serialVersionUID = 1L;

    private long id;
    private String idUserSostituito;
    private String idUserSostituto;
    private LocalDate dataInizio;
    private LocalDate dataFine;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // =========================================================
    // BUSINESS LOGIC
    // =========================================================

    /** True se oggi cade nel periodo di sostituzione (estremi inclusi). */
    public boolean isAttivaOggi() {
        LocalDate oggi = LocalDate.now();
        return dataInizio != null && dataFine != null
            && !oggi.isBefore(dataInizio) && !oggi.isAfter(dataFine);
    }

    public boolean isFutura() {
        return dataInizio != null && dataInizio.isAfter(LocalDate.now());
    }

    public boolean isScaduta() {
        return dataFine != null && dataFine.isBefore(LocalDate.now());
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public String getDataInizioFormatted() {
        return dataInizio != null ? dataInizio.format(FMT) : "";
    }

    public String getDataFineFormatted() {
        return dataFine != null ? dataFine.format(FMT) : "";
    }

    // =========================================================
    // GETTERS / SETTERS
    // =========================================================

    public long getId()                          { return id; }
    public void setId(long v)                     { this.id = v; }

    public String getIdUserSostituito()           { return idUserSostituito; }
    public void setIdUserSostituito(String v)     { this.idUserSostituito = v; }

    public String getIdUserSostituto()            { return idUserSostituto; }
    public void setIdUserSostituto(String v)      { this.idUserSostituto = v; }

    public LocalDate getDataInizio()              { return dataInizio; }
    public void setDataInizio(LocalDate v)        { this.dataInizio = v; }

    public LocalDate getDataFine()                { return dataFine; }
    public void setDataFine(LocalDate v)          { this.dataFine = v; }

    public LocalDateTime getCreatedAt()           { return createdAt; }
    public void setCreatedAt(LocalDateTime v)     { this.createdAt = v; }

    public LocalDateTime getUpdatedAt()           { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v)     { this.updatedAt = v; }
}
