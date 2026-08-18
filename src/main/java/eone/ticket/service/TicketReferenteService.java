package eone.ticket.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import eone.ticket.config.DBConfig;

/**
 * Service per la gestione del referente_cli assegnato a un ticket.
 *
 * Tabella ticket_referente, chiave "tickt" con la stessa convenzione di
 * ticket_comment.tickt: DRAFT-{id} durante la vita del draft, numero
 * ticket SAP dopo la fusione (migrato in TicketDraftService.mergeDraft()).
 *
 * Il referente è obbligatorio alla creazione del DRAFT (impostato da
 * NewTicketUI) ed è riassegnabile in seguito sia dal richiedente del
 * ticket sia dal referente attualmente assegnato (vedi canModificareReferente).
 */
public class TicketReferenteService {

    /**
     * Elenco dei tickt (DRAFT-{id} o numeri SAP) dove reqidReferente è
     * assegnato come referente — usato da TicketListUI per allargare la
     * vista "i miei ticket" ai ticket dove l'utente è referente ma non
     * richiedente.
     */
    public List<String> getTicktsByReferente(String reqidReferente) throws SQLException {
        List<String> list = new ArrayList<>();
        if (reqidReferente == null || reqidReferente.trim().isEmpty()) return list;
        String sql = "SELECT tickt FROM ticket_referente WHERE reqid_referente = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, reqidReferente.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getString("tickt"));
            }
        }
        return list;
    }

    /**
     * Lookup massivo: per una lista di tickt (DRAFT o SAP), restituisce la
     * mappa tickt -> reqid_referente. Usato da TicketListUI per arricchire
     * l'intera griglia con una sola query invece di una per riga.
     */
    public java.util.Map<String, String> getReferentiBulk(java.util.List<String> tickts) throws SQLException {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (tickts == null || tickts.isEmpty()) return result;

        StringBuilder sb = new StringBuilder("SELECT tickt, reqid_referente FROM ticket_referente WHERE tickt IN (");
        for (int i = 0; i < tickts.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("?");
        }
        sb.append(")");

        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sb.toString())) {
            for (int i = 0; i < tickts.size(); i++) ps.setString(i + 1, tickts.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getString("tickt"), rs.getString("reqid_referente"));
            }
        }
        return result;
    }

    /** Referente attualmente assegnato al ticket, o null se non impostato. */
    public String getReferente(String tickt) throws SQLException {
        if (tickt == null || tickt.trim().isEmpty()) return null;
        String sql = "SELECT reqid_referente FROM ticket_referente WHERE tickt = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, tickt.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("reqid_referente") : null;
            }
        }
    }

    /**
     * True se il richiedente vuole essere notificato via email quando il
     * referente del ticket è diverso da lui (default TRUE — comportamento
     * di sempre, nessuna sorpresa per chi non tocca questa opzione).
     * Ritorna TRUE anche se il ticket non ha ancora un referente impostato
     * (nessuna riga trovata), per non bloccare per errore le notifiche.
     */
    public boolean getNotificaRichiedente(String tickt) throws SQLException {
        if (tickt == null || tickt.trim().isEmpty()) return true;
        String sql = "SELECT notifica_richiedente FROM ticket_referente WHERE tickt = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, tickt.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean("notifica_richiedente") : true;
            }
        }
    }

    /**
     * Imposta/riassegna il referente di un ticket (upsert). Usato sia alla
     * creazione del DRAFT sia per una successiva riassegnazione.
     * @param notificaRichiedente se FALSE e reqidReferente è diverso dal
     *        richiedente del ticket, il richiedente non verrà più
     *        notificato via email su questo ticket (vedi CommentUI.inviaNotifiche).
     *        Irrilevante (il richiedente riceve comunque le notifiche, in
     *        quanto referente) se reqidReferente coincide col richiedente.
     */
    public void setReferente(String tickt, String reqidReferente, String updatedBy, boolean notificaRichiedente) throws SQLException {
        if (tickt == null || tickt.trim().isEmpty())
            throw new IllegalArgumentException("tickt obbligatorio");
        if (reqidReferente == null || reqidReferente.trim().isEmpty())
            throw new IllegalArgumentException("reqidReferente obbligatorio");

        String sql = "INSERT INTO ticket_referente (tickt, reqid_referente, notifica_richiedente, updated_by, updated_at) " +
                     "VALUES (?, ?, ?, ?, NOW()) " +
                     "ON CONFLICT (tickt) DO UPDATE SET " +
                     "reqid_referente = EXCLUDED.reqid_referente, " +
                     "notifica_richiedente = EXCLUDED.notifica_richiedente, " +
                     "updated_by = EXCLUDED.updated_by, " +
                     "updated_at = NOW()";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, tickt.trim());
            ps.setString(2, reqidReferente.trim());
            ps.setBoolean(3, notificaRichiedente);
            ps.setString(4, updatedBy);
            ps.executeUpdate();
        }
        System.out.println("[TicketReferenteService] Referente di " + tickt + " impostato a '" +
                            reqidReferente + "' da '" + updatedBy + "' (notificaRichiedente=" + notificaRichiedente + ")");
    }

    /** Sovraccarico retrocompatibile: notifica il richiedente per default (comportamento di sempre). */
    public void setReferente(String tickt, String reqidReferente, String updatedBy) throws SQLException {
        setReferente(tickt, reqidReferente, updatedBy, true);
    }

    /**
     * True se reqidUtente può modificare il referente del ticket: deve
     * essere il richiedente del ticket oppure il referente attualmente
     * assegnato. Il chiamante passa il reqid del richiedente del ticket
     * (già noto dal Ticket/TicketDraft) — evita una query aggiuntiva qui.
     */
    public boolean canModificareReferente(String tickt, String reqidRichiedenteDelTicket, String reqidUtente) throws SQLException {
        if (reqidUtente == null || reqidUtente.trim().isEmpty()) return false;
        if (reqidUtente.equalsIgnoreCase(reqidRichiedenteDelTicket)) return true;
        String referenteAttuale = getReferente(tickt);
        return referenteAttuale != null && referenteAttuale.equalsIgnoreCase(reqidUtente);
    }
}