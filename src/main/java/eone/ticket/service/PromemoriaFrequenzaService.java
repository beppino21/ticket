package eone.ticket.service;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import eone.ticket.config.DBConfig;

/**
 * Frequenza personale del promemoria (ticket_user.promemoria_ogni_giorni) e
 * data dell'ultimo invio effettivo (ticket_user.promemoria_ultimo_invio).
 *
 * Semantica del valore "ogni N giorni":
 *   1  = a ogni esecuzione dello scheduler, cioè ogni giorno feriale (default,
 *        comportamento storico)
 *   N>1 = solo se l'ultimo invio risale ad almeno N giorni di calendario fa
 *        (o non c'è mai stato un invio)
 *   0  = mai (l'utente non vuole il promemoria)
 *
 * Lo scheduler gira solo lun-ven: con N=7 il promemoria arriva quindi
 * una volta a settimana, nello stesso giorno della settimana.
 *
 * Tutto "best effort": se la lettura fallisce si ricade sul comportamento
 * storico (invio), se la scrittura della data fallisce si registra solo l'errore.
 */
public class PromemoriaFrequenzaService {

    public static final int MAX_GIORNI = 365;

    private static final class Voce {
        final int giorni;
        final LocalDate ultimoInvio;
        Voce(int giorni, LocalDate ultimoInvio) { this.giorni = giorni; this.ultimoInvio = ultimoInvio; }
    }

    /** Converte il testo del campo in giorni (0-365); -1 se non valido. */
    public static int parseGiorni(String testo) {
        try {
            int v = Integer.parseInt(testo == null ? "" : testo.trim());
            return (v >= 0 && v <= MAX_GIORNI) ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Riga per la tabella della pagina admin "Promemoria". */
    public static final class Destinatario implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String idUser, nome, ruolo, email;
        public final int giorni;
        public final LocalDate ultimoInvio;
        Destinatario(String idUser, String nome, String ruolo, String email, int giorni, LocalDate ultimoInvio) {
            this.idUser = idUser; this.nome = nome; this.ruolo = ruolo; this.email = email;
            this.giorni = giorni; this.ultimoInvio = ultimoInvio;
        }
        /** Prima data (inclusa) in cui il promemoria torna dovuto; null se mai inviato o se giorni<=1. */
        public LocalDate prossimoDovuto() {
            return (giorni > 1 && ultimoInvio != null) ? ultimoInvio.plusDays(giorni) : null;
        }
        public boolean dovutoOggi(LocalDate oggi) {
            if (giorni <= 0) return false;
            LocalDate p = prossimoDovuto();
            return p == null || !oggi.isBefore(p);
        }
    }

    /** Utenti attivi con email che possono ricevere un promemoria (AMS, DISPATCHER, CLIENTE, REFERENTE_CLI). */
    public List<Destinatario> elenco() throws java.sql.SQLException {
        String sql = "SELECT id_user, nome, ruolo, email, promemoria_ogni_giorni, promemoria_ultimo_invio " +
                     "FROM ticket_user WHERE attivo = TRUE AND email IS NOT NULL AND email <> '' " +
                     "AND ruolo IN ('AMS','DISPATCHER','CLIENTE','REFERENTE_CLI') " +
                     "ORDER BY CASE ruolo WHEN 'AMS' THEN 1 WHEN 'DISPATCHER' THEN 2 WHEN 'CLIENTE' THEN 3 ELSE 4 END, nome";
        List<Destinatario> list = new ArrayList<>();
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Date d = rs.getDate("promemoria_ultimo_invio");
                list.add(new Destinatario(rs.getString("id_user"), rs.getString("nome"), rs.getString("ruolo"),
                    rs.getString("email"), rs.getInt("promemoria_ogni_giorni"), d != null ? d.toLocalDate() : null));
            }
        }
        return list;
    }

    private final Map<String, Voce> cache = new HashMap<>();

    /** Carica in un colpo solo frequenza e ultimo invio di tutti gli utenti (da chiamare a inizio esecuzione). */
    public void carica() {
        cache.clear();
        String sql = "SELECT id_user, promemoria_ogni_giorni, promemoria_ultimo_invio FROM ticket_user";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Date d = rs.getDate("promemoria_ultimo_invio");
                cache.put(rs.getString("id_user"),
                    new Voce(rs.getInt("promemoria_ogni_giorni"), d != null ? d.toLocalDate() : null));
            }
        } catch (Exception e) {
            System.err.println("[PromemoriaFrequenzaService] Errore lettura frequenze (si usa l'invio giornaliero): " + e.getMessage());
            cache.clear();
        }
    }

    /**
     * @param forza true per l'invio manuale ("Invia ora"): ignora l'intervallo
     *              ma rispetta comunque 0 = mai.
     */
    public boolean isDovuto(String idUser, LocalDate oggi, boolean forza) {
        Voce v = cache.get(idUser);
        if (v == null) return true;              // sconosciuto -> comportamento storico
        if (v.giorni <= 0) return false;          // 0 = non desidera il promemoria
        if (forza || v.giorni == 1) return true;
        if (v.ultimoInvio == null) return true;
        return ChronoUnit.DAYS.between(v.ultimoInvio, oggi) >= v.giorni;
    }

    /** Da chiamare solo dopo un invio realmente riuscito. */
    public void registraInvio(String idUser, LocalDate oggi) {
        Voce v = cache.get(idUser);
        cache.put(idUser, new Voce(v != null ? v.giorni : 1, oggi));
        String sql = "UPDATE ticket_user SET promemoria_ultimo_invio = ? WHERE id_user = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(oggi));
            ps.setString(2, idUser);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[PromemoriaFrequenzaService] Errore registrazione ultimo invio per " + idUser + ": " + e.getMessage());
        }
    }
}
