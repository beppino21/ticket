package eone.ticket.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eone.ticket.config.DBConfig;
import eone.ticket.context.ViewSessionContext;
import eone.ticket.util.ClientInfo;

/**
 * Registro applicativo degli eventi rilevanti per la sicurezza (audit log).
 *
 * Ogni evento viene scritto in due posti:
 *  - tabella ticket_audit_log (append-only: ticket_app ha solo INSERT/SELECT,
 *    e un trigger impedisce UPDATE/DELETE — vedi 13_sicurezza_*.sql);
 *  - logger SLF4J "AUDIT" (file giornaliero a rotazione, vedi logback.xml),
 *    così l'evento resta anche se il DB non è raggiungibile.
 *
 * La registrazione non deve MAI interrompere l'operazione dell'utente:
 * qualunque errore viene solo segnalato sul log di sistema.
 *
 * Non vengono mai registrate password, codici MFA o contenuti di allegati.
 */
public final class AuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    // --- Codici evento (stringhe stabili: usate anche come filtro in consultazione) ---
    public static final String LOGIN_OK              = "LOGIN_OK";
    public static final String LOGIN_FAIL            = "LOGIN_FAIL";
    public static final String LOGIN_BLOCCATO        = "LOGIN_BLOCCATO";
    public static final String LOGOUT                = "LOGOUT";
    public static final String MFA_ATTIVATA          = "MFA_ATTIVATA";
    public static final String MFA_FAIL              = "MFA_FAIL";
    public static final String MFA_AZZERATA          = "MFA_AZZERATA";
    public static final String UTENTE_SBLOCCATO      = "UTENTE_SBLOCCATO";
    public static final String ACCESSO_NEGATO        = "ACCESSO_NEGATO";
    public static final String SESSIONE_SOSTITUITA   = "SESSIONE_SOSTITUITA";
    public static final String MFA_CODICI_GENERATI   = "MFA_CODICI_GENERATI";
    public static final String MFA_RECUPERO_USATO    = "MFA_RECUPERO_USATO";
    public static final String MFA_CODICI_REVOCATI   = "MFA_CODICI_REVOCATI";
    public static final String PASSWORD_CAMBIATA     = "PASSWORD_CAMBIATA";
    public static final String PASSWORD_CAMBIO_FAIL  = "PASSWORD_CAMBIO_FAIL";
    public static final String PASSWORD_RESET        = "PASSWORD_RESET";
    public static final String UTENTE_CREATO         = "UTENTE_CREATO";
    public static final String UTENTE_MODIFICATO     = "UTENTE_MODIFICATO";
    public static final String UTENTE_ELIMINATO      = "UTENTE_ELIMINATO";
    public static final String CLIENTE_CONFIG_SALVATO   = "CLIENTE_CONFIG_SALVATO";
    public static final String CLIENTE_CONFIG_RIMOSSO   = "CLIENTE_CONFIG_RIMOSSO";
    public static final String COMMENTO_INSERITO     = "COMMENTO_INSERITO";
    public static final String TICKET_APERTO         = "TICKET_APERTO";
    public static final String ALLEGATO_CARICATO     = "ALLEGATO_CARICATO";
    public static final String ALLEGATO_RIFIUTATO    = "ALLEGATO_RIFIUTATO";
    public static final String ALLEGATO_SCARICATO    = "ALLEGATO_SCARICATO";

    public static final String ESITO_OK   = "OK";
    public static final String ESITO_FAIL = "FAIL";

    private AuditService() { }

    /** Evento dell'utente in sessione (id, kunnr e ruolo presi da ViewSessionContext). */
    public static void log(String evento, String esito, String oggetto, String dettaglio) {
        String idUser = null, kunnr = null, ruolo = null;
        try {
            ViewSessionContext ctx = ViewSessionContext.instance();
            idUser = ctx.getUsername();
            kunnr  = ctx.getKunnr();
            ruolo  = ctx.getRuolo();
        } catch (Throwable t) {
            // fuori da una sessione CC (scheduler): si registra senza utente
        }
        log(idUser, kunnr, ruolo, evento, esito, oggetto, dettaglio);
    }

    /** Evento con utente esplicito — usato al logon, quando la sessione non è ancora popolata. */
    public static void log(String idUser, String kunnr, String ruolo, String evento, String esito,
                           String oggetto, String dettaglio) {
        String ip = ClientInfo.getClientIp();
        String e  = esito != null ? esito : ESITO_OK;

        try {
            AUDIT.info("evento={} esito={} utente={} kunnr={} ruolo={} ip={} oggetto={} dettaglio={}",
                       evento, e, sanitize(idUser), sanitize(kunnr), sanitize(ruolo), sanitize(ip),
                       sanitize(oggetto), sanitize(dettaglio));
        } catch (Throwable t) {
            System.err.println("[AuditService] Errore scrittura log file: " + t.getMessage());
        }

        String sql = "INSERT INTO ticket_audit_log (id_user, kunnr, ruolo, evento, esito, oggetto, dettaglio, ip_address) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, cut(idUser, 40));
            ps.setString(2, cut(kunnr, 10));
            ps.setString(3, cut(ruolo, 15));
            ps.setString(4, cut(evento, 40));
            ps.setString(5, cut(e, 10));
            ps.setString(6, cut(oggetto, 200));
            ps.setString(7, cut(dettaglio, 2000));
            ps.setString(8, cut(ip, 100));
            ps.executeUpdate();
        } catch (Throwable t) {
            System.err.println("[AuditService] ❌ Errore scrittura ticket_audit_log (" + evento + "): " + t.getMessage());
        }
    }

    // =========================================================
    // CONSULTAZIONE (schermata "Registro attività", solo ADMIN)
    // =========================================================

    public static class Voce {
        public LocalDateTime ts;
        public String idUser, kunnr, ruolo, evento, esito, oggetto, dettaglio, ip;
    }

    /**
     * Ultime voci del registro, filtrate (filtri vuoti = nessun filtro).
     * Il filtro utente/oggetto è "contiene", case-insensitive.
     */
    public static List<Voce> cerca(String filtroUtente, String filtroEvento, String filtroOggetto,
                                   LocalDateTime da, LocalDateTime a, int maxRighe) throws SQLException {
        StringBuilder sql = new StringBuilder(
            "SELECT ts, id_user, kunnr, ruolo, evento, esito, oggetto, dettaglio, ip_address " +
            "FROM ticket_audit_log WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (notEmpty(filtroUtente))  { sql.append(" AND id_user ILIKE ?"); params.add("%" + filtroUtente.trim() + "%"); }
        if (notEmpty(filtroEvento))  { sql.append(" AND evento = ?");      params.add(filtroEvento.trim()); }
        if (notEmpty(filtroOggetto)) { sql.append(" AND oggetto ILIKE ?"); params.add("%" + filtroOggetto.trim() + "%"); }
        if (da != null)              { sql.append(" AND ts >= ?");         params.add(Timestamp.valueOf(da)); }
        if (a != null)               { sql.append(" AND ts < ?");          params.add(Timestamp.valueOf(a)); }
        sql.append(" ORDER BY ts DESC, id DESC LIMIT ?");
        params.add(maxRighe);

        List<Voce> list = new ArrayList<>();
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Voce v = new Voce();
                    Timestamp t = rs.getTimestamp("ts");
                    v.ts        = t != null ? t.toLocalDateTime() : null;
                    v.idUser    = rs.getString("id_user");
                    v.kunnr     = rs.getString("kunnr");
                    v.ruolo     = rs.getString("ruolo");
                    v.evento    = rs.getString("evento");
                    v.esito     = rs.getString("esito");
                    v.oggetto   = rs.getString("oggetto");
                    v.dettaglio = rs.getString("dettaglio");
                    v.ip        = rs.getString("ip_address");
                    list.add(v);
                }
            }
        }
        return list;
    }

    /** Tutti i codici evento, per la combobox di filtro. */
    public static String[] codiciEvento() {
        return new String[] {
            LOGIN_OK, LOGIN_FAIL, LOGIN_BLOCCATO, UTENTE_SBLOCCATO, ACCESSO_NEGATO, SESSIONE_SOSTITUITA, LOGOUT, MFA_ATTIVATA, MFA_FAIL, MFA_AZZERATA,
            MFA_CODICI_GENERATI, MFA_RECUPERO_USATO, MFA_CODICI_REVOCATI,
            PASSWORD_CAMBIATA, PASSWORD_CAMBIO_FAIL, PASSWORD_RESET,
            UTENTE_CREATO, UTENTE_MODIFICATO, UTENTE_ELIMINATO,
            CLIENTE_CONFIG_SALVATO, CLIENTE_CONFIG_RIMOSSO,
            TICKET_APERTO, COMMENTO_INSERITO,
            ALLEGATO_CARICATO, ALLEGATO_RIFIUTATO, ALLEGATO_SCARICATO
        };
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }
    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
    /** Evita che un dettaglio con a-capo spezzi la riga del file di log (log injection). */
    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "-";
        return s.replace('\r', ' ').replace('\n', ' ');
    }
}
