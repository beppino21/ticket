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
        String sql = "SELECT kunnr, nome_cliente, abilitato, created_at, updated_at " +
                     "FROM ticket_cliente_config ORDER BY kunnr";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ClienteConfig c = new ClienteConfig();
                c.setKunnr(rs.getString("kunnr"));
                c.setNomeCliente(rs.getString("nome_cliente"));
                c.setAbilitato(rs.getBoolean("abilitato"));
                list.add(c);
            }
        }
        return list;
    }

    /** Crea o aggiorna la configurazione di un cliente (upsert su kunnr). */
    public void save(String kunnr, String nomeCliente, boolean abilitato) throws SQLException {
        String sql = "INSERT INTO ticket_cliente_config (kunnr, nome_cliente, abilitato, updated_at) " +
                     "VALUES (?, ?, ?, NOW()) " +
                     "ON CONFLICT (kunnr) DO UPDATE SET " +
                     "nome_cliente = EXCLUDED.nome_cliente, abilitato = EXCLUDED.abilitato, updated_at = NOW()";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, kunnr);
            ps.setString(2, nomeCliente);
            ps.setBoolean(3, abilitato);
            ps.executeUpdate();
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
