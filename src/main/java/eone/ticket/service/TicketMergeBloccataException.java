package eone.ticket.service;

/**
 * Lanciata da {@link TicketDraftService#checkMergeWarnings} quando la fusione
 * DRAFT -> ticket SAP non è consentita per una regola di integrità (oggi:
 * cliente/Kunnr del ticket SAP diverso da quello del DRAFT).
 *
 * Estende {@link TicketSapNotFoundException} apposta: il DISPATCHER (DispatcherUI)
 * già tratta quell'eccezione come errore BLOCCANTE — nessun "conferma e procedi" —
 * mostrando il messaggio in barra di stato, quindi non serve alcuna modifica
 * alla schermata di smistamento.
 */
public class TicketMergeBloccataException extends TicketSapNotFoundException {

    private static final long serialVersionUID = 1L;

    public TicketMergeBloccataException(String message) {
        super(message);
    }
}
