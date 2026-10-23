package eone.ticket.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eone.ticket.config.DBConfig;
import eone.ticket.model.ClienteConfig;
import eone.ticket.model.PasswordPolicy;

/**
 * Gestisce l'abilitazione dei clienti (Kunnr) alla nuova gestione ticket —
 * durante la migrazione cliente-per-cliente dalla vecchia procedura, un
 * Kunnr assente da questa tabella (o presente con abilitato=FALSE) viene
 * escluso dalle viste AMS/DISPATCHER, per non confondere il servizio con
 * ticket di clienti non ancora migrati.
 */
public class ClienteConfigService {

    /** Tutti i Kunnr attualmente abilitati — usato per filtrare le liste ticket. */
    public Set<String> getKunnrAbilitati() throws SQLException {
        Set<String> set = new HashSet<>();
        String sql = "SELECT kunnr FROM ticket_cliente_config WHERE abilitato = TRUE";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) set.add(normalizeKunnr(rs.getString("kunnr")));
        }
        return set;
    }

    /** Elenco completo (abilitati e non) — per la schermata di amministrazione. */
    public List<ClienteConfig> listAll() throws SQLException {
        List<ClienteConfig> list = new ArrayList<>();
        String sql = "SELECT kunnr, nome_cliente, abilitato, prefisso_referente, promemoria_richiedenti_abilitato, " +
                     "pwd_lunghezza_minima, pwd_min_speciali, pwd_min_numerici, pwd_maiusc_minusc, mfa_obbligatoria, created_at, updated_at " +
                     "FROM ticket_cliente_config ORDER BY kunnr";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ClienteConfig c = new ClienteConfig();
                c.setKunnr(rs.getString("kunnr"));
                c.setNomeCliente(rs.getString("nome_cliente"));
                c.setAbilitato(rs.getBoolean("abilitato"));
                c.setPrefissoReferente(rs.getString("prefisso_referente"));
                c.setPromemoriaRichiedentiAbilitato(rs.getBoolean("promemoria_richiedenti_abilitato"));
                c.setPasswordPolicy(mapPolicy(rs));
                c.setMfaObbligatoria(rs.getBoolean("mfa_obbligatoria"));
                list.add(c);
            }
        }
        return list;
    }

    /**
     * Kunnr abilitati ALLA NUOVA GESTIONE e con il promemoria "attività
     * ferme" per RICHIEDENTI/REFERENTE_CLI attivo — usato da
     * PromemoriaQuotidianoService per decidere a chi inviarlo senza dover
     * fare una query per cliente. Un Kunnr disabilitato del tutto
     * (abilitato=FALSE) è escluso a prescindere dal flag promemoria.
     */
    public Set<String> getKunnrAbilitatiPromemoriaRichiedenti() throws SQLException {
        Set<String> set = new HashSet<>();
        String sql = "SELECT kunnr FROM ticket_cliente_config WHERE abilitato = TRUE AND promemoria_richiedenti_abilitato = TRUE";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) set.add(normalizeKunnr(rs.getString("kunnr")));
        }
        return set;
    }

    /**
     * Crea o aggiorna la configurazione di un cliente (upsert su kunnr).
     * Se prefissoReferente è valorizzato, ne verifica l'unicità (a meno di
     * maiuscole/minuscole) PRIMA di scrivere — oltre al vincolo univoco a
     * DB, così l'operatore vede subito un messaggio chiaro invece
     * dell'errore Postgres grezzo. Lancia IllegalStateException se il
     * prefisso è già in uso da un altro cliente.
     */
    public void save(String kunnr, String nomeCliente, boolean abilitato, String prefissoReferente) throws SQLException {
        save(kunnr, nomeCliente, abilitato, prefissoReferente, true);
    }

    /**
     * Come {@link #save(String, String, boolean, String)}, con in più il
     * toggle del promemoria giornaliero per RICHIEDENTI/REFERENTE_CLI.
     * Non modifica la policy password già configurata (default su insert).
     */
    public void save(String kunnr, String nomeCliente, boolean abilitato, String prefissoReferente,
                      boolean promemoriaRichiedentiAbilitato) throws SQLException {
        save(kunnr, nomeCliente, abilitato, prefissoReferente, promemoriaRichiedentiAbilitato, null);
    }

    /**
     * Versione completa, con la policy password del cliente.
     * passwordPolicy = null → su insert si applicano i default
     * (8/0/0/false), su update si mantengono i valori già presenti.
     */
    public void save(String kunnr, String nomeCliente, boolean abilitato, String prefissoReferente,
                      boolean promemoriaRichiedentiAbilitato, PasswordPolicy passwordPolicy) throws SQLException {
        save(kunnr, nomeCliente, abilitato, prefissoReferente, promemoriaRichiedentiAbilitato, passwordPolicy, null);
    }

    /**
     * Versione completa con anche l'obbligo MFA. mfaObbligatoria = null →
     * su insert FALSE, su update invariato (stessa logica della policy password).
     */
    public void save(String kunnr, String nomeCliente, boolean abilitato, String prefissoReferente,
                      boolean promemoriaRichiedentiAbilitato, PasswordPolicy passwordPolicy,
                      Boolean mfaObbligatoria) throws SQLException {
        String prefisso = (prefissoReferente == null || prefissoReferente.trim().isEmpty())
            ? null : prefissoReferente.trim().toUpperCase();

        if (prefisso != null) {
            String sqlCheck = "SELECT kunnr FROM ticket_cliente_config " +
                               "WHERE UPPER(prefisso_referente) = ? AND kunnr <> ?";
            try (Connection con = DBConfig.getConnection();
                 PreparedStatement ps = con.prepareStatement(sqlCheck)) {
                ps.setString(1, prefisso);
                ps.setString(2, kunnr);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalStateException("Prefisso \"" + prefisso + "\" già usato dal cliente " +
                                                          rs.getString("kunnr") + " — sceglierne uno diverso");
                    }
                }
            }
        }

        // Le 4 colonne policy usano COALESCE: un parametro NULL significa
        // "default" in insert e "lascia com'è" in update.
        String sql = "INSERT INTO ticket_cliente_config (kunnr, nome_cliente, abilitato, prefisso_referente, promemoria_richiedenti_abilitato, " +
                     " pwd_lunghezza_minima, pwd_min_speciali, pwd_min_numerici, pwd_maiusc_minusc, mfa_obbligatoria, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, " +
                     " COALESCE(?::int, " + PasswordPolicy.DEFAULT_LUNGHEZZA_MINIMA + "), " +
                     " COALESCE(?::int, " + PasswordPolicy.DEFAULT_MIN_SPECIALI + "), " +
                     " COALESCE(?::int, " + PasswordPolicy.DEFAULT_MIN_NUMERICI + "), " +
                     " COALESCE(?::boolean, " + PasswordPolicy.DEFAULT_MAIUSC_MINUSC + "), " +
                     " COALESCE(?::boolean, FALSE), NOW()) " +
                     "ON CONFLICT (kunnr) DO UPDATE SET " +
                     "nome_cliente = EXCLUDED.nome_cliente, abilitato = EXCLUDED.abilitato, " +
                     "prefisso_referente = EXCLUDED.prefisso_referente, " +
                     "promemoria_richiedenti_abilitato = EXCLUDED.promemoria_richiedenti_abilitato, " +
                     "pwd_lunghezza_minima = COALESCE(?::int,     ticket_cliente_config.pwd_lunghezza_minima), " +
                     "pwd_min_speciali     = COALESCE(?::int,     ticket_cliente_config.pwd_min_speciali), " +
                     "pwd_min_numerici     = COALESCE(?::int,     ticket_cliente_config.pwd_min_numerici), " +
                     "pwd_maiusc_minusc    = COALESCE(?::boolean, ticket_cliente_config.pwd_maiusc_minusc), " +
                     "mfa_obbligatoria     = COALESCE(?::boolean, ticket_cliente_config.mfa_obbligatoria), " +
                     "updated_at = NOW()";
        Integer len  = passwordPolicy != null ? passwordPolicy.getLunghezzaMinima()      : null;
        Integer spec = passwordPolicy != null ? passwordPolicy.getMinCaratteriSpeciali() : null;
        Integer num  = passwordPolicy != null ? passwordPolicy.getMinCaratteriNumerici() : null;
        Boolean aA   = passwordPolicy != null ? passwordPolicy.isObbligoMaiuscMinusc()   : null;
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, kunnr);
            ps.setString(2, nomeCliente);
            ps.setBoolean(3, abilitato);
            ps.setString(4, prefisso);
            ps.setBoolean(5, promemoriaRichiedentiAbilitato);
            for (int base : new int[] { 6, 11 }) {
                setIntOrNull (ps, base,     len);
                setIntOrNull (ps, base + 1, spec);
                setIntOrNull (ps, base + 2, num);
                setBoolOrNull(ps, base + 3, aA);
                setBoolOrNull(ps, base + 4, mfaObbligatoria);
            }
            ps.executeUpdate();
        }
        AuditService.log(AuditService.CLIENTE_CONFIG_SALVATO, AuditService.ESITO_OK, kunnr,
            "abilitato=" + abilitato + " prefisso=" + prefisso + " promemoria=" + promemoriaRichiedentiAbilitato +
            (passwordPolicy != null ? " pwdPolicy=" + passwordPolicy.getDescrizioneBreve() : "") +
            (mfaObbligatoria != null ? " mfaObbligatoria=" + mfaObbligatoria : ""));
    }

    /**
     * Policy password da applicare a un utente del Kunnr indicato. Il
     * confronto avviene sul Kunnr normalizzato (senza zeri iniziali),
     * perché ticket_user lo memorizza zero-paddato a 10 cifre mentre in
     * ticket_cliente_config può essere stato inserito senza zeri.
     * Kunnr vuoto/null (AMS, DISPATCHER, ADMIN) o non configurato →
     * {@link PasswordPolicy#defaultPolicy()}.
     */
    public PasswordPolicy getPasswordPolicy(String kunnr) throws SQLException {
        String target = normalizeKunnr(kunnr);
        if (target.isEmpty()) return PasswordPolicy.defaultPolicy();
        String sql = "SELECT kunnr, pwd_lunghezza_minima, pwd_min_speciali, pwd_min_numerici, pwd_maiusc_minusc " +
                     "FROM ticket_cliente_config";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (target.equals(normalizeKunnr(rs.getString("kunnr")))) return mapPolicy(rs);
            }
        }
        return PasswordPolicy.defaultPolicy();
    }

    /** Obbligo MFA per gli utenti del Kunnr indicato (confronto su Kunnr normalizzato). Non configurato → false. */
    public boolean isMfaObbligatoria(String kunnr) throws SQLException {
        String target = normalizeKunnr(kunnr);
        if (target.isEmpty()) return false;
        String sql = "SELECT kunnr, mfa_obbligatoria FROM ticket_cliente_config";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (target.equals(normalizeKunnr(rs.getString("kunnr")))) return rs.getBoolean("mfa_obbligatoria");
            }
        }
        return false;
    }

    private static PasswordPolicy mapPolicy(ResultSet rs) throws SQLException {
        return new PasswordPolicy(rs.getInt("pwd_lunghezza_minima"),
                                  rs.getInt("pwd_min_speciali"),
                                  rs.getInt("pwd_min_numerici"),
                                  rs.getBoolean("pwd_maiusc_minusc"));
    }

    private static void setIntOrNull(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.INTEGER); else ps.setInt(idx, v);
    }

    private static void setBoolOrNull(PreparedStatement ps, int idx, Boolean v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.BOOLEAN); else ps.setBoolean(idx, v);
    }

    /**
     * Prefisso configurato per un cliente, o null se non ancora impostato
     * — usato da ReferentiUI per costruire lo username dei referenti
     * self-service (prefisso_codice) e per bloccare la creazione se manca.
     */
    public String getPrefissoReferente(String kunnr) throws SQLException {
        String sql = "SELECT prefisso_referente FROM ticket_cliente_config WHERE kunnr = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, kunnr);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("prefisso_referente") : null;
            }
        }
    }

    public void delete(String kunnr) throws SQLException {
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM ticket_cliente_config WHERE kunnr = ?")) {
            ps.setString(1, kunnr);
            ps.executeUpdate();
        }
        AuditService.log(AuditService.CLIENTE_CONFIG_RIMOSSO, AuditService.ESITO_OK, kunnr, null);
    }

    /** Stesso criterio di normalizzazione usato altrove nel progetto per il kunnr (toglie zeri iniziali). */
    public static String normalizeKunnr(String kunnr) {
        if (kunnr == null) return "";
        String k = kunnr.trim();
        if (k.matches("\\d+")) {
            try {
                return String.valueOf(Long.parseLong(k));
            } catch (NumberFormatException e) {
                return k;
            }
        }
        return k;
    }
}