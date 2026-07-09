package eone.ticket.service;

/**
 * Lanciata quando il numero di ticket SAP inserito per la fusione di un
 * DRAFT non esiste (o non è leggibile) su SAP.
 *
 * A differenza degli altri controlli di checkMergeWarnings() (commenti già
 * presenti, richiedente diverso, data incoerente) — che sono giudizi di
 * merito che il DISPATCHER può scegliere di ignorare — questo caso è un
 * blocco vero e proprio: non esiste alcun ticket con cui fondere il DRAFT.
 * Per questo va gestito come errore bloccante e non come warning
 * sorpassabile con "conferma".
 */
public class TicketSapNotFoundException extends Exception {

    private static final long serialVersionUID = 1L;

    public TicketSapNotFoundException(String message) {
        super(message);
    }

    public TicketSapNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
