package eone.ticket.service;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import eone.ticket.config.DBConfig;
import eone.ticket.model.RequesterInfo;
import eone.ticket.model.TicketSubstitution;

/**
 * Service per le sostituzioni temporanee tra utenti di pari ruolo.
 * Versione semplice: un solo periodo configurabile alla volta per utente
 * sostituito (upsert su id_user_sostituito) — nessuno storico per ora.
 */
public class SubstitutionService {

    private static final String COLS =
        "id, id_user_sostituito, id_user_sostituto, data_inizio, data_fine, created_at, updated_at";

    // =========================
    // LETTURA / SCRITTURA — schermata di auto-sostituzione
    // =========================

    /** La sostituzione configurata dall'utente per sé stesso (come sostituito), se esiste. */
    public TicketSubstitution getByUser(String idUserSostituito) throws SQLException {
        String sql = "SELECT " + COLS + " FROM ticket_substitution WHERE id_user_sostituito = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUserSostituito);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /**
     * Salva (crea o aggiorna) la sostituzione dell'utente — upsert su
     * id_user_sostituito, coerente col vincolo UNIQUE della tabella.
     */
    public void save(String idUserSostituito, String idUserSostituto,
                      LocalDate dataInizio, LocalDate dataFine) throws SQLException {
        String sql = "INSERT INTO ticket_substitution " +
                     "(id_user_sostituito, id_user_sostituto, data_inizio, data_fine, updated_at) " +
                     "VALUES (?, ?, ?, ?, NOW()) " +
                     "ON CONFLICT (id_user_sostituito) DO UPDATE SET " +
                     "id_user_sostituto = EXCLUDED.id_user_sostituto, " +
                     "data_inizio = EXCLUDED.data_inizio, " +
                     "data_fine = EXCLUDED.data_fine, " +
                     "updated_at = NOW()";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUserSostituito);
            ps.setString(2, idUserSostituto);
            ps.setDate  (3, Date.valueOf(dataInizio));
            ps.setDate  (4, Date.valueOf(dataFine));
            ps.executeUpdate();
        }
    }

    /** Rimuove la sostituzione configurata dall'utente (come sostituito), se esiste. */
    public void delete(String idUserSostituito) throws SQLException {
        String sql = "DELETE FROM ticket_substitution WHERE id_user_sostituito = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUserSostituito);
            ps.executeUpdate();
        }
    }

    /**
     * Utenti con lo stesso ruolo dell'utente indicato, attivi, esclusi sé
     * stesso — per il combobox di scelta del sostituto.
     *
     * @param kunnrHint se valorizzato (CLIENTE), restringe la ricerca allo
     *                   stesso Kunnr — un cliente non deve poter scegliere
     *                   come sostituto un utente di un'altra azienda. Per
     *                   AMS/ADMIN (nessun Kunnr associato) passare null:
     *                   nessun filtro, comportamento invariato.
     */
    public List<RequesterInfo> getUtentiStessoRuolo(String idUserEscluso, String ruolo, String kunnrHint) throws SQLException {
        List<RequesterInfo> list = new ArrayList<>();
        boolean filtraKunnr = kunnrHint != null && !kunnrHint.trim().isEmpty();
        String sql = "SELECT id_user, kunnr, reqid, nome, email, ruolo, vede_tutti, attivo, " +
                     "password_impostata_il, password_scadenza_giorni, password_non_scade " +
                     "FROM ticket_user " +
                     "WHERE ruolo = ? AND attivo = TRUE AND id_user <> ? " +
                     (filtraKunnr ? "AND LPAD(kunnr, 10, '0') = LPAD(?, 10, '0') " : "") +
                     "ORDER BY nome";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ruolo);
            ps.setString(2, idUserEscluso);
            if (filtraKunnr) ps.setString(3, kunnrHint.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RequesterInfo info = new RequesterInfo();
                    info.setId_user(rs.getString("id_user"));
                    info.setKunnr(rs.getString("kunnr"));
                    info.setReqid(rs.getString("reqid"));
                    info.setNome(rs.getString("nome"));
                    info.setEmail(rs.getString("email"));
                    info.setRuolo(rs.getString("ruolo"));
                    info.setVedeTutti(rs.getBoolean("vede_tutti"));
                    list.add(info);
                }
            }
        }
        return list;
    }

    /**
     * Id dell'utente che sta attualmente sostituendo l'utente indicato
     * (oggi compreso nel periodo, estremi inclusi), se presente — usato
     * per inoltrare anche al sostituto le notifiche email dirette al
     * sostituito (es. commenti CLIENTE↔AMS).
     */
    public String getSostitutoAttivo(String idUserSostituito) throws SQLException {
        String sql = "SELECT id_user_sostituto FROM ticket_substitution " +
                     "WHERE id_user_sostituito = ? AND data_inizio <= CURRENT_DATE AND data_fine >= CURRENT_DATE";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUserSostituito);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("id_user_sostituto") : null;
            }
        }
    }

    // =========================
    // LOOKUP — usato da TicketListUI per estendere la visibilità
    // =========================

    /**
     * Id degli utenti attualmente sostituiti (oggi compreso nel periodo,
     * estremi inclusi) dall'utente indicato come sostituto.
     */
    public List<String> getSostituitiAttivi(String idUserSostituto) throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT id_user_sostituito FROM ticket_substitution " +
                     "WHERE id_user_sostituto = ? AND data_inizio <= CURRENT_DATE AND data_fine >= CURRENT_DATE";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUserSostituto);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getString("id_user_sostituito"));
            }
        }
        return list;
    }

    // =========================
    // UTILITY
    // =========================

    private TicketSubstitution mapRow(ResultSet rs) throws SQLException {
        TicketSubstitution s = new TicketSubstitution();
        s.setId(rs.getLong("id"));
        s.setIdUserSostituito(rs.getString("id_user_sostituito"));
        s.setIdUserSostituto(rs.getString("id_user_sostituto"));
        Date di = rs.getDate("data_inizio");
        if (di != null) s.setDataInizio(di.toLocalDate());
        Date df = rs.getDate("data_fine");
        if (df != null) s.setDataFine(df.toLocalDate());
        Timestamp cat = rs.getTimestamp("created_at");
        if (cat != null) s.setCreatedAt(cat.toLocalDateTime());
        Timestamp uat = rs.getTimestamp("updated_at");
        if (uat != null) s.setUpdatedAt(uat.toLocalDateTime());
        return s;
    }
}