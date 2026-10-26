package eone.ticket.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.mindrot.jbcrypt.BCrypt;

import eone.ticket.config.DBConfig;
import eone.ticket.model.RequesterInfo;
import eone.ticket.model.Ticket;
import eone.ticket.model.TicketDraft;
import eone.ticket.service.TicketReferenteService;

/**
 * Service per la gestione utenti da parte di AMS_ADMIN (utenti AMS/DISPATCHER)
 * e REQ_ADMIN (richiedenti CLIENTE del proprio Kunnr).
 *
 * La cancellazione è bloccata se l'utente ha ticket attivi a suo carico
 * (SAP, stato diverso da CLO) o — per i CLIENTE — DRAFT non ancora fusi in
 * SAP: altrimenti quei ticket resterebbero orfani, senza nessuno che li
 * gestisce, esattamente lo stesso principio già applicato altrove nell'app
 * (es. la protezione contro la fusione DRAFT → ticket SAP inesistente).
 */
public class UserAdminService {

    private static final String COLS =
        "id_user, kunnr, reqid, nome, email, ruolo, vede_tutti, attivo, gestisce_referenti, reqid_richiedente, " +
        "password_impostata_il, password_scadenza_giorni, password_non_scade, promemoria_ogni_giorni";

    // =========================
    // LISTE
    // =========================

    /** Utenti AMS e DISPATCHER — per AMS_ADMIN. */
    public List<RequesterInfo> listAmsUsers() throws SQLException {
        String sql = "SELECT " + COLS + " FROM ticket_user " +
                     "WHERE ruolo IN ('AMS','DISPATCHER') ORDER BY nome";
        return query(sql);
    }

    /** Richiedenti (CLIENTE) di un preciso Kunnr — per REQ_ADMIN. */
    public List<RequesterInfo> listRichiedenti(String kunnr) throws SQLException {
        String sql = "SELECT " + COLS + " FROM ticket_user " +
                     "WHERE ruolo = 'CLIENTE' AND LPAD(kunnr, 10, '0') = LPAD(?, 10, '0') ORDER BY nome";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, kunnr);
            try (ResultSet rs = ps.executeQuery()) {
                List<RequesterInfo> list = new ArrayList<>();
                while (rs.next()) list.add(mapRow(rs));
                return list;
            }
        }
    }

    /**
     * Referenti_cli di un preciso Kunnr — per un CLIENTE con
     * gestisce_referenti=TRUE (self-service sul proprio kunnr) oppure per
     * ADMIN (kunnr scelto liberamente).
     */
    public List<RequesterInfo> listReferenti(String kunnr) throws SQLException {
        String sql = "SELECT " + COLS + " FROM ticket_user " +
                     "WHERE ruolo = 'REFERENTE_CLI' AND LPAD(kunnr, 10, '0') = LPAD(?, 10, '0') ORDER BY nome";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, kunnr);
            try (ResultSet rs = ps.executeQuery()) {
                List<RequesterInfo> list = new ArrayList<>();
                while (rs.next()) list.add(mapRow(rs));
                return list;
            }
        }
    }

    // =========================
    // CREAZIONE / MODIFICA
    // =========================

    /**
     * Gli username non distinguono maiuscole e minuscole (vedi
     * RequesterService.authenticate): rifiuta un nuovo username uguale a uno
     * esistente a meno delle maiuscole, con un messaggio comprensibile.
     * L'indice univoco ux_ticket_user_id_user_lower lo garantisce comunque
     * anche a livello di database.
     */
    private void verificaUsernameLibero(String idUser) throws SQLException {
        String sql = "SELECT id_user FROM ticket_user WHERE LOWER(id_user) = LOWER(?)";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new SQLException("esiste già l'utente '" + rs.getString("id_user")
                        + "' (gli username non distinguono maiuscole e minuscole)");
                }
            }
        }
    }

    /**
     * Crea un nuovo utente AMS o DISPATCHER. La password iniziale viene
     * impostata "già scaduta" (password_impostata_il nel passato oltre la
     * soglia) così l'utente è forzato a cambiarla al primo accesso — stesso
     * meccanismo già usato per la policy password, nessun flag a parte.
     */
    public void createAmsUser(String idUser, String nome, String email, String ruolo,
                               String passwordIniziale) throws SQLException {
        verificaUsernameLibero(idUser);
        String hash = BCrypt.hashpw(passwordIniziale, BCrypt.gensalt(12));
        String sql = "INSERT INTO ticket_user " +
                     "(id_user, kunnr, reqid, nome, email, password_hash, ruolo, vede_tutti, attivo, " +
                     " password_impostata_il, password_scadenza_giorni) " +
                     "VALUES (?, '', '', ?, ?, ?, ?, FALSE, TRUE, NOW() - INTERVAL '91 days', 90)";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            ps.setString(2, nome);
            ps.setString(3, email);
            ps.setString(4, hash);
            ps.setString(5, ruolo);
            ps.executeUpdate();
        }
        AuditService.log(AuditService.UTENTE_CREATO, AuditService.ESITO_OK, idUser, "ruolo=" + ruolo);
    }

    /**
     * Crea un nuovo richiedente CLIENTE per il Kunnr indicato (quello del
     * REQ_ADMIN che lo sta creando — non è l'utente a poterlo scegliere).
     */
    public void createRichiedente(String idUser, String kunnr, String reqid, String nome,
                                   String email, String passwordIniziale) throws SQLException {
        verificaUsernameLibero(idUser);
        String hash = BCrypt.hashpw(passwordIniziale, BCrypt.gensalt(12));
        String sql = "INSERT INTO ticket_user " +
                     "(id_user, kunnr, reqid, nome, email, password_hash, ruolo, vede_tutti, attivo, " +
                     " password_impostata_il, password_scadenza_giorni) " +
                     "VALUES (?, ?, ?, ?, ?, ?, 'CLIENTE', FALSE, TRUE, NOW() - INTERVAL '91 days', 90)";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            ps.setString(2, kunnr);
            ps.setString(3, reqid);
            ps.setString(4, nome);
            ps.setString(5, email);
            ps.setString(6, hash);
            ps.executeUpdate();
        }
        AuditService.log(AuditService.UTENTE_CREATO, AuditService.ESITO_OK, idUser,
                         "ruolo=CLIENTE kunnr=" + kunnr + " reqid=" + reqid);
    }

    /**
     * Crea un nuovo referente_cli per il Kunnr indicato, collegato al
     * richiedente (reqidRichiedente) a cui fa capo. Il reqid del referente
     * è sempre = idUser (identità propria e distinta, mai condivisa con un
     * richiedente) — non richiede un input separato.
     */
    public void createReferente(String idUser, String kunnr, String reqidRichiedente, String nome,
                                 String email, String passwordIniziale) throws SQLException {
        verificaUsernameLibero(idUser);
        String hash = BCrypt.hashpw(passwordIniziale, BCrypt.gensalt(12));
        String sql = "INSERT INTO ticket_user " +
                     "(id_user, kunnr, reqid, reqid_richiedente, nome, email, password_hash, ruolo, vede_tutti, attivo, " +
                     " password_impostata_il, password_scadenza_giorni) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, 'REFERENTE_CLI', FALSE, TRUE, NOW() - INTERVAL '91 days', 90)";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            ps.setString(2, kunnr);
            ps.setString(3, idUser);          // reqid del referente = idUser
            ps.setString(4, reqidRichiedente); // a chi fa capo
            ps.setString(5, nome);
            ps.setString(6, email);
            ps.setString(7, hash);
            ps.executeUpdate();
        }
        AuditService.log(AuditService.UTENTE_CREATO, AuditService.ESITO_OK, idUser,
                         "ruolo=REFERENTE_CLI kunnr=" + kunnr + " richiedente=" + reqidRichiedente);
    }
    /**
     * Aggiorna nome/email e i flag di stato/policy password — non tocca
     * kunnr/reqid/ruolo (identità fissa, non modificabile dopo la creazione).
     * gestisceReferenti ha senso solo per richiedenti CLIENTE — per utenti
     * AMS/DISPATCHER/REFERENTE_CLI va sempre passato false.
     */
    public void updateAnagrafica(String idUser, String nome, String email, boolean attivo,
                                  boolean vedeTutti, boolean passwordNonScade,
                                  int passwordScadenzaGiorni, boolean gestisceReferenti) throws SQLException {
        String sql = "UPDATE ticket_user SET nome = ?, email = ?, attivo = ?, vede_tutti = ?, " +
                     "password_non_scade = ?, password_scadenza_giorni = ?, gestisce_referenti = ?, updated_at = NOW() " +
                     "WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nome);
            ps.setString(2, email);
            ps.setBoolean(3, attivo);
            ps.setBoolean(4, vedeTutti);
            ps.setBoolean(5, passwordNonScade);
            ps.setInt(6, passwordScadenzaGiorni);
            ps.setBoolean(7, gestisceReferenti);
            ps.setString(8, idUser);
            ps.executeUpdate();
        }
        AuditService.log(AuditService.UTENTE_MODIFICATO, AuditService.ESITO_OK, idUser,
                         "attivo=" + attivo + " vedeTutti=" + vedeTutti + " pwdNonScade=" + passwordNonScade +
                         " pwdScadenzaGg=" + passwordScadenzaGiorni + " gestisceReferenti=" + gestisceReferenti);
    }

    /** Frequenza del promemoria in giorni (1 = ogni giorno feriale, N = ogni N giorni, 0 = mai). */
    public void updatePromemoriaFrequenza(String idUser, int giorni) throws SQLException {
        if (giorni < 0 || giorni > 365) throw new IllegalArgumentException("Frequenza promemoria fuori intervallo (0-365)");
        String sql = "UPDATE ticket_user SET promemoria_ogni_giorni = ?, updated_at = NOW() WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, giorni);
            ps.setString(2, idUser);
            ps.executeUpdate();
        }
    }

    /**
     * Reset password amministrativo — pensato per gli utenti che aprono
     * pochi ticket e si dimenticano la password (il caso d'uso più comune,
     * secondo il backoffice). La nuova password viene impostata "già
     * scaduta" (stesso meccanismo della policy password, nessun flag a
     * parte): l'utente dovrà sceglierne una propria al primo accesso.
     */
    /**
     * Reimposta la password e forza il cambio al prossimo accesso —
     * azzera SEMPRE anche password_non_scade: chi effettua un reset si
     * aspetta che la persona debba cambiarla al primo accesso, anche se in
     * precedenza era stata impostata come esente da scadenza.
     */
    public void resetPassword(String idUser, String nuovaPasswordTemporanea) throws SQLException {
        String hash = BCrypt.hashpw(nuovaPasswordTemporanea, BCrypt.gensalt(12));
        String sql = "UPDATE ticket_user SET password_hash = ?, " +
                     "password_impostata_il = NOW() - INTERVAL '3650 days', password_non_scade = FALSE, " +
                     "login_falliti = 0, bloccato_fino = NULL, updated_at = NOW() " +   // il reset sblocca anche l'utente
                     "WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, idUser);
            ps.executeUpdate();
        }
        AuditService.log(AuditService.PASSWORD_RESET, AuditService.ESITO_OK, idUser, "password temporanea, cambio forzato al prossimo accesso");
    }

    // =========================
    // CANCELLAZIONE (con guardia sui ticket attivi)
    // =========================

    /**
     * Cancella un utente AMS/DISPATCHER, ma solo se non ha ticket SAP
     * attivi (stato diverso da CLO) assegnati. Ritorna il numero di ticket
     * attivi trovati (0 = cancellazione eseguita).
     */
    public int deleteAmsUser(String idUser, SAPTicketService sapService) throws Exception {
        int attivi = contaTicketAttiviAms(idUser, sapService);
        if (attivi > 0) return attivi; // bloccato, non cancella

        String sql = "DELETE FROM ticket_user WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            ps.executeUpdate();
        }
        AuditService.log(AuditService.UTENTE_ELIMINATO, AuditService.ESITO_OK, idUser, null);
        return 0;
    }

    /**
     * Cancella un richiedente CLIENTE, ma solo se non ha ticket SAP attivi
     * né DRAFT non ancora fusi a suo carico. Ritorna il numero di
     * ticket/draft attivi trovati (0 = cancellazione eseguita).
     */
    public int deleteRichiedente(String idUser, String kunnr, String reqid,
                                  SAPTicketService sapService, TicketDraftService draftService) throws Exception {
        int attivi = contaTicketAttiviRichiedente(kunnr, reqid, sapService, draftService);
        if (attivi > 0) return attivi; // bloccato, non cancella

        String sql = "DELETE FROM ticket_user WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            ps.executeUpdate();
        }
        AuditService.log(AuditService.UTENTE_ELIMINATO, AuditService.ESITO_OK, idUser, null);
        return 0;
    }

    /**
     * Cancella un referente_cli, ma solo se non è attualmente assegnato come
     * referente su nessun ticket (DRAFT o SAP) — altrimenti quel ticket
     * resterebbe con un referente inesistente. Ritorna il numero di
     * assegnazioni attive trovate (0 = cancellazione eseguita).
     */
    public int deleteReferente(String idUser, String kunnr, String reqid,
                                TicketReferenteService referenteService) throws Exception {
        int attivi = contaTicketAttiviReferente(kunnr, reqid, referenteService);
        if (attivi > 0) return attivi; // bloccato, non cancella

        String sql = "DELETE FROM ticket_user WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            ps.executeUpdate();
        }
        AuditService.log(AuditService.UTENTE_ELIMINATO, AuditService.ESITO_OK, idUser, null);
        return 0;
    }

    /** Solo il conteggio, senza cancellare — usato dalla UI per il warning prima della conferma. */
    public int contaTicketAttiviAms(String idUser, SAPTicketService sapService) throws Exception {
        SAPTicketService.TicketResponse resp = sapService.getTickets(null, null, null, null, null, "ne:CLO");
        if (!resp.isSuccess() || resp.getTickets() == null) return 0;
        int count = 0;
        for (Ticket t : resp.getTickets()) {
            if (idUser.equalsIgnoreCase(t.getAmusr())) count++;
        }
        return count;
    }

    /** Solo il conteggio, senza cancellare — usato dalla UI per il warning prima della conferma. */
    public int contaTicketAttiviRichiedente(String kunnr, String reqid,
                                             SAPTicketService sapService, TicketDraftService draftService) throws Exception {
        int count = 0;

        // Ticket SAP attivi (Kunnr+Reqid: filtro confermato funzionante lato SAP)
        SAPTicketService.TicketResponse resp = sapService.getTickets(kunnr, reqid, null, null, null, "ne:CLO");
        if (resp.isSuccess() && resp.getTickets() != null) {
            count += resp.getTickets().size();
        }

        // DRAFT non ancora fusi in SAP
        List<TicketDraft> drafts = draftService.getDraftsByRequester(kunnr, reqid);
        for (TicketDraft d : drafts) {
            if (d.isDraft()) count++;
        }

        return count;
    }

    /** Cambia il richiedente a cui fa capo un referente_cli già esistente. */
    public void updateReferenteRichiedente(String idUser, String reqidRichiedente) throws SQLException {
        String sql = "UPDATE ticket_user SET reqid_richiedente = ?, updated_at = NOW() WHERE id_user = ? AND ruolo = 'REFERENTE_CLI'";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, reqidRichiedente);
            ps.setString(2, idUser);
            ps.executeUpdate();
        }
    }

    /** Solo il conteggio, senza cancellare — usato dalla UI per il warning prima della conferma. */
    public int contaTicketAttiviReferente(String kunnr, String reqid, TicketReferenteService referenteService) throws Exception {
        return referenteService.getTicktsByReferente(kunnr, reqid).size();
    }

    // =========================
    // UTILITY
    // =========================

    private List<RequesterInfo> query(String sql) throws SQLException {
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<RequesterInfo> list = new ArrayList<>();
            while (rs.next()) list.add(mapRow(rs));
            return list;
        }
    }

    private RequesterInfo mapRow(ResultSet rs) throws SQLException {
        RequesterInfo info = new RequesterInfo();
        info.setId_user(rs.getString("id_user"));
        info.setKunnr(rs.getString("kunnr"));
        info.setReqid(rs.getString("reqid"));
        info.setNome(rs.getString("nome"));
        info.setEmail(rs.getString("email"));
        info.setRuolo(rs.getString("ruolo"));
        info.setVedeTutti(rs.getBoolean("vede_tutti"));
        info.setAttivo(rs.getBoolean("attivo"));
        info.setGestisceReferenti(rs.getBoolean("gestisce_referenti"));
        info.setReqidRichiedente(rs.getString("reqid_richiedente"));
        info.setPasswordNonScade(rs.getBoolean("password_non_scade"));
        info.setPasswordScadenzaGiorni(rs.getInt("password_scadenza_giorni"));
        info.setPromemoriaOgniGiorni(rs.getInt("promemoria_ogni_giorni"));
        return info;
    }
}