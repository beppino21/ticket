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
     *
     * Filtrato ANCHE per kunnr — reqid non è garantito univoco fra clienti
     * diversi (due clienti possono assegnare lo stesso reqid a un proprio
     * referente), quindi un filtro sul solo reqid_referente rischierebbe di
     * restituire tickt di un cliente diverso da quello del chiamante
     * (isolamento multi-tenant — bug reale riscontrato in produzione).
     */
    public List<String> getTicktsByReferente(String kunnr, String reqidReferente) throws SQLException {
        List<String> list = new ArrayList<>();
        if (reqidReferente == null || reqidReferente.trim().isEmpty()) return list;
        if (kunnr == null || kunnr.trim().isEmpty()) return list;
        String sql = "SELECT tickt FROM ticket_referente WHERE reqid_referente = ? AND kunnr = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, reqidReferente.trim());
            ps.setString(2, kunnr.trim());
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
     * @param kunnr il cliente (Kunnr) del ticket — SEMPRE noto al chiamante
     *        (dal draft appena creato o dal ticket già caricato in
     *        CommentUI). Necessario per poter poi filtrare in modo sicuro
     *        getTicktsByReferente() per kunnr, senza affidarsi al solo
     *        reqid (non univoco fra clienti — vedi lì).
     * @param notificaRichiedente se FALSE e reqidReferente è diverso dal
     *        richiedente del ticket, il richiedente non verrà più
     *        notificato via email su questo ticket (vedi CommentUI.inviaNotifiche).
     *        Irrilevante (il richiedente riceve comunque le notifiche, in
     *        quanto referente) se reqidReferente coincide col richiedente.
     */
    public void setReferente(String tickt, String kunnr, String reqidReferente, String updatedBy, boolean notificaRichiedente) throws SQLException {
        if (tickt == null || tickt.trim().isEmpty())
            throw new IllegalArgumentException("tickt obbligatorio");
        if (kunnr == null || kunnr.trim().isEmpty())
            throw new IllegalArgumentException("kunnr obbligatorio");
        if (reqidReferente == null || reqidReferente.trim().isEmpty())
            throw new IllegalArgumentException("reqidReferente obbligatorio");

        String sql = "INSERT INTO ticket_referente (tickt, kunnr, reqid_referente, notifica_richiedente, updated_by, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, NOW()) " +
                     "ON CONFLICT (tickt) DO UPDATE SET " +
                     "kunnr = EXCLUDED.kunnr, " +
                     "reqid_referente = EXCLUDED.reqid_referente, " +
                     "notifica_richiedente = EXCLUDED.notifica_richiedente, " +
                     "updated_by = EXCLUDED.updated_by, " +
                     "updated_at = NOW()";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, tickt.trim());
            ps.setString(2, kunnr.trim());
            ps.setString(3, reqidReferente.trim());
            ps.setBoolean(4, notificaRichiedente);
            ps.setString(5, updatedBy);
            ps.executeUpdate();
        }
        System.out.println("[TicketReferenteService] Referente di " + tickt + " (kunnr=" + kunnr + ") impostato a '" +
                            reqidReferente + "' da '" + updatedBy + "' (notificaRichiedente=" + notificaRichiedente + ")");
    }

    /** Sovraccarico retrocompatibile: notifica il richiedente per default (comportamento di sempre). */
    public void setReferente(String tickt, String kunnr, String reqidReferente, String updatedBy) throws SQLException {
        setReferente(tickt, kunnr, reqidReferente, updatedBy, true);
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