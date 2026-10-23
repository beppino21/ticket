package eone.ticket.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import eone.ticket.config.AppConfig;
import eone.ticket.config.DBConfig;

/**
 * Blocco temporaneo dell'utente dopo troppi tentativi falliti
 * (password errata o codice MFA errato) — difesa contro i tentativi
 * ripetuti di indovinare le credenziali.
 *
 * Parametri (config.properties):
 *   LOGIN_MAX_TENTATIVI  default 5
 *   LOGIN_BLOCCO_MINUTI  default 15
 *
 * Il contatore si azzera solo a logon COMPLETATO (password + eventuale
 * MFA) o con un reset password amministrativo: una password corretta da
 * sola non lo azzera, altrimenti chi la conosce potrebbe provare codici
 * MFA all'infinito.
 *
 * Colonne su ticket_user: login_falliti, bloccato_fino.
 */
public class LoginGuardService {

    public static int maxTentativi() {
        return parseInt(AppConfig.get("LOGIN_MAX_TENTATIVI", "5"), 5);
    }

    public static int minutiBlocco() {
        return parseInt(AppConfig.get("LOGIN_BLOCCO_MINUTI", "15"), 15);
    }

    /** True se l'utente è attualmente bloccato. Utente inesistente → false. */
    public boolean isBloccato(String idUser) {
        String sql = "SELECT bloccato_fino IS NOT NULL AND bloccato_fino > NOW() AS bloccato FROM ticket_user WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("bloccato");
            }
        } catch (Exception e) {
            System.err.println("[LoginGuard] Errore verifica blocco per " + idUser + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Registra un tentativo fallito. Al raggiungimento della soglia imposta
     * il blocco e riparte da zero. Restituisce true se l'utente è stato
     * bloccato da questo tentativo.
     */
    public boolean registraFallimento(String idUser) {
        int max = maxTentativi();
        String sql =
            "UPDATE ticket_user SET " +
            "  bloccato_fino = CASE WHEN login_falliti + 1 >= ? THEN NOW() + (? * INTERVAL '1 minute') ELSE bloccato_fino END, " +
            "  login_falliti = CASE WHEN login_falliti + 1 >= ? THEN 0 ELSE login_falliti + 1 END " +
            "WHERE id_user = ? " +
            "RETURNING bloccato_fino IS NOT NULL AND bloccato_fino > NOW() AS bloccato";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, max);
            ps.setInt(2, minutiBlocco());
            ps.setInt(3, max);
            ps.setString(4, idUser);
            try (ResultSet rs = ps.executeQuery()) {
                boolean bloccato = rs.next() && rs.getBoolean("bloccato");
                if (bloccato) {
                    AuditService.log(idUser, null, null, AuditService.LOGIN_BLOCCATO, AuditService.ESITO_FAIL, idUser,
                                     max + " tentativi falliti — bloccato per " + minutiBlocco() + " minuti");
                }
                return bloccato;
            }
        } catch (Exception e) {
            System.err.println("[LoginGuard] Errore registrazione tentativo per " + idUser + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Fine del blocco in corso, o null se l'utente non è bloccato adesso —
     * per l'indicatore nelle schermate di amministrazione.
     */
    public java.time.LocalDateTime getBloccatoFino(String idUser) {
        String sql = "SELECT bloccato_fino FROM ticket_user WHERE id_user = ? AND bloccato_fino > NOW()";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                java.sql.Timestamp t = rs.getTimestamp("bloccato_fino");
                return t != null ? t.toLocalDateTime() : null;
            }
        } catch (Exception e) {
            System.err.println("[LoginGuard] Errore lettura blocco per " + idUser + ": " + e.getMessage());
            return null;
        }
    }

    /** Sblocco manuale da parte di un amministratore (senza toccare la password). */
    public void sblocca(String idUser) throws SQLException {
        String sql = "UPDATE ticket_user SET login_falliti = 0, bloccato_fino = NULL WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            ps.executeUpdate();
        }
        AuditService.log(AuditService.UTENTE_SBLOCCATO, AuditService.ESITO_OK, idUser, "sblocco manuale");
    }

    /** Logon completato: azzera contatore e blocco. */
    public void registraSuccesso(String idUser) {
        String sql = "UPDATE ticket_user SET login_falliti = 0, bloccato_fino = NULL " +
                     "WHERE id_user = ? AND (login_falliti <> 0 OR bloccato_fino IS NOT NULL)";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[LoginGuard] Errore azzeramento tentativi per " + idUser + ": " + e.getMessage());
        }
    }

    private static int parseInt(String s, int def) {
        try { return Math.max(1, Integer.parseInt(s.trim())); } catch (Exception e) { return def; }
    }
}
