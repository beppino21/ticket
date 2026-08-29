package eone.ticket.service;

/**
 * Limiti dimensionali per gli allegati di ticket/commenti, applicati
 * lato server in aggiunta al limite client-side "maxfilesize" già
 * presente sui componenti t:fileuploadbutton (CommentDialog.xml,
 * NewTicket.xml).
 *
 * Il controllo client-side da solo non basta:
 *  - non copre mai la somma di più allegati caricati insieme sullo
 *    stesso commento/ticket (un utente può selezionare più file, ognuno
 *    sotto il limite per-file, il cui totale supera comunque il
 *    maxPostSize/maxSwallowSize del Connector Tomcat, vedi server.xml);
 *  - non protegge da eventuali scostamenti tra client e server (versioni
 *    cache del browser, bypass, ecc.).
 *
 * NOTA dimensionale: il file viaggia dal client come stringa hex
 * (BaseActionEventUpload.getHexByteString), quindi il peso reale sul
 * wire è ~2x la dimensione del file originale. I limiti sotto sono
 * espressi in byte "reali" del file (post-decodifica); il margine tra
 * questi valori e maxPostSize/maxSwallowSize di Tomcat deve sempre
 * tenerne conto.
 */
public final class AttachmentLimits {

    private AttachmentLimits() { }

    /** Limite per singolo file, in byte. Allineato al "maxfilesize"
     *  impostato sui componenti t:fileuploadbutton (attualmente
     *  5000000, mostrato a video come "max 5 MB per file"). Se cambi
     *  questo valore, aggiorna anche maxfilesize in CommentDialog.xml
     *  e NewTicket.xml e il relativo testo del t:label di hint. */
    public static final long MAX_SINGLE_FILE_BYTES = 5_000_000L;

    /** Limite cumulativo per tutti gli allegati di un singolo commento
     *  o ticket, in byte. */
    public static final long MAX_TOTAL_BYTES = 15_000_000L;

    /** Formatta una dimensione in byte come "x,x MB" per i messaggi utente. */
    public static String formatMB(long bytes) {
        return String.format("%.1f MB", bytes / 1_000_000.0);
    }
}