package eone.ticket.model;

import java.io.Serializable;

/**
 * Informazioni di transcodifica per uno stato ticket SAP (rstat),
 * lette dalla tabella ticket_stato_rstat.
 * Distinto da TicketComment (che gestisce gli stati del commento, non del ticket).
 */
public class TicketStatoInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String rstat;
    private String descrizione;
    private String descrizioneCorta;
    private int    ordine;
    private String colore;
    private String coloreTesto;

    public String getRstat()                       { return rstat; }
    public void setRstat(String rstat)              { this.rstat = rstat; }

    public String getDescrizione()                  { return descrizione; }
    public void setDescrizione(String descrizione)  { this.descrizione = descrizione; }

    public String getDescrizioneCorta()                    { return descrizioneCorta; }
    public void setDescrizioneCorta(String descrizioneCorta) { this.descrizioneCorta = descrizioneCorta; }

    public int getOrdine()                          { return ordine; }
    public void setOrdine(int ordine)                { this.ordine = ordine; }

    public String getColore()                       { return colore; }
    public void setColore(String colore)             { this.colore = colore; }

    public String getColoreTesto()                   { return coloreTesto; }
    public void setColoreTesto(String coloreTesto)   { this.coloreTesto = coloreTesto; }

    /** Campo combinato per visualizzazione in lista: "WIP - IN PROGRESS" */
    public String getCodiceDescrizione() {
        return rstat + " - " + (descrizione != null ? descrizione : "");
    }
}