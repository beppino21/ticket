package eone.ticket.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import eone.ticket.config.AppConfig;
import eone.ticket.model.Ticket;
import eone.ticket.model.TicketComment;

/**
 * Sollecito automatico di chiusura: individua i ticket il cui ULTIMO stato
 * (dedotto dall'ultimo commento) è "Richiesta chiusura ticket" o "Ticket
 * risolto" da almeno N giorni, e invia un'unica mail aggregata al
 * backoffice per sollecitarne la chiusura sul backend SAP.
 *
 * Un ticket il cui ultimo stato è "Ticket concluso" (ASS_CONCLUSO, impostato
 * dall'AMS) NON viene mai incluso — per costruzione, dato che il filtro è
 * una lista esplicita dei soli due stati "lato cliente": si aspetta che sia
 * il cliente a spostarlo su uno di quelli prima che scatti il sollecito.
 */
public class SollecitoChiusuraService {

    private final CommentService     commentService = new CommentService();
    private final SAPTicketService   sapService     = new SAPTicketService();
    private final MailService        mailService    = new MailService();

    /** Esegue il controllo e invia la mail aggregata se ci sono ticket da segnalare. */
    public void inviaSollecito() {
        try {
            String email = AppConfig.get("SOLLECITO_CHIUSURA_EMAIL", "helpdesk@eonegroup.it");
            int giorniAttesa;
            try {
                giorniAttesa = Integer.parseInt(AppConfig.get("SOLLECITO_CHIUSURA_GIORNI", "1").trim());
            } catch (NumberFormatException nfe) {
                giorniAttesa = 1;
            }

            List<TicketComment> ultimiStati = commentService.getLatestStatusPerTicket();
            LocalDateTime soglia = LocalDateTime.now().minusDays(giorniAttesa);

            List<TicketComment> candidati = new ArrayList<>();
            for (TicketComment c : ultimiStati) {
                boolean statoDaSollecitare =
                    TicketComment.STATO_CLI_RICHIESTA_CHIUSURA.equals(c.getStatoTicket()) ||
                    TicketComment.STATO_CLI_RISOLTO.equals(c.getStatoTicket());
                if (statoDaSollecitare && c.getCreatedAt() != null && c.getCreatedAt().isBefore(soglia)) {
                    candidati.add(c);
                }
            }

            if (candidati.isEmpty()) {
                System.out.println("[SollecitoChiusuraService] Nessun ticket da sollecitare oggi.");
                return;
            }

            // Verifica incrociata con SAP: un ticket potrebbe essere stato
            // chiuso nel frattempo per un'altra via (non tramite un commento
            // in questa app) — in quel caso non va sollecitato di nuovo.
            Map<String, Ticket> mappaSap = new HashMap<>();
            SAPTicketService.TicketResponse resp = sapService.getTickets(null, null, null, null, null, null);
            if (resp.isSuccess() && resp.getTickets() != null) {
                for (Ticket t : resp.getTickets()) {
                    if (t.getTickt() != null) mappaSap.put(t.getTickt().trim(), t);
                }
            } else {
                System.err.println("[SollecitoChiusuraService] Impossibile leggere lo stato SAP corrente (" +
                    resp.getErrorMessage() + ") — procedo senza verifica incrociata.");
            }

            List<String> righe = new ArrayList<>();
            for (TicketComment c : candidati) {
                Ticket t = mappaSap.get(c.getTickt() != null ? c.getTickt().trim() : "");
                if (t != null && isChiusoInSap(t.getRstat())) {
                    continue; // già chiuso/risolto lato SAP — nessun sollecito necessario
                }
                long giorni = ChronoUnit.DAYS.between(c.getCreatedAt(), LocalDateTime.now());
                String titolo = t != null && t.getTitle() != null ? t.getTitle() : "";
                righe.add(c.getTickt() + " — " + titolo + " — " + c.getStatoTicketLabel() +
                          " da " + giorni + (giorni == 1 ? " giorno" : " giorni"));
            }

            if (righe.isEmpty()) {
                System.out.println("[SollecitoChiusuraService] Tutti i candidati risultano già chiusi/risolti in SAP, nessun sollecito.");
                return;
            }

            mailService.sendSollecitoChiusura(email, righe);
        } catch (Exception e) {
            System.err.println("[SollecitoChiusuraService] Errore invio sollecito: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static boolean isChiusoInSap(String rstat) {
        return "CLO".equalsIgnoreCase(rstat) || "RES".equalsIgnoreCase(rstat);
    }
}