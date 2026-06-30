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
import eone.ticket.model.TicketComment;
import eone.ticket.model.TicketStatoInfo;

public class EnrichmentService {

    private static final int TESTO_MAX_LEN = 80;
    private final TicketStatoService statoService = new TicketStatoService();

    public void enrichTickets(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) return;

        List<String> ticktList = tickets.stream()
            .map(Ticket::getTickt)
            .filter(t -> t != null && !t.isEmpty())
            .collect(Collectors.toList());
        if (ticktList.isEmpty()) return;

        Map<String, Ticket> ticketMap = new HashMap<>();
        for (Ticket t : tickets) {
            if (t.getTickt() != null) ticketMap.put(t.getTickt(), t);
        }

        enrichWithCommentStats(ticketMap, ticktList);
        enrichWithRequesterNames(tickets);
        enrichWithRstatTranscoding(tickets);
    }

    // =========================
    // STATISTICHE COMMENTI
    // =========================

    private void enrichWithCommentStats(Map<String, Ticket> ticketMap, List<String> ticktList) {

        // Query 1: conteggi (GROUP BY tickt — corretta)
        String sqlCounts = buildGroupByQuery(
            "SELECT c.tickt, " +
            "       COUNT(DISTINCT c.id) AS num_commenti, " +
            "       COUNT(a.id)          AS num_allegati " +
            "FROM ticket_comment c " +
            "LEFT JOIN ticket_attachment a ON a.comment_id = c.id " +
            "WHERE c.tickt IN (",
            ticktList.size()
        );

        // Query 2: ultimo commento per ticket (DISTINCT ON — senza GROUP BY)
        String sqlLast = buildInClause(
            "SELECT DISTINCT ON (tickt) " +
            "       tickt, stato_ticket, created_at, testo " +
            "FROM ticket_comment " +
            "WHERE tickt IN (",
            ticktList.size()
        ) + " ORDER BY tickt, created_at DESC";

        try (Connection con = DBConfig.getConnection()) {

            // Esegue conteggi
            try (PreparedStatement ps = con.prepareStatement(sqlCounts)) {
                for (int i = 0; i < ticktList.size(); i++) ps.setString(i + 1, ticktList.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Ticket t = ticketMap.get(rs.getString("tickt"));
                        if (t != null) {
                            t.setEncNumCommenti((int) rs.getLong("num_commenti"));
                            t.setEncNumAllegati((int) rs.getLong("num_allegati"));
                        }
                    }
                }
            }

            // Esegue ultimo commento
            try (PreparedStatement ps = con.prepareStatement(sqlLast)) {
                for (int i = 0; i < ticktList.size(); i++) ps.setString(i + 1, ticktList.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Ticket t = ticketMap.get(rs.getString("tickt"));
                        if (t != null) {
                            String statoCode = rs.getString("stato_ticket");
                            // Riusa TicketComment per etichetta e colori — coerenza
                            // garantita con il dettaglio commenti, nessuna logica duplicata.
                            TicketComment tmp = new TicketComment();
                            tmp.setStatoTicket(statoCode);
                            t.setEncUltimoStato(statoCode);
                            t.setEncUltimoStatoLabel(tmp.getStatoTicketLabel());
                            t.setEncUltimoStatoColor(tmp.getStatoColor());
                            t.setEncUltimoStatoTextColor(tmp.getStatoTextColor());
                            Timestamp ts = rs.getTimestamp("created_at");
                            if (ts != null) t.setEncUltimaData(formatTimestamp(ts.toLocalDateTime()));
                            t.setEncUltimoTesto(truncate(rs.getString("testo"), TESTO_MAX_LEN));
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[EnrichmentService] Errore arricchimento commenti: " + e.getMessage());
        }
    }

    // =========================
    // NOMI RICHIEDENTI
    // =========================

    /**
     * Arricchisce ogni ticket con il nome del richiedente da ticket_user.
     * Nota: reqid non è univoco — la ricerca è approssimativa (primo match).
     * Per un risultato preciso servirebbe kunnr+reqid, ma nella lista ticket
     * il nome è comunque indicativo.
     */
    private void enrichWithRequesterNames(List<Ticket> tickets) {
        List<String> reqids = tickets.stream()
            .map(Ticket::getReqid)
            .filter(r -> r != null && !r.trim().isEmpty())
            .distinct()
            .collect(Collectors.toList());

        if (reqids.isEmpty()) return;

        // Cerca in ticket_user per reqid — restituisce il primo nome trovato per quel reqid
        String sql = buildInClause(
            "SELECT DISTINCT ON (reqid) reqid, nome FROM ticket_user WHERE reqid IN (",
            reqids.size()
        ) + " ORDER BY reqid, id_user";

        Map<String, String> reqidToNome = new HashMap<>();

        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            for (int i = 0; i < reqids.size(); i++) ps.setString(i + 1, reqids.get(i));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String reqid = rs.getString("reqid");
                    String nome  = rs.getString("nome");
                    if (nome != null && !nome.trim().isEmpty()) {
                        reqidToNome.put(reqid, nome.trim());
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[EnrichmentService] Errore arricchimento richiedenti: " + e.getMessage());
            return;
        }

        for (Ticket t : tickets) {
            String reqid = t.getReqid();
            if (reqid == null || reqid.trim().isEmpty()) continue;
            String nome = reqidToNome.get(reqid);
            t.setEncNomeRichiedente(nome != null ? reqid + " \u2014 " + nome : reqid);
        }

        System.out.println("[EnrichmentService] Nomi richiedenti risolti: " +
                           reqidToNome.size() + "/" + reqids.size());
    }

    // =========================
    // TRANSCODIFICA STATO TICKET SAP (rstat)
    // =========================

    /**
     * Arricchisce ogni ticket con la descrizione e il colore dello stato SAP (rstat),
     * usando la cache statica di TicketStatoService (13 valori, letti una volta sola).
     */
    private void enrichWithRstatTranscoding(List<Ticket> tickets) {
        for (Ticket t : tickets) {
            TicketStatoInfo info = statoService.getStatoInfo(t.getRstat());
            t.setEncRstatLabel(info.getCodiceDescrizione());
            t.setEncRstatColor(info.getColore());
            t.setEncRstatTextColor(info.getColoreTesto());
        }
    }

    // =========================
    // UTILITY
    // =========================

    /** Costruisce prefix + (?,?,...) + ") GROUP BY tickt" */
    private String buildGroupByQuery(String prefix, int n) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        sb.append(") GROUP BY tickt");
        return sb.toString();
    }

    /** Costruisce prefix + (?,?,...) + ")" — senza GROUP BY */
    private String buildInClause(String prefix, int n) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        sb.append(")");
        return sb.toString();
    }

    private String formatTimestamp(LocalDateTime dt) {
        if (dt == null) return "";
        return String.format("%02d/%02d/%04d %02d:%02d",
            dt.getDayOfMonth(), dt.getMonthValue(), dt.getYear(),
            dt.getHour(), dt.getMinute());
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", "").trim();
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}