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
        String sql = "SELECT kunnr, nome_cliente, abilitato, prefisso_referente, created_at, updated_at " +
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
                list.add(c);
            }
        }
        return list;
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

        String sql = "INSERT INTO ticket_cliente_config (kunnr, nome_cliente, abilitato, prefisso_referente, updated_at) " +
                     "VALUES (?, ?, ?, ?, NOW()) " +
                     "ON CONFLICT (kunnr) DO UPDATE SET " +
                     "nome_cliente = EXCLUDED.nome_cliente, abilitato = EXCLUDED.abilitato, " +
                     "prefisso_referente = EXCLUDED.prefisso_referente, updated_at = NOW()";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, kunnr);
            ps.setString(2, nomeCliente);
            ps.setBoolean(3, abilitato);
            ps.setString(4, prefisso);
            ps.executeUpdate();
        }
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