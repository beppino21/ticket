package eone.ticket.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eone.ticket.config.AppConfig;
import eone.ticket.model.PromemoriaRiga;
import eone.ticket.model.RequesterInfo;
import eone.ticket.model.Ticket;
import eone.ticket.model.TicketComment;
import eone.ticket.model.TicketDraft;
import eone.ticket.model.TicketRiassegnazione;

/**
 * Promemoria quotidiano dei ticket/attività pendenti — mail personalizzata
 * per ciascun utente AMS (i propri ticket non ancora chiusi) e mail
 * identica a ciascun indirizzo DISPATCHER (DRAFT pendenti + richieste di
 * riattribuzione pendenti). Invocato dallo scheduler (PromemoriaSchedulerListener)
 * tutte le mattine nei giorni feriali, ma può anche essere richiamato a mano
 * (es. per test) — è idempotente: rilegge sempre lo stato corrente.
 *
 * Scala colori fissa (vedi PromemoriaRiga.getColore()):
 *   verde 0-3 giorni, giallo 4-9 giorni, rosso 10+ giorni — calcolati:
 *   - per un ticket SAP: dall'ultima comunicazione scritta dall'AMS su quel
 *     ticket (autore_tipo='ASSISTENZA' in ticket_comment); se non esiste
 *     alcuna comunicazione AMS, dalla data di apertura del ticket (Erdat SAP).
 *   - per un DRAFT: dalla data di inserimento del DRAFT.
 *   - per una richiesta di riattribuzione: dalla data di apertura della richiesta.
 */
public class PromemoriaQuotidianoService {

    private static final Set<String> STATI_CHIUSI = new HashSet<>(java.util.Arrays.asList("CLO", "RES", "CAN"));

    private final SAPTicketService              sapService            = new SAPTicketService();
    private final CommentService                commentService        = new CommentService();
    private final TicketDraftService             draftService          = new TicketDraftService();
    private final TicketRiassegnazioneService    riassegnazioneService = new TicketRiassegnazioneService();
    private final ClienteConfigService           clienteConfigService  = new ClienteConfigService();
    private final RequesterService               requesterService      = new RequesterService();
    private final SubstitutionService            substitutionService   = new SubstitutionService();
    private final MailService                    mailService           = new MailService();

    /** Entry point richiamato dallo scheduler. Le due mail sono indipendenti: se una fallisce, l'altra parte comunque. */
    public void eseguiPromemoriaQuotidiano() {
        if (!AppConfig.getBoolean("PROMEMORIA_QUOTIDIANO_ABILITATO", true)) {
            System.out.println("[PromemoriaQuotidianoService] Disabilitato da configurazione (PROMEMORIA_QUOTIDIANO_ABILITATO=false) — nessun invio.");
            return;
        }
        try {
            inviaPromemoriaAms();
        } catch (Exception e) {
            System.err.println("[PromemoriaQuotidianoService] Errore promemoria AMS: " + e.getMessage());
            e.printStackTrace();
        }
        try {
            inviaPromemoriaDispatcher();
        } catch (Exception e) {
            System.err.println("[PromemoriaQuotidianoService] Errore promemoria DISPATCHER: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================================================
    // AMS — un ticket pendente per riga, una mail per utente AMS
    // =========================================================

    private void inviaPromemoriaAms() throws Exception {
        List<RequesterInfo> amsUsers = requesterService.getActiveAmsUsers();
        if (amsUsers.isEmpty()) {
            System.out.println("[PromemoriaQuotidianoService] Nessun utente AMS attivo con email — nessun promemoria AMS.");
            return;
        }

        SAPTicketService.TicketResponse resp = sapService.getTickets(null, null, null, null, null, null);
        if (!resp.isSuccess()) {
            System.err.println("[PromemoriaQuotidianoService] Impossibile leggere i ticket da SAP (" +
                resp.getErrorMessage() + ") — promemoria AMS saltato.");
            return;
        }

        List<Ticket> pendenti = filtraClientiAbilitatiEPendenti(resp.getTickets());

        // Ultima comunicazione AMS per ticket (data di riferimento per il ritardo)
        Map<String, LocalDate> ultimaComAms = new HashMap<>();
        for (TicketComment c : commentService.getUltimaComunicazioneAmsPerTicket()) {
            if (c.getTickt() != null && c.getCreatedAt() != null) {
                ultimaComAms.put(c.getTickt().trim(), c.getCreatedAt().toLocalDate());
            }
        }
        // Ultimo stato in assoluto per ticket — per rilevare il "Sollecito attività AMS" del cliente
        Map<String, String> ultimoStato = new HashMap<>();
        for (TicketComment c : commentService.getLatestStatusPerTicket()) {
            if (c.getTickt() != null) ultimoStato.put(c.getTickt().trim(), c.getStatoTicket());
        }

        // Raggruppa i ticket pendenti per Amusr (case-insensitive, trim)
        Map<String, List<Ticket>> perAmusr = new HashMap<>();
        for (Ticket t : pendenti) {
            String amusr = t.getAmusr() != null ? t.getAmusr().trim() : "";
            if (amusr.isEmpty()) continue;
            perAmusr.computeIfAbsent(amusr.toUpperCase(), k -> new ArrayList<>()).add(t);
        }

        LocalDate oggi = LocalDate.now();
        int mailInviate = 0;
        for (RequesterInfo ams : amsUsers) {
            List<Ticket> mieiTicket = perAmusr.get(ams.getId_user().trim().toUpperCase());
            if (mieiTicket == null || mieiTicket.isEmpty()) continue; // nessun ticket aperto -> nessuna mail, come richiesto

            List<PromemoriaRiga> righe = new ArrayList<>();
            for (Ticket t : mieiTicket) {
                String tickt = t.getTickt() != null ? t.getTickt().trim() : "";
                LocalDate riferimento = ultimaComAms.get(tickt);
                if (riferimento == null) riferimento = parseSapDate(t.getErdat());
                long giorni = riferimento != null ? ChronoUnit.DAYS.between(riferimento, oggi) : 0;
                boolean sollecito = TicketComment.STATO_CLI_SOLLECITO_ASSISTENZA.equals(ultimoStato.get(tickt));
                righe.add(new PromemoriaRiga(tickt, t.getTitle(), t.getKunnr(), giorni, sollecito,
                    mailService.buildTicketLinkPublic(tickt)));
            }
            righe.sort((a, b) -> Long.compare(b.getGiorni(), a.getGiorni())); // più vecchi in cima

            // Destinatari: il titolare sempre; se oggi è sostituito, anche il sostituto (entrambi informati)
            Set<String> destinatari = new HashSet<>();
            if (ams.getEmail() != null && !ams.getEmail().trim().isEmpty()) destinatari.add(ams.getEmail().trim());
            try {
                String sostituto = substitutionService.getSostitutoAttivo(ams.getId_user());
                if (sostituto != null) {
                    RequesterInfo infoSostituto = requesterService.getById(sostituto);
                    if (infoSostituto != null && infoSostituto.getEmail() != null && !infoSostituto.getEmail().trim().isEmpty()) {
                        destinatari.add(infoSostituto.getEmail().trim());
                    }
                }
            } catch (Exception e) {
                System.err.println("[PromemoriaQuotidianoService] Errore lookup sostituto per " + ams.getId_user() + ": " + e.getMessage());
            }

            for (String email : destinatari) {
                mailService.sendPromemoriaAms(email, ams.getNome() != null ? ams.getNome() : ams.getId_user(), righe);
                mailInviate++;
            }
        }
        System.out.println("[PromemoriaQuotidianoService] Promemoria AMS: " + mailInviate + " mail inviate.");
    }

    // =========================================================
    // DISPATCHER — DRAFT pendenti + richieste di riattribuzione pendenti,
    // stessa mail identica a ciascun indirizzo DISPATCHER
    // =========================================================

    private void inviaPromemoriaDispatcher() throws Exception {
        List<RequesterInfo> dispatchers = requesterService.getActiveDispatchers();
        if (dispatchers.isEmpty()) {
            System.out.println("[PromemoriaQuotidianoService] Nessun DISPATCHER attivo con email — nessun promemoria DISPATCHER.");
            return;
        }

        LocalDate oggi = LocalDate.now();

        List<PromemoriaRiga> righeDraft = new ArrayList<>();
        for (TicketDraft d : draftService.getPendingDrafts()) {
            LocalDate riferimento = d.getCreatedAt() != null ? d.getCreatedAt().toLocalDate() : oggi;
            long giorni = ChronoUnit.DAYS.between(riferimento, oggi);
            righeDraft.add(new PromemoriaRiga(d.getTicktKey(), d.getTitolo(), d.getKunnr(), giorni, false, null));
        }
        righeDraft.sort((a, b) -> Long.compare(b.getGiorni(), a.getGiorni()));

        List<PromemoriaRiga> righeRiassegnazione = new ArrayList<>();
        for (TicketRiassegnazione r : riassegnazioneService.listAperteAll()) {
            LocalDate riferimento = r.getCreatedAt() != null ? r.getCreatedAt().toLocalDate() : oggi;
            long giorni = ChronoUnit.DAYS.between(riferimento, oggi);
            String descrizione = "Richiesta di " + r.getAmusrRichiedente() +
                (r.getTesto() != null && !r.getTesto().trim().isEmpty() ? ": " + tronca(r.getTesto(), 120) : "");
            righeRiassegnazione.add(new PromemoriaRiga(r.getTickt(), descrizione, null, giorni, false,
                mailService.buildTicketLinkPublic(r.getTickt())));
        }
        righeRiassegnazione.sort((a, b) -> Long.compare(b.getGiorni(), a.getGiorni()));

        if (righeDraft.isEmpty() && righeRiassegnazione.isEmpty()) {
            System.out.println("[PromemoriaQuotidianoService] Nessun DRAFT né richiesta di riattribuzione pendente — nessun promemoria DISPATCHER.");
            return;
        }

        int mailInviate = 0;
        for (RequesterInfo dispatcher : dispatchers) {
            mailService.sendPromemoriaDispatcher(dispatcher.getEmail(), righeDraft, righeRiassegnazione);
            mailInviate++;
        }
        System.out.println("[PromemoriaQuotidianoService] Promemoria DISPATCHER: " + mailInviate + " mail inviate ("
            + righeDraft.size() + " draft, " + righeRiassegnazione.size() + " richieste di riattribuzione).");
    }

    // =========================================================
    // UTILITY
    // =========================================================

    private List<Ticket> filtraClientiAbilitatiEPendenti(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) return new ArrayList<>();
        Set<String> abilitati;
        try {
            abilitati = clienteConfigService.getKunnrAbilitati();
        } catch (Exception e) {
            System.err.println("[PromemoriaQuotidianoService] Errore lettura clienti abilitati, procedo senza filtro: " + e.getMessage());
            abilitati = null;
        }
        List<Ticket> risultato = new ArrayList<>();
        for (Ticket t : tickets) {
            String rstat = t.getRstat() != null ? t.getRstat().trim().toUpperCase() : "";
            if (STATI_CHIUSI.contains(rstat)) continue;
            if (abilitati != null && !abilitati.contains(ClienteConfigService.normalizeKunnr(t.getKunnr()))) continue;
            risultato.add(t);
        }
        return risultato;
    }

    private static LocalDate parseSapDate(String erdat) {
        if (erdat == null || erdat.isEmpty()) return null;
        try {
            if (erdat.contains("Date")) {
                long ms = Long.parseLong(erdat.replaceAll("[^0-9]", ""));
                return java.time.Instant.ofEpochMilli(ms)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            }
            if (erdat.length() >= 8) {
                return LocalDate.of(
                    Integer.parseInt(erdat.substring(0, 4)),
                    Integer.parseInt(erdat.substring(4, 6)),
                    Integer.parseInt(erdat.substring(6, 8)));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String tronca(String s, int maxLen) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= maxLen ? t : t.substring(0, maxLen) + "...";
    }
}
