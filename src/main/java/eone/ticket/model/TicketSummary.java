package eone.ticket.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Sintesi dei ticket per stato, calcolata in-memory dalla lista già caricata.
 * Passata da TicketListUI a MenuUI per la dashboard nel menu.
 */
public class TicketSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Voce della dashboard: stato + conteggio + colori */
    public static class StatoCount implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String rstat;
        public final String label;
        public final int    count;
        public final String color;
        public final String textColor;

        public StatoCount(String rstat, String label, int count, String color, String textColor) {
            this.rstat = rstat; this.label = label; this.count = count;
            this.color = color; this.textColor = textColor;
        }
        public String getRstat()     { return rstat; }
        public String getLabel()     { return label; }
        public int    getCount()     { return count; }
        public String getColor()     { return color; }
        public String getTextColor() { return textColor; }
    }

    private int totaleSAP   = 0;
    private int totaleDraft = 0;
    private int sostituitiCount = 0;
    private final List<StatoCount> voci = new ArrayList<>();

    public static TicketSummary build(List<Ticket> tickets, int draftCount) {
        return build(tickets, draftCount, 0);
    }

    /**
     * @param sostituitiCount quanti dei ticket/draft passati appartengono a
     *                        colleghi attualmente sostituiti dall'utente
     *                        (già inclusi in tickets/draftCount — non un
     *                        totale a parte, solo per l'indicazione a menu).
     */
    public static TicketSummary build(List<Ticket> tickets, int draftCount, int sostituitiCount) {
        TicketSummary s = new TicketSummary();
        s.totaleDraft = draftCount;
        s.sostituitiCount = sostituitiCount;
        if (tickets == null) return s;
        s.totaleSAP = tickets.size();

        // Conteggio per stato
        java.util.Map<String,Integer> conteggioMap = new java.util.LinkedHashMap<>();
        for (Ticket t : tickets) {
            String rstat = t.getRstat() != null ? t.getRstat() : "?";
            conteggioMap.merge(rstat, 1, Integer::sum);
        }

        // Costruisce le voci con colori da TicketStatoService
        eone.ticket.service.TicketStatoService statoService = new eone.ticket.service.TicketStatoService();
        conteggioMap.forEach((rstat, count) -> {
            TicketStatoInfo info = statoService.getStatoInfo(rstat);
            s.voci.add(new StatoCount(rstat, info.getDescrizioneCorta(), count,
                                      info.getColore(), info.getColoreTesto()));
        });

        // Aggiunge voce DRAFT se presenti
        if (draftCount > 0) {
            s.voci.add(new StatoCount("DRAFT", "DRAFT", draftCount, "#FF8F00", "#FFFFFF"));
        }

        return s;
    }

    public int getTotaleSAP()       { return totaleSAP; }
    public int getTotaleDraft()     { return totaleDraft; }
    public int getTotale()          { return totaleSAP + totaleDraft; }
    public int getSostituitiCount() { return sostituitiCount; }
    public List<StatoCount> getVoci() { return voci; }
}