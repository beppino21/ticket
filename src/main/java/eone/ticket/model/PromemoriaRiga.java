package eone.ticket.model;

import java.io.Serializable;

/**
 * Una riga della mail di promemoria quotidiano (AMS o DISPATCHER) — un
 * ticket/DRAFT/richiesta di riassegnazione pendente, con l'evidenziazione
 * colore calcolata a monte da PromemoriaQuotidianoService.
 *
 * Colori (fissi, indipendenti dalla priorità del ticket):
 *   verde  (#C8E6C9) — ritardo fino a 3 giorni
 *   giallo (#FFF9C4) — ritardo da 4 a 9 giorni
 *   rosso  (#FFCDD2) — ritardo da 10 giorni in su
 */
public class PromemoriaRiga implements Serializable {

    private static final long serialVersionUID = 1L;

    private String  chiave;        // Tickt o DRAFT-{id}, per il link
    private String  descrizione;   // titolo ticket / testo draft / testo richiesta riassegnazione (troncato)
    private String  clienteLabel;  // Kunnr o nome cliente, se disponibile
    private long    giorni;        // giorni di ritardo/attesa
    private boolean sollecitoCliente; // true = il cliente ha inviato "Sollecito attività AMS" su questo ticket
    private String  link;          // deep link al ticket, null se non disponibile (es. DRAFT senza tickt SAP)

    public PromemoriaRiga(String chiave, String descrizione, String clienteLabel, long giorni,
                           boolean sollecitoCliente, String link) {
        this.chiave = chiave;
        this.descrizione = descrizione;
        this.clienteLabel = clienteLabel;
        this.giorni = giorni;
        this.sollecitoCliente = sollecitoCliente;
        this.link = link;
    }

    public String getChiave()             { return chiave; }
    public String getDescrizione()        { return descrizione; }
    public String getClienteLabel()       { return clienteLabel; }
    public long   getGiorni()             { return giorni; }
    public boolean isSollecitoCliente()   { return sollecitoCliente; }
    public String getLink()               { return link; }

    /** Colore di sfondo pastello in base al ritardo — scala fissa 3/9 giorni. */
    public String getColore() {
        if (giorni <= 3) return "#C8E6C9"; // verde pastello
        if (giorni <= 9) return "#FFF9C4"; // giallo pastello
        return "#FFCDD2";                  // rosso pastello
    }
}
