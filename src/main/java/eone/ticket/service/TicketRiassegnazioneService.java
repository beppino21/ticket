package eone.ticket.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import eone.ticket.config.DBConfig;
import eone.ticket.model.TicketRiassegnazione;

/**
 * Service per le richieste AMS di riattribuzione ticket (tabella
 * ticket_riassegnazione).
 *
 * Un solo utente può aprire una richiesta: l'AMS a cui il ticket è
 * attualmente assegnato (Amusr) — verifica lato chiamante (RiassegnazioneUI),
 * qui si controlla solo che non esista già una richiesta APERTA per lo
 * stesso tickt (vincolo anche a livello DB, indice unico parziale).
 *
 * Auto-conclusione: la modifica dell'Amusr avviene sul backend SAP dal
 * DISPATCHER, fuori da quest'app. Quando una schermata rilegge i ticket
 * (lista generale o le schermate di riassegnazione), chiudiSeCambiate()
 * confronta l'Amusr corrente con quello salvato alla richiesta e, se
 * diverso, marca la richiesta CONCLUSA con il nuovo Amusr — a quel punto
 * sparisce sia dalla vista AMS che da quella DISPATCHER, restando visibile
 * solo nello storico.
 */
public class TicketRiassegnazioneService {

    private static final String COLS =
        "id, tickt, amusr_richiedente, testo, stato, nuovo_amusr, created_at, updated_at, concluded_at";

    // =========================
    // LETTURA
    // =========================

    public TicketRiassegnazione getAperta(String tickt) throws SQLException {
        String sql = "SELECT " + COLS + " FROM ticket_riassegnazione WHERE tickt = ? AND stato = 'APERTA'";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, tickt);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public List<TicketRiassegnazione> listAperteByRichiedente(String amusr) throws SQLException {
        return listByStato("APERTA", "amusr_richiedente = ?", amusr);
    }

    public List<TicketRiassegnazione> listAperteAll() throws SQLException {
        return listByStato("APERTA", null, null);
    }

    public List<TicketRiassegnazione> listStoricoByRichiedente(String amusr) throws SQLException {
        return listByStato("CONCLUSA", "amusr_richiedente = ?", amusr);
    }

    public List<TicketRiassegnazione> listStoricoAll() throws SQLException {
        return listByStato("CONCLUSA", null, null);
    }

    private List<TicketRiassegnazione> listByStato(String stato, String extraWhere, String param) throws SQLException {
        List<TicketRiassegnazione> list = new ArrayList<>();
        String sql = "SELECT " + COLS + " FROM ticket_riassegnazione WHERE stato = ? " +
                     (extraWhere != null ? "AND " + extraWhere + " " : "") +
                     "ORDER BY created_at DESC";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, stato);
            if (extraWhere != null) ps.setString(2, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        }
        return list;
    }

    // =========================
    // SCRITTURA
    // =========================

    /**
     * Apre una nuova richiesta di riattribuzione. Fallisce (SQLException,
     * violazione dell'indice unico parziale) se esiste già una richiesta
     * APERTA per lo stesso tickt — non è previsto un messaggio dedicato
     * qui: il chiamante fa già un controllo preventivo con getAperta().
     */
    public void create(String tickt, String amusrRichiedente, String testo) throws SQLException {
        if (tickt == null || tickt.trim().isEmpty())
            throw new IllegalArgumentException("tickt obbligatorio");
        if (amusrRichiedente == null || amusrRichiedente.trim().isEmpty())
            throw new IllegalArgumentException("amusrRichiedente obbligatorio");
        if (testo == null || testo.trim().isEmpty())
            throw new IllegalArgumentException("testo obbligatorio");

        String sql = "INSERT INTO ticket_riassegnazione (tickt, amusr_richiedente, testo, stato, created_at, updated_at) " +
                     "VALUES (?, ?, ?, 'APERTA', NOW(), NOW())";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, tickt.trim());
            ps.setString(2, amusrRichiedente.trim());
            ps.setString(3, testo.trim());
            ps.executeUpdate();
        }
        System.out.println("[TicketRiassegnazioneService] Richiesta riattribuzione aperta su " + tickt +
                            " da '" + amusrRichiedente + "'");
    }

    /**
     * Modifica il testo di una richiesta ancora APERTA — solo il
     * richiedente originale può farlo. Non-op silenzioso (0 righe
     * aggiornate) se la richiesta nel frattempo è stata conclusa o non
     * appartiene più a quell'utente: il chiamante verifica il risultato
     * ricaricando la lista.
     */
    public boolean updateTesto(long id, String amusrRichiedente, String nuovoTesto) throws SQLException {
        if (nuovoTesto == null || nuovoTesto.trim().isEmpty())
            throw new IllegalArgumentException("testo obbligatorio");
        String sql = "UPDATE ticket_riassegnazione SET testo = ?, updated_at = NOW() " +
                     "WHERE id = ? AND amusr_richiedente = ? AND stato = 'APERTA'";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nuovoTesto.trim());
            ps.setLong(2, id);
            ps.setString(3, amusrRichiedente);
            return ps.executeUpdate() > 0;
        }
    }

    /** Cancella una richiesta APERTA — solo il richiedente originale può farlo. */
    public boolean delete(long id, String amusrRichiedente) throws SQLException {
        String sql = "DELETE FROM ticket_riassegnazione WHERE id = ? AND amusr_richiedente = ? AND stato = 'APERTA'";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, amusrRichiedente);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Rifiuto da parte del DISPATCHER — NON cancellazione fisica: la riga
     * resta come storico, marcata CONCLUSA con nuovo_amusr = "DELETED"
     * (vedi TicketRiassegnazione.NUOVO_AMUSR_RIFIUTATA) e concluded_at
     * valorizzato ora. Nessun controllo di ownership (il DISPATCHER non è
     * il richiedente) — solo che sia ancora APERTA.
     *
     * @return la richiesta aggiornata (per poter notificare il richiedente
     *         via mail), o null se non era più APERTA (già conclusa nel frattempo).
     */
    public TicketRiassegnazione rifiuta(long id) throws SQLException {
        String sql = "UPDATE ticket_riassegnazione SET stato = 'CONCLUSA', nuovo_amusr = ?, " +
                     "concluded_at = NOW(), updated_at = NOW() WHERE id = ? AND stato = 'APERTA' " +
                     "RETURNING " + COLS;
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, TicketRiassegnazione.NUOVO_AMUSR_RIFIUTATA);
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    // =========================
    // AUTO-CHIUSURA
    // =========================

    /**
     * Confronta l'Amusr corrente (da SAP) con quello salvato alla
     * richiesta, per tutte le richieste APERTE sui tickt presenti nella
     * mappa. Chiude (CONCLUSA + nuovo_amusr) quelle risultate cambiate.
     * Va richiamato ad ogni caricamento di una lista ticket che include
     * l'Amusr — così la richiesta sparisce da sola sia per l'AMS
     * richiedente sia per il DISPATCHER al prossimo giro di lettura.
     *
     * @param amusrCorrentePerTickt mappa tickt -> Amusr attuale (da SAP)
     * @return numero di richieste chiuse in questa chiamata
     */
    public int chiudiSeCambiate(Map<String, String> amusrCorrentePerTickt) throws SQLException {
        if (amusrCorrentePerTickt == null || amusrCorrentePerTickt.isEmpty()) return 0;

        List<String> tickts = new ArrayList<>(amusrCorrentePerTickt.keySet());
        StringBuilder sb = new StringBuilder(
            "SELECT id, tickt, amusr_richiedente FROM ticket_riassegnazione WHERE stato = 'APERTA' AND tickt IN (");
        for (int i = 0; i < tickts.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("?");
        }
        sb.append(")");

        Map<Long, String> daChiudere = new HashMap<>(); // id -> nuovo amusr
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sb.toString())) {
            for (int i = 0; i < tickts.size(); i++) ps.setString(i + 1, tickts.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tickt = rs.getString("tickt");
                    String amusrRichiesta = rs.getString("amusr_richiedente");
                    String amusrAttuale = amusrCorrentePerTickt.get(tickt);
                    if (amusrAttuale != null && !amusrAttuale.trim().isEmpty() &&
                        !amusrAttuale.trim().equalsIgnoreCase(amusrRichiesta)) {
                        daChiudere.put(rs.getLong("id"), amusrAttuale.trim());
                    }
                }
            }
        }
        if (daChiudere.isEmpty()) return 0;

        String updateSql = "UPDATE ticket_riassegnazione SET stato = 'CONCLUSA', nuovo_amusr = ?, " +
                            "concluded_at = NOW(), updated_at = NOW() WHERE id = ? AND stato = 'APERTA'";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(updateSql)) {
            for (Map.Entry<Long, String> e : daChiudere.entrySet()) {
                ps.setString(1, e.getValue());
                ps.setLong(2, e.getKey());
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            int count = 0;
            for (int r : results) if (r > 0) count++;
            if (count > 0) {
                System.out.println("[TicketRiassegnazioneService] Auto-chiuse " + count + " richieste di riattribuzione (Amusr cambiato)");
            }
            return count;
        }
    }

    /** Comodo per un singolo ticket (es. schermate che non hanno già una mappa bulk pronta). */
    public boolean chiudiSeCambiata(String tickt, String amusrCorrente) throws SQLException {
        if (tickt == null || amusrCorrente == null) return false;
        Map<String, String> m = new HashMap<>();
        m.put(tickt, amusrCorrente);
        return chiudiSeCambiate(m) > 0;
    }

    // =========================
    // UTILITY
    // =========================

    private TicketRiassegnazione mapRow(ResultSet rs) throws SQLException {
        TicketRiassegnazione r = new TicketRiassegnazione();
        r.setId(rs.getLong("id"));
        r.setTickt(rs.getString("tickt"));
        r.setAmusrRichiedente(rs.getString("amusr_richiedente"));
        r.setTesto(rs.getString("testo"));
        r.setStato(rs.getString("stato"));
        r.setNuovoAmusr(rs.getString("nuovo_amusr"));
        Timestamp cat = rs.getTimestamp("created_at");
        if (cat != null) r.setCreatedAt(cat.toLocalDateTime());
        Timestamp uat = rs.getTimestamp("updated_at");
        if (uat != null) r.setUpdatedAt(uat.toLocalDateTime());
        Timestamp conc = rs.getTimestamp("concluded_at");
        if (conc != null) r.setConcludedAt(conc.toLocalDateTime());
        return r;
    }
}