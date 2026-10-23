package eone.ticket.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.mindrot.jbcrypt.BCrypt;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import eone.ticket.config.AppConfig;
import eone.ticket.config.DBConfig;
import eone.ticket.model.RequesterInfo;
import eone.ticket.util.SecretCrypto;
import eone.ticket.util.Totp;

/**
 * Autenticazione a due fattori TOTP (app Authenticator).
 *
 * Quando è richiesta:
 *  - l'utente ha già attivato l'MFA (segreto presente) → sempre;
 *  - utente di un cliente (kunnr valorizzato) → se il cliente ha
 *    mfa_obbligatoria = TRUE in ticket_cliente_config;
 *  - utente interno (kunnr vuoto: AMS, DISPATCHER, ADMIN, AMS_ADMIN) →
 *    se MFA_INTERNI_OBBLIGATORIA=true in config.properties.
 *
 * Colonne su ticket_user: mfa_secret (cifrato, vedi SecretCrypto),
 * mfa_attivata_il, mfa_ultimo_step (anti-riuso dello stesso codice).
 *
 * Codici di recupero (tabella ticket_mfa_recovery): 10 codici monouso
 * "XXXX-XXXX" consegnati all'utente una sola volta, salvati solo come
 * hash BCrypt. Servono ad accedere senza telefono; quando finiscono, al
 * logon successivo con l'app ne viene proposta una nuova serie.
 */
public class MfaService {

    private final ClienteConfigService clienteConfigService = new ClienteConfigService();

    /** Stato MFA di un utente. */
    public static class Stato {
        public boolean attiva;
        public String  segreto;      // in chiaro (decifrato), null se non attiva
        public long    ultimoStep;   // ultimo periodo accettato, 0 se mai
    }

    public Stato getStato(String idUser) throws SQLException {
        Stato st = new Stato();
        String sql = "SELECT mfa_secret, mfa_ultimo_step FROM ticket_user WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String salvato = rs.getString("mfa_secret");
                    if (salvato != null && !salvato.isEmpty()) {
                        st.attiva     = true;
                        st.segreto    = SecretCrypto.decifra(salvato);
                        st.ultimoStep = rs.getLong("mfa_ultimo_step");
                    }
                }
            }
        }
        return st;
    }

    /** True se per questo utente il secondo fattore è obbligatorio per policy (a prescindere dall'attivazione). */
    public boolean isObbligatoria(RequesterInfo info) throws SQLException {
        String kunnr = info.getKunnr();
        if (kunnr == null || kunnr.trim().isEmpty()) {
            return AppConfig.getBoolean("MFA_INTERNI_OBBLIGATORIA", false);
        }
        return clienteConfigService.isMfaObbligatoria(kunnr);
    }

    /**
     * Verifica il codice e, se valido, lo "consuma" registrando lo step:
     * l'UPDATE è condizionato (step > ultimo) così due richieste
     * concorrenti con lo stesso codice non possono passare entrambe.
     */
    public boolean verificaEConsuma(String idUser, Stato st, String codice) throws SQLException {
        long step = Totp.verifica(st.segreto, codice, Totp.stepCorrente());
        if (step < 0 || step <= st.ultimoStep) return false;
        String sql = "UPDATE ticket_user SET mfa_ultimo_step = ? " +
                     "WHERE id_user = ? AND (mfa_ultimo_step IS NULL OR mfa_ultimo_step < ?)";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, step);
            ps.setString(2, idUser);
            ps.setLong(3, step);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Completa l'attivazione: verifica il primo codice contro il segreto
     * proposto e solo se corretto lo salva (cifrato).
     */
    public boolean attiva(String idUser, String segretoProposto, String codice) throws SQLException {
        long step = Totp.verifica(segretoProposto, codice, Totp.stepCorrente());
        if (step < 0) return false;
        String sql = "UPDATE ticket_user SET mfa_secret = ?, mfa_attivata_il = NOW(), mfa_ultimo_step = ?, updated_at = NOW() " +
                     "WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, SecretCrypto.cifra(segretoProposto));
            ps.setLong(2, step);
            ps.setString(3, idUser);
            return ps.executeUpdate() == 1;
        }
    }

    /** Azzeramento da parte di un amministratore (telefono perso/cambiato): al prossimo accesso l'utente riattiva. */
    public void azzera(String idUser) throws SQLException {
        String sql = "UPDATE ticket_user SET mfa_secret = NULL, mfa_attivata_il = NULL, mfa_ultimo_step = NULL, updated_at = NOW() " +
                     "WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             PreparedStatement psRc = con.prepareStatement("DELETE FROM ticket_mfa_recovery WHERE id_user = ?")) {
            ps.setString(1, idUser);
            ps.executeUpdate();
            psRc.setString(1, idUser);
            psRc.executeUpdate();
        }
        AuditService.log(AuditService.MFA_AZZERATA, AuditService.ESITO_OK, idUser, "anche i codici di recupero");
    }

    // =========================================================
    // CODICI DI RECUPERO
    // =========================================================

    public static final int N_CODICI_RECUPERO = 10;
    /** Alfabeto senza caratteri ambigui (0/O, 1/I/L): 31 simboli × 8 = ~40 bit per codice. */
    private static final char[] ALFABETO_RC = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    /** Nuova serie di codici in chiaro, formato "XXXX-XXXX". NON salvata: vedi {@link #salvaCodiciRecupero}. */
    public static List<String> nuoviCodiciRecupero() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < N_CODICI_RECUPERO; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 8; j++) {
                if (j == 4) sb.append('-');
                sb.append(ALFABETO_RC[RNG.nextInt(ALFABETO_RC.length)]);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** Normalizza l'input dell'utente: maiuscole, senza spazi né trattini. */
    public static String normalizzaCodiceRecupero(String s) {
        return s == null ? "" : s.toUpperCase().replace("-", "").replace(" ", "").trim();
    }

    /** True se l'input ha la forma di un codice di recupero (8 caratteri dell'alfabeto), non di un TOTP. */
    public static boolean isFormatoCodiceRecupero(String input) {
        String n = normalizzaCodiceRecupero(input);
        if (n.length() != 8) return false;
        for (char c : n.toCharArray()) if (new String(ALFABETO_RC).indexOf(c) < 0) return false;
        return true;
    }

    /**
     * Sostituisce i codici dell'utente con quelli forniti (salvati come
     * hash). Chiamato solo DOPO che l'utente ha confermato di averli
     * salvati: se abbandona prima, non resta nessun codice "mai visto".
     */
    public void salvaCodiciRecupero(String idUser, List<String> codici) throws SQLException {
        try (Connection con = DBConfig.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement del = con.prepareStatement("DELETE FROM ticket_mfa_recovery WHERE id_user = ?");
                 PreparedStatement ins = con.prepareStatement("INSERT INTO ticket_mfa_recovery (id_user, code_hash) VALUES (?, ?)")) {
                del.setString(1, idUser);
                del.executeUpdate();
                for (String c : codici) {
                    ins.setString(1, idUser);
                    ins.setString(2, BCrypt.hashpw(normalizzaCodiceRecupero(c), BCrypt.gensalt(10)));
                    ins.addBatch();
                }
                ins.executeBatch();
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
        AuditService.log(AuditService.MFA_CODICI_GENERATI, AuditService.ESITO_OK, idUser, codici.size() + " codici");
    }

    /**
     * Revoca da parte di un amministratore (foglio perso, codici visti da
     * altri): i codici attuali non valgono più e al prossimo logon con
     * l'app l'utente ne riceve una nuova serie. L'app sul telefono resta valida.
     */
    public void revocaCodiciRecupero(String idUser) throws SQLException {
        int n;
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM ticket_mfa_recovery WHERE id_user = ?")) {
            ps.setString(1, idUser);
            n = ps.executeUpdate();
        }
        AuditService.log(AuditService.MFA_CODICI_REVOCATI, AuditService.ESITO_OK, idUser,
                         n + " codici eliminati, nuova serie al prossimo logon");
    }

    /** Sotto questa soglia l'utente viene avvisato dopo il logon. */
    public static final int SOGLIA_AVVISO_CODICI = 2;

    /** Numero di codici di recupero non ancora usati. */
    public int codiciRecuperoRimasti(String idUser) {
        String sql = "SELECT COUNT(*) FROM ticket_mfa_recovery WHERE id_user = ? AND usato_il IS NULL";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            System.err.println("[MfaService] Errore conteggio codici di recupero per " + idUser + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Prova un codice di recupero: se corrisponde a uno non usato lo
     * marca come usato (UPDATE condizionato: non riutilizzabile nemmeno
     * con due richieste contemporanee) e restituisce true.
     */
    public boolean usaCodiceRecupero(String idUser, String input) throws SQLException {
        String codice = normalizzaCodiceRecupero(input);
        if (codice.length() != 8) return false;
        List<Long> ids = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        String sql = "SELECT id, code_hash FROM ticket_mfa_recovery WHERE id_user = ? AND usato_il IS NULL";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { ids.add(rs.getLong("id")); hashes.add(rs.getString("code_hash")); }
            }
        }
        for (int i = 0; i < hashes.size(); i++) {
            boolean ok;
            try { ok = BCrypt.checkpw(codice, hashes.get(i)); } catch (Exception e) { ok = false; }
            if (!ok) continue;
            String upd = "UPDATE ticket_mfa_recovery SET usato_il = NOW() WHERE id = ? AND usato_il IS NULL";
            try (Connection con = DBConfig.getConnection();
                 PreparedStatement ps = con.prepareStatement(upd)) {
                ps.setLong(1, ids.get(i));
                if (ps.executeUpdate() == 1) {
                    AuditService.log(idUser, null, null, AuditService.MFA_RECUPERO_USATO, AuditService.ESITO_OK, idUser,
                                     "codici rimasti: " + codiciRecuperoRimasti(idUser));
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /** True se l'utente ha l'MFA attiva — per le schermate di amministrazione. */
    public boolean isAttiva(String idUser) {
        String sql = "SELECT mfa_secret IS NOT NULL AS attiva FROM ticket_user WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, idUser);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("attiva");
            }
        } catch (Exception e) {
            System.err.println("[MfaService] Errore lettura stato MFA per " + idUser + ": " + e.getMessage());
            return false;
        }
    }

    public static String issuer() {
        return AppConfig.get("MFA_ISSUER", "Ticketing e-One");
    }

    /** PNG del QR code per l'URI otpauth:// (lato in pixel). */
    public static byte[] qrPng(String testo, int lato) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix m = new QRCodeWriter().encode(testo, BarcodeFormat.QR_CODE, lato, lato, hints);
        BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < m.getHeight(); y++)
            for (int x = 0; x < m.getWidth(); x++)
                img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
