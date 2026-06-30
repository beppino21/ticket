package eone.ticket.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import eone.ticket.config.DBConfig;
import eone.ticket.model.TicketStatoInfo;

/**
 * Service di transcodifica per gli stati ticket SAP (rstat).
 * Carica l'intera tabella ticket_stato_rstat in cache statica al primo
 * utilizzo — sono pochi record (13), cambiano raramente, e vengono
 * consultati per ogni riga della lista ticket: la cache evita query ripetute.
 */
public class TicketStatoService {

    private static Map<String, TicketStatoInfo> cache;

    /** Restituisce le info di transcodifica per un rstat, o un fallback neutro se sconosciuto. */
    public TicketStatoInfo getStatoInfo(String rstat) {
        if (rstat == null) return fallback("");
        ensureCacheLoaded();
        String key = rstat.trim().toUpperCase();
        TicketStatoInfo info = cache.get(key);
        if (info == null) {
            System.err.println("[TicketStatoService] Stato non transcodificato: '" + rstat +
                               "' (cache contiene " + cache.size() + " stati)");
            return fallback(rstat);
        }
        return info;
    }

    /** Forza il ricaricamento della cache (es. dopo modifica manuale della tabella). */
    public static synchronized void invalidateCache() {
        cache = null;
    }

    private synchronized void ensureCacheLoaded() {
        if (cache != null) return;
        Map<String, TicketStatoInfo> loaded = new HashMap<>();
        String sql = "SELECT rstat, descrizione, descrizione_corta, ordine, colore, colore_testo " +
                     "FROM ticket_stato_rstat";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                TicketStatoInfo info = new TicketStatoInfo();
                info.setRstat           (rs.getString("rstat"));
                info.setDescrizione     (rs.getString("descrizione"));
                info.setDescrizioneCorta(rs.getString("descrizione_corta"));
                info.setOrdine          (rs.getInt("ordine"));
                info.setColore          (rs.getString("colore"));
                info.setColoreTesto     (rs.getString("colore_testo"));
                loaded.put(info.getRstat(), info);
            }
            System.out.println("[TicketStatoService] Cache caricata: " + loaded.size() + " stati");

        } catch (Exception e) {
            System.err.println("[TicketStatoService] Errore caricamento cache stati: " + e.getMessage());
            e.printStackTrace();
        }
        cache = loaded;
    }

    private TicketStatoInfo fallback(String rstat) {
        TicketStatoInfo info = new TicketStatoInfo();
        info.setRstat(rstat != null ? rstat : "");
        info.setDescrizione(rstat != null ? rstat : "");
        info.setDescrizioneCorta(rstat != null ? rstat : "");
        info.setOrdine(999);
        info.setColore("#CCCCCC");
        info.setColoreTesto("#000000");
        return info;
    }
}