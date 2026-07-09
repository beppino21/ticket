package eone.ticket.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.mindrot.jbcrypt.BCrypt;

import eone.ticket.config.DBConfig;
import eone.ticket.model.RequesterInfo;

/**
 * Service per la gestione degli utenti su PostgreSQL (ticket_user).
 *
 * Responsabilità:
 *  - autenticazione via bcrypt (sostituisce SAPLogonService per il logon)
 *  - cambio password
 *  - lookup nome richiedente per arricchimento lista ticket
 *
 * NOTA su "attivo": il flag riguarda solo l'accesso/logon dell'utente al
 * tool ticket (confermato: attivo=false significa che l'utente non entra
 * nemmeno nei ticket). Non ha nulla a che vedere con la validità come
 * destinatario di notifica email. Per questo authenticate()/changePassword()
 * filtrano su attivo=TRUE (è una questione di sicurezza del logon), mentre
 * getById()/getByKunnrReqid() — usati solo per risolvere i destinatari delle
 * notifiche — NON lo filtrano più: un AMS o un cliente senza accesso al tool
 * può comunque essere un destinatario email valido.
 */
public class RequesterService {

    // =========================================================
    // LOGON
    // =========================================================

    /**
     * Restituisce gli utenti DISPATCHER attivi con email valorizzata — usato
     * per notificare la creazione di un nuovo ticket DRAFT da smistare.
     */
    public java.util.List<RequesterInfo> getActiveDispatchers() throws SQLException {
        java.util.List<RequesterInfo> list = new java.util.ArrayList<>();
        String sql = "SELECT id_user, kunnr, reqid, nome, email, password_hash, ruolo, vede_tutti, attivo, password_impostata_il, password_scadenza_giorni, password_non_scade " +
                     "FROM ticket_user " +
                     "WHERE ruolo = 'DISPATCHER' AND attivo = TRUE AND email IS NOT NULL AND email <> ''";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    /**
     * Verifica credenziali di accesso contro ticket_user.
     *
     * @param reqid    username inserito dall'utente
     * @param password password in chiaro inserita dall'utente
     * @return RequesterInfo popolato se autenticazione OK, null se KO
     * @throws SQLException in caso di errore DB (non credential failure)
     */
    public RequesterInfo authenticate(String id_user, String password) throws SQLException {
        if (id_user == null || id_user.trim().isEmpty()) return null;
        if (password == null || password.isEmpty())  return null;

        String sql = "SELECT id_user, kunnr, reqid, nome, email, password_hash, ruolo, vede_tutti, attivo, password_impostata_il, password_scadenza_giorni, password_non_scade " +
                     "FROM ticket_user " +
                     "WHERE id_user = ? AND attivo = TRUE";

        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

        	System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
        	System.out.println(id_user.trim());
        	System.out.println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");

            ps.setString(1, id_user.trim());

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    System.out.println("[RequesterService] Utente non trovato o non attivo: " + id_user);
                    return null;
                }

                String storedHash = rs.getString("password_hash");

                // Caso primo avvio: hash non ancora impostato (null o vuoto)
                if (storedHash == null || storedHash.trim().isEmpty()) {
                    System.err.println("[RequesterService] password_hash non impostato per: " + id_user);
                    return null;
                }

                // Verifica bcrypt
                boolean ok;
                try {
                    ok = BCrypt.checkpw(password, storedHash);
                } catch (Exception e) {
                    System.err.println("[RequesterService] Errore bcrypt per " + id_user + ": " + e.getMessage());
                    return null;
                }

                if (!ok) {
                    System.out.println("[RequesterService] Password errata per: " + id_user);
                    return null;
                }

                // Autenticazione riuscita
                RequesterInfo info = mapRow(rs);

                System.out.println("[RequesterService] ✅ Autenticazione OK: " + id_user +
                                   " ruolo=" + info.getRuolo() + " kunnr=" + info.getKunnr());
                return info;
            }
        }
    }

    /**
     * Cerca un utente per id_user, senza verifica password.
     * Usato per risolvere i destinatari delle notifiche email (es. campo amusr).
     *
     * Non filtra su "attivo": un utente senza accesso al tool (es. un AMS
     * censito solo per nome/email) resta comunque un destinatario email valido.
     */
    public RequesterInfo getById(String id_user) throws SQLException {
        if (id_user == null || id_user.trim().isEmpty()) return null;

        String sql = "SELECT id_user, kunnr, reqid, nome, email, password_hash, ruolo, vede_tutti, attivo, password_impostata_il, password_scadenza_giorni, password_non_scade " +
                     "FROM ticket_user WHERE id_user = ?";

        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, id_user.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapRow(rs);
            }
        }
    }

    /**
     * Cerca il richiedente (CLIENTE) collegato a una coppia kunnr+reqid.
     * Usato per risolvere il destinatario cliente delle notifiche email.
     * Nota: reqid non è univoco da solo — kunnr+reqid identifica il richiedente.
     *
     * Il confronto su kunnr è fatto con LPAD a 10 cifre su entrambi i lati:
     * il kunnr che arriva dal ticket (via SAP OData) può non essere
     * zero-paddato a 10 cifre come invece è memorizzato in ticket_user
     * (es. "3000004" vs "0003000004") — senza normalizzazione il match
     * fallisce sempre pur trattandosi dello stesso cliente.
     *
     * Non filtra su "attivo" per lo stesso motivo di getById(): qui si
     * risolve un destinatario di notifica, non un accesso al tool.
     */
    public RequesterInfo getByKunnrReqid(String kunnr, String reqid) throws SQLException {
        if (kunnr == null || reqid == null) return null;

        String sql = "SELECT id_user, kunnr, reqid, nome, email, password_hash, ruolo, vede_tutti, attivo, password_impostata_il, password_scadenza_giorni, password_non_scade " +
                     "FROM ticket_user WHERE LPAD(kunnr, 10, '0') = LPAD(?, 10, '0') AND reqid = ? LIMIT 1";

        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, kunnr.trim());
            ps.setString(2, reqid.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapRow(rs);
            }
        }
    }

    /** Mapping comune ResultSet -> RequesterInfo, riusato da authenticate/getById/getByKunnrReqid */
    private RequesterInfo mapRow(ResultSet rs) throws SQLException {
        RequesterInfo info = new RequesterInfo();
        info.setId_user  (rs.getString  ("id_user"));
        info.setKunnr    (rs.getString  ("kunnr"));
        info.setReqid    (rs.getString  ("reqid"));
        info.setNome     (rs.getString  ("nome"));
        info.setEmail    (rs.getString  ("email"));
        info.setRuolo    (rs.getString  ("ruolo"));
        info.setVedeTutti(rs.getBoolean ("vede_tutti"));
        java.sql.Timestamp impostataIl = rs.getTimestamp("password_impostata_il");
        if (impostataIl != null) info.setPasswordImpostataIl(impostataIl.toLocalDateTime());
        info.setPasswordScadenzaGiorni(rs.getInt("password_scadenza_giorni"));
        info.setPasswordNonScade(rs.getBoolean("password_non_scade"));
        return info;
    }

    // =========================================================
    // CAMBIO PASSWORD
    // =========================================================

    /**
     * Cambia la password di un utente dopo aver verificato quella vecchia.
     *
     * @param reqid       username
     * @param oldPassword password attuale in chiaro
     * @param newPassword nuova password in chiaro (già validata dalla UI)
     * @return true se il cambio è riuscito, false se la vecchia password è errata
     * @throws SQLException in caso di errore DB
     */
    public boolean changePassword(String reqid, String oldPassword, String newPassword)
            throws SQLException {

        // Prima verifica la vecchia password
        RequesterInfo info = authenticate(reqid, oldPassword);
        if (info == null) {
            System.out.println("[RequesterService] changePassword: vecchia password errata per " + reqid);
            return false;
        }

        // Hash della nuova password
        String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12));

        String sql = "UPDATE ticket_user SET password_hash = ?, password_impostata_il = NOW(), updated_at = NOW() " +
                     "WHERE id_user = ? AND attivo = TRUE"; // password_impostata_il = NOW() qui azzera automaticamente
                                                             // lo stato di scadenza — nessun flag da ripulire a parte

        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, newHash);
            ps.setString(2, reqid.trim());
            int rows = ps.executeUpdate();

            if (rows == 1) {
                System.out.println("[RequesterService] ✅ Password aggiornata per: " + reqid);
                return true;
            } else {
                System.err.println("[RequesterService] ❌ Nessuna riga aggiornata per: " + reqid);
                return false;
            }
        }
    }

    // =========================================================
    // ENRICHMENT — lookup nomi per lista ticket
    // =========================================================

    /**
     * Restituisce una mappa id_user → nome per gli utenti passati.
     * Usato da EnrichmentService per arricchire la lista commenti con i nomi.
     * id_user è la PK di ticket_user (username univoco assegnato dal backoffice).
     */
    public java.util.Map<String, String> getNomiByIdUsers(List<String> idUsers) throws SQLException {

        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (idUsers == null || idUsers.isEmpty()) return result;

        List<String> valid = idUsers.stream()
            .filter(r -> r != null && !r.trim().isEmpty())
            .collect(Collectors.toList());
        if (valid.isEmpty()) return result;

        StringBuilder sql = new StringBuilder(
            "SELECT id_user, nome FROM ticket_user WHERE id_user IN (");
        for (int i = 0; i < valid.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(")");

        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {

            for (int i = 0; i < valid.size(); i++) {
                ps.setString(i + 1, valid.get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String idUser = rs.getString("id_user");
                    String nome   = rs.getString("nome");
                    if (nome != null && !nome.trim().isEmpty()) {
                        result.put(idUser, nome.trim());
                    }
                }
            }
        }

        System.out.println("[RequesterService] getNomiByIdUsers: risolti " +
                           result.size() + "/" + valid.size());
        return result;
    }
}