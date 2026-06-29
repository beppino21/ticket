package eone.ticket.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import eone.ticket.config.DBConfig;
import eone.ticket.model.Ticket;

/**
 * Service di arricchimento: aggiunge dati PostgreSQL ai Ticket estratti da SAP.
 *
 * Principio: una sola query aggregata per tutta la lista ticket —
 * no N+1, no query per ogni ticket.
 *
 * Campi popolati su Ticket:
 *   encNumCommenti   — numero totale commenti
 *   encNumAllegati   — numero totale allegati (su tutti i commenti del ticket)
 *   encUltimoStato   — stato_ticket dell'ultimo commento
 *   encUltimaData    — data/ora ultimo commento (formattata)
 *   encUltimoTesto   — testo ultimo commento (troncato a 80 caratteri)
 *
 * Progettato per evolvere: aggiungere qui la transcodifica kunnr → ragione sociale
 * e reqid → nome richiedente quando la tabella ticket_requester sarà popolata.
 */
public class EnrichmentService {

    private static final int TESTO_MAX_LEN = 80;

    /**
     * Arricchisce una lista di ticket con i dati aggregati da PostgreSQL.
     * Se la lista è vuota o il DB non è raggiungibile, i ticket rimangono
     * con i valori di default (0 commenti, stringhe vuote) senza eccezione.
     *
     * @param tickets lista ticket estratti da SAP — modificata in-place
     */
    public void enrichTickets(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) return;

        // Costruisce la lista di tickt per il WHERE IN
        List<String> ticktList = tickets.stream()
            .map(Ticket::getTickt)
            .filter(t -> t != null && !t.isEmpty())
            .collect(Collectors.toList());

        if (ticktList.isEmpty()) return;

        // Mappa tickt → ticket per popolamento rapido
        Map<String, Ticket> ticketMap = new HashMap<>();
        for (Ticket t : tickets) {
            if (t.getTickt() != null) ticketMap.put(t.getTickt(), t);
        }

        enrichWithCommentStats(ticketMap, ticktList);
        // Punto di estensione futuro:
        // enrichWithCustomerNames(ticketMap, ticktList);
        // enrichWithRequesterNames(ticketMap, ticktList);
    }

    // =========================
    // STATISTICHE COMMENTI
    // =========================

    private void enrichWithCommentStats(Map<String, Ticket> ticketMap, List<String> ticktList) {

        // Query 1: conteggi aggregati (commenti e allegati per ticket)
        String sqlCounts = buildInQuery(
            "SELECT c.tickt, " +
            "       COUNT(DISTINCT c.id)  AS num_commenti, " +
            "       COUNT(a.id)           AS num_allegati " +
            "FROM ticket_comment c " +
            "LEFT JOIN ticket_attachment a ON a.comment_id = c.id " +
            "WHERE c.tickt IN (",
            ticktList.size()
        );

        // Query 2: ultimo commento per ticket (stato, data, testo)
        String sqlLast = buildInQuery(
            "SELECT DISTINCT ON (tickt) " +
            "       tickt, stato_ticket, created_at, testo " +
            "FROM ticket_comment " +
            "WHERE tickt IN (",
            ticktList.size()
        ) + " ORDER BY tickt, created_at DESC";

        try (Connection con = DBConfig.getConnection()) {

            // Esegui query conteggi
            try (PreparedStatement ps = con.prepareStatement(sqlCounts)) {
                for (int i = 0; i < ticktList.size(); i++) {
                    ps.setString(i + 1, ticktList.get(i));
                }
                // Ultimo parametro GROUP BY implicito nella query
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tickt = rs.getString("tickt");
                        Ticket t = ticketMap.get(tickt);
                        if (t != null) {
                            t.setEncNumCommenti((int) rs.getLong("num_commenti"));
                            t.setEncNumAllegati((int) rs.getLong("num_allegati"));
                        }
                    }
                }
            }

            // Esegui query ultimo commento
            try (PreparedStatement ps = con.prepareStatement(sqlLast)) {
                for (int i = 0; i < ticktList.size(); i++) {
                    ps.setString(i + 1, ticktList.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tickt = rs.getString("tickt");
                        Ticket t = ticketMap.get(tickt);
                        if (t != null) {
                            t.setEncUltimoStato(translateStato(rs.getString("stato_ticket")));
                            Timestamp ts = rs.getTimestamp("created_at");
                            if (ts != null) {
                                t.setEncUltimaData(formatTimestamp(ts.toLocalDateTime()));
                            }
                            String testo = rs.getString("testo");
                            t.setEncUltimoTesto(truncate(testo, TESTO_MAX_LEN));
                        }
                    }
                }
            }

        } catch (Exception e) {
            // Non bloccare la UI se PostgreSQL non è raggiungibile
            System.err.println("[EnrichmentService] Errore arricchimento ticket: " + e.getMessage());
        }
    }

    // =========================
    // UTILITY
    // =========================

    /** Costruisce la parte WHERE IN con N placeholder */
    private String buildInQuery(String prefix, int n) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        sb.append(") GROUP BY tickt");
        return sb.toString();
    }

    /** Traduce il codice stato in etichetta leggibile */
    private String translateStato(String stato) {
        if (stato == null) return "";
        switch (stato) {
            case "WAIT_AMS":    return "Attesa Assistenza";
            case "WAIT_CLI":    return "Attesa Cliente";
            case "IN_PROGRESS": return "In lavorazione";
            case "RESOLVED":    return "Risolto";
            case "CLOSED":      return "Chiuso";
            default:            return stato;
        }
    }

    /** Formatta LocalDateTime in dd/MM/yyyy HH:mm */
    private String formatTimestamp(LocalDateTime dt) {
        if (dt == null) return "";
        return String.format("%02d/%02d/%04d %02d:%02d",
            dt.getDayOfMonth(), dt.getMonthValue(), dt.getYear(),
            dt.getHour(), dt.getMinute());
    }

    /** Tronca il testo a maxLen caratteri aggiungendo "..." */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", "").trim();
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}