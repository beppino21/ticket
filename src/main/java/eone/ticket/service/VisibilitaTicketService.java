package eone.ticket.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eone.ticket.context.ViewSessionContext;
import eone.ticket.model.Ticket;
import eone.ticket.model.TicketComment;
import eone.ticket.util.IdNormalizer;

/**
 * Regola di visibilità dei ticket SAP per i ruoli lato cliente e per l'AMS.
 *
 * Un ticket che esiste soltanto su SAP (mai passato dall'app: nessun DRAFT,
 * nessun commento) non è visibile a CLIENTE, REFERENTE_CLI, REQ_ADMIN, AMS,
 * AMS_ADMIN. Un ticket SAP è visibile se:
 *   - è CHIUSO (Rstat CLO, RES, CAN, REF) — sempre visibile e conteggiato,
 *     anche senza commenti: lo storico pregresso non si "riprende";
 *   - oppure ha almeno UN commento in ticket_comment — un ticket nato
 *     dall'app lo ha sempre (il primo commento viene salvato insieme al
 *     DRAFT e migrato sul ticket SAP alla fusione).
 * DISPATCHER e ADMIN vedono sempre tutto. I DRAFT non passano di qui: sono
 * record locali, gestiti a parte.
 *
 * Non è una regola di sicurezza ma di opportunità: se la lettura dei commenti
 * fallisce si mostra tutto (fail-open), loggando l'errore.
 */
public class VisibilitaTicketService {

    private static final Set<String> RUOLI_RISTRETTI = new HashSet<>(Arrays.asList(
        "CLIENTE", "REFERENTE_CLI", "REQ_ADMIN", "AMS", "AMS_ADMIN"));

    private static final Set<String> STATI_CHIUSI = new HashSet<>(Arrays.asList("CLO", "RES", "CAN", "REF"));

    private final CommentService commentService = new CommentService();

    public static boolean isChiuso(String rstat) {
        return rstat != null && STATI_CHIUSI.contains(rstat.trim().toUpperCase());
    }

    public static boolean ruoloRistretto(String ruolo) {
        return ruolo != null && RUOLI_RISTRETTI.contains(ruolo.trim().toUpperCase());
    }

    /** True se la regola va applicata all'utente attualmente collegato. */
    public static boolean siApplicaAllUtenteCorrente() {
        return ruoloRistretto(ViewSessionContext.instance().getRuolo());
    }

    /** Chiave di confronto del numero ticket (zeri iniziali irrilevanti). */
    public static String chiave(String tickt) {
        return IdNormalizer.normalize(tickt);
    }

    /** Numeri dei ticket SAP che hanno almeno un commento (chiavi normalizzate); null se lettura fallita. */
    public Set<String> caricaTicktConCommenti() {
        try {
            Set<String> set = new HashSet<>();
            for (TicketComment c : commentService.getLatestStatusPerTicket()) {
                if (c.getTickt() != null) set.add(chiave(c.getTickt()));
            }
            return set;
        } catch (Exception e) {
            System.err.println("[VisibilitaTicketService] Errore lettura commenti, nessun filtro applicato: " + e.getMessage());
            return null;
        }
    }

    public static boolean isVisibile(Ticket t, Set<String> ticktConCommenti) {
        if (ticktConCommenti == null) return true; // fail-open
        if (isChiuso(t.getRstat())) return true;
        return ticktConCommenti.contains(chiave(t.getTickt()));
    }

    /** Applica la regola a una lista (sempre, senza controllo del ruolo). */
    public List<Ticket> filtra(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) return tickets;
        Set<String> conCommenti = caricaTicktConCommenti();
        if (conCommenti == null) return tickets;
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets) {
            if (isVisibile(t, conCommenti)) out.add(t);
        }
        return out;
    }

    /** Applica la regola solo se l'utente collegato appartiene a un ruolo ristretto. */
    public List<Ticket> filtraPerUtenteCorrente(List<Ticket> tickets) {
        return siApplicaAllUtenteCorrente() ? filtra(tickets) : tickets;
    }
}
