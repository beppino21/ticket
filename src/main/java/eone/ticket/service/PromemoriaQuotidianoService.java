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
 * per ciascun utente AMS (i propri ticket per cui è in attesa un'azione
 * AMS, non semplicemente "non ancora chiusi" — vedi richiedeAzioneAms) e
 * mail identica a ciascun indirizzo DISPATCHER (DRAFT pendenti + richieste
 * di riattribuzione pendenti). Invocato dallo scheduler
 * (PromemoriaSchedulerListener) tutte le mattine nei giorni feriali, ma può
 * anche essere richiamato a mano (es. per test) — è idempotente: rilegge
 * sempre lo stato corrente.
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

    private static final Set<String> STATI_CHIUSI = new HashSet<>(java.util.Arrays.asList("CLO", "RES", "CAN", "REF"));

    private final SAPTicketService              sapService            = new SAPTicketService();
    private final CommentService                commentService        = new CommentService();
    private final TicketDraftService             draftService          = new TicketDraftService();
    private final TicketRiassegnazioneService    riassegnazioneService = new TicketRiassegnazioneService();
    private final TicketReferenteService         referenteService      = new TicketReferenteService();
    private final ClienteConfigService           clienteConfigService  = new ClienteConfigService();
    private final RequesterService               requesterService      = new RequesterService();
    private final SubstitutionService            substitutionService   = new SubstitutionService();
    private final MailService                    mailService           = new MailService();

    /** Entry point richiamato dallo scheduler. Le tre mail sono indipendenti: se una fallisce, le altre partono comunque. */
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
        try {
            inviaPromemoriaRichiedenti();
        } catch (Exception e) {
            System.err.println("[PromemoriaQuotidianoService] Errore promemoria RICHIEDENTI/REFERENTE_CLI: " + e.getMessage());
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
        // Ultimo stato in assoluto per ticket — decide sia se il ticket richiede
        // un'azione AMS (vedi richiedeAzioneAms), sia se evidenziare il "Sollecito
        // attività AMS" del cliente
        Map<String, String> ultimoStato = new HashMap<>();
        for (TicketComment c : commentService.getLatestStatusPerTicket()) {
            if (c.getTickt() != null) ultimoStato.put(c.getTickt().trim(), c.getStatoTicket());
        }

        // Raggruppa per Amusr (case-insensitive, trim) solo i ticket il cui ultimo
        // stato è "in carico" all'AMS, cioè in attesa di una sua azione — vedi
        // richiedeAzioneAms(). Un ticket la cui palla è passata al cliente
        // (ASS_ATTESA_CLIENTE / ASS_SOLLECITO_CLIENTE) non compare nella mail.
        Map<String, List<Ticket>> perAmusr = new HashMap<>();
        for (Ticket t : pendenti) {
            String amusr = t.getAmusr() != null ? t.getAmusr().trim() : "";
            if (amusr.isEmpty()) continue;
            String tickt = t.getTickt() != null ? t.getTickt().trim() : "";
            if (!richiedeAzioneAms(ultimoStato.get(tickt))) continue;
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
    // RICHIEDENTE/REFERENTE_CLI — una mail per reqid con due sezioni:
    // "a tuo carico" (il cliente deve agire) e "in carico al servizio AMS"
    // (informativo, il cliente aspetta una risposta/smistamento) — ordinate
    // per anzianità decrescente. Disabilitabile per singolo cliente dalla
    // schermata "Abilitazione clienti" (ClienteConfigService).
    // =========================================================

    private void inviaPromemoriaRichiedenti() throws Exception {
        List<RequesterInfo> richiedenti = requesterService.getActiveRichiedenti();
        if (richiedenti.isEmpty()) {
            System.out.println("[PromemoriaQuotidianoService] Nessun RICHIEDENTE/REFERENTE_CLI attivo con email — nessun promemoria richiedenti.");
            return;
        }

        Set<String> kunnrAbilitatiPromemoria;
        try {
            kunnrAbilitatiPromemoria = clienteConfigService.getKunnrAbilitatiPromemoriaRichiedenti();
        } catch (Exception e) {
            System.err.println("[PromemoriaQuotidianoService] Errore lettura toggle promemoria richiedenti, nessun invio: " + e.getMessage());
            return;
        }

        // Stato e data dell'ULTIMO commento in assoluto per ciascun ticket SAP
        // (qualunque autore) — un'unica query, da cui ricaviamo sia "a carico
        // di chi" sia la data di riferimento per l'anzianità nel gruppo AMS.
        Map<String, String>    ultimoStato   = new HashMap<>();
        Map<String, LocalDate> ultimaComData = new HashMap<>();
        for (TicketComment c : commentService.getLatestStatusPerTicket()) {
            if (c.getTickt() == null) continue;
            String key = c.getTickt().trim();
            ultimoStato.put(key, c.getStatoTicket());
            if (c.getCreatedAt() != null) ultimaComData.put(key, c.getCreatedAt().toLocalDate());
        }
        // Ultima comunicazione scritta dall'AMS — riferimento per "da quando
        // aspetti una tua risposta" nel gruppo "a tuo carico".
        Map<String, LocalDate> ultimaComAms = new HashMap<>();
        for (TicketComment c : commentService.getUltimaComunicazioneAmsPerTicket()) {
            if (c.getTickt() != null && c.getCreatedAt() != null) {
                ultimaComAms.put(c.getTickt().trim(), c.getCreatedAt().toLocalDate());
            }
        }

        LocalDate oggi = LocalDate.now();
        int mailInviate = 0;
        for (RequesterInfo r : richiedenti) {
            String kunnr = r.getKunnr();
            String reqid = r.getReqid();
            if (kunnr == null || kunnr.trim().isEmpty() || reqid == null || reqid.trim().isEmpty()) continue;
            String kunnrNorm = ClienteConfigService.normalizeKunnr(kunnr);
            if (!kunnrAbilitatiPromemoria.contains(kunnrNorm)) continue; // cliente non abilitato o toggle spento

            try {
                List<PromemoriaRiga> aCaricoSuo = new ArrayList<>();
                List<PromemoriaRiga> aCaricoAms = new ArrayList<>();
                Set<String> ticktVisti = new HashSet<>();

                // --- Ticket SAP propri (Kunnr+Reqid: filtro confermato lato SAP) ---
                SAPTicketService.TicketResponse resp = sapService.getTickets(kunnr, reqid, null, null, null, null);
                if (resp.isSuccess() && resp.getTickets() != null) {
                    for (Ticket t : resp.getTickets()) {
                        String rstat = t.getRstat() != null ? t.getRstat().trim().toUpperCase() : "";
                        if (STATI_CHIUSI.contains(rstat)) continue;
                        String tickt = t.getTickt() != null ? t.getTickt().trim() : "";
                        if (tickt.isEmpty() || !ticktVisti.add(tickt)) continue;
                        classificaRigaTicket(t, tickt, ultimoStato, ultimaComAms, ultimaComData, oggi, aCaricoSuo, aCaricoAms);
                    }
                }

                // --- Ticket SAP dove è referente_cli ma non richiedente diretto ---
                // GUARDIA DI ISOLAMENTO MULTI-TENANT (stessa ragione di
                // TicketListUI.aggiungiTicketDoveReferente): getTicketById()
                // può ripiegare su una ricerca senza filtro Kunnr — va sempre
                // riverificato prima di usarlo.
                for (String tickt : referenteService.getTicktsByReferente(kunnr, reqid)) {
                    if (tickt == null || tickt.startsWith("DRAFT-")) continue;
                    String key = tickt.trim();
                    if (!ticktVisti.add(key)) continue;
                    try {
                        Ticket extra = sapService.getTicketById(key, kunnr);
                        if (extra == null) continue;
                        if (!kunnrNorm.equals(ClienteConfigService.normalizeKunnr(extra.getKunnr()))) {
                            System.err.println("[PromemoriaQuotidianoService] Scartato ticket " + key + " (Kunnr=" + extra.getKunnr() +
                                               ") per reqid=" + reqid + " — non appartiene al cliente atteso (Kunnr=" + kunnr + ")");
                            continue;
                        }
                        String rstat = extra.getRstat() != null ? extra.getRstat().trim().toUpperCase() : "";
                        if (STATI_CHIUSI.contains(rstat)) continue;
                        classificaRigaTicket(extra, key, ultimoStato, ultimaComAms, ultimaComData, oggi, aCaricoSuo, aCaricoAms);
                    } catch (Exception e) {
                        System.err.println("[PromemoriaQuotidianoService] Errore recupero ticket referente " + key +
                                           " (reqid=" + reqid + "): " + e.getMessage());
                    }
                }

                // --- DRAFT pendenti: propri + quelli dove è referente_cli ---
                // (nel gruppo "in carico al servizio": attendono lo smistamento
                // del DISPATCHER, non un'azione del richiedente).
                Set<Long> draftIdsVisti = new HashSet<>();
                for (TicketDraft d : draftService.getDraftsByRequester(kunnr, reqid)) {
                    if (!d.isDraft() || !draftIdsVisti.add(d.getId())) continue;
                    aggiungiRigaDraftPendente(d, oggi, aCaricoAms);
                }
                List<Long> draftIdsReferente = new ArrayList<>();
                for (String tickt : referenteService.getTicktsByReferente(kunnr, reqid)) {
                    if (tickt == null || !tickt.startsWith("DRAFT-")) continue;
                    try {
                        long id = Long.parseLong(tickt.substring("DRAFT-".length()));
                        if (draftIdsVisti.add(id)) draftIdsReferente.add(id);
                    } catch (NumberFormatException ignored) {}
                }
                if (!draftIdsReferente.isEmpty()) {
                    for (TicketDraft d : draftService.getDraftsByIds(draftIdsReferente)) {
                        if (!d.isDraft()) continue;
                        if (!kunnrNorm.equals(ClienteConfigService.normalizeKunnr(d.getKunnr()))) {
                            System.err.println("[PromemoriaQuotidianoService] Scartato DRAFT-" + d.getId() + " (Kunnr=" + d.getKunnr() +
                                               ") per reqid=" + reqid + " — non appartiene al cliente atteso (Kunnr=" + kunnr + ")");
                            continue;
                        }
                        aggiungiRigaDraftPendente(d, oggi, aCaricoAms);
                    }
                }

                if (aCaricoSuo.isEmpty() && aCaricoAms.isEmpty()) continue; // nulla di fermo -> nessuna mail, come per AMS

                aCaricoSuo.sort((a, b) -> Long.compare(b.getGiorni(), a.getGiorni())); // più vecchi in cima
                aCaricoAms.sort((a, b) -> Long.compare(b.getGiorni(), a.getGiorni()));

                String email = r.getEmail();
                if (email == null || email.trim().isEmpty()) continue;
                mailService.sendPromemoriaRichiedente(email.trim(), r.getNomeOReqid(), aCaricoSuo, aCaricoAms);
                mailInviate++;
            } catch (Exception e) {
                System.err.println("[PromemoriaQuotidianoService] Errore promemoria per reqid=" + reqid +
                                   " (kunnr=" + kunnr + "): " + e.getMessage());
            }
        }
        System.out.println("[PromemoriaQuotidianoService] Promemoria RICHIEDENTI/REFERENTE_CLI: " + mailInviate + " mail inviate.");
    }

    /**
     * Classifica un ticket SAP in uno dei due gruppi della mail richiedente,
     * in base all'ultimo stato di conversazione (ticket_comment.stato_ticket):
     *  - ASS_ATTESA_CLIENTE/ASS_SOLLECITO_CLIENTE -> "a tuo carico" (l'AMS ha
     *    risposto, aspetta un'azione del cliente), anzianità dall'ultima
     *    comunicazione AMS.
     *  - richiedeAzioneAms(stato)==true -> "in carico al servizio AMS"
     *    (nessun commento ancora, o il cliente aspetta l'AMS), anzianità
     *    dall'ultimo commento in assoluto (o apertura ticket se nessuno).
     *  - qualunque altro stato (es. ASS_CONCLUSO/CLI_RISOLTO — la
     *    conversazione è di fatto chiusa pur non essendolo ancora lato SAP):
     *    non compare in nessuno dei due gruppi, stesso criterio già usato dal
     *    promemoria AMS per non segnalare come "pendente" ciò che non lo è.
     */
    private void classificaRigaTicket(Ticket t, String tickt, Map<String, String> ultimoStato,
                                       Map<String, LocalDate> ultimaComAms, Map<String, LocalDate> ultimaComData,
                                       LocalDate oggi, List<PromemoriaRiga> aCaricoSuo, List<PromemoriaRiga> aCaricoAms) {
        String stato = ultimoStato.get(tickt);
        boolean inCaricoCliente = TicketComment.STATO_ASS_ATTESA_CLIENTE.equals(stato) ||
                                   TicketComment.STATO_ASS_SOLLECITO_CLIENTE.equals(stato);
        if (inCaricoCliente) {
            LocalDate riferimento = ultimaComAms.get(tickt);
            if (riferimento == null) riferimento = parseSapDate(t.getErdat());
            long giorni = riferimento != null ? ChronoUnit.DAYS.between(riferimento, oggi) : 0;
            aCaricoSuo.add(new PromemoriaRiga(tickt, t.getTitle(), t.getKunnr(), giorni, false,
                mailService.buildTicketLinkPublic(tickt)));
        } else if (richiedeAzioneAms(stato)) {
            LocalDate riferimento = ultimaComData.get(tickt);
            if (riferimento == null) riferimento = parseSapDate(t.getErdat());
            long giorni = riferimento != null ? ChronoUnit.DAYS.between(riferimento, oggi) : 0;
            aCaricoAms.add(new PromemoriaRiga(tickt, t.getTitle(), t.getKunnr(), giorni, false,
                mailService.buildTicketLinkPublic(tickt)));
        }
        // else: conversazione già conclusa (ASS_CONCLUSO/CLI_RISOLTO) — non mostrato
    }

    private void aggiungiRigaDraftPendente(TicketDraft d, LocalDate oggi, List<PromemoriaRiga> aCaricoAms) {
        LocalDate riferimento = d.getCreatedAt() != null ? d.getCreatedAt().toLocalDate() : oggi;
        long giorni = ChronoUnit.DAYS.between(riferimento, oggi);
        aCaricoAms.add(new PromemoriaRiga(d.getTicktKey(), d.getTitolo(), d.getKunnr(), giorni, false, null));
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

    /**
     * Un ticket "richiede un'azione AMS" quando l'ultima mossa nella
     * conversazione è del CLIENTE (la palla è quindi passata all'AMS):
     *  - nessun commento ancora presente (ticket appena aperto, mai preso in carico)
     *  - CLI_ATTESA_ASSISTENZA / CLI_SOLLECITO_ASSISTENZA (il cliente aspetta l'AMS)
     *  - CLI_RICHIESTA_CHIUSURA / CLI_RICHIESTA_CANCELLAZIONE (il cliente aspetta
     *    che l'AMS evada la richiesta)
     * Quando invece l'ultima mossa è dell'ASSISTENZA (ASS_ATTESA_CLIENTE,
     * ASS_SOLLECITO_CLIENTE: l'AMS ha già risposto e aspetta il cliente) o il
     * ticket è già concluso (ASS_CONCLUSO / CLI_RISOLTO), non richiede azione e
     * non compare nel promemoria.
     */
    private boolean richiedeAzioneAms(String ultimoStato) {
        if (ultimoStato == null) return true; // nessuna comunicazione ancora -> va preso in carico
        switch (ultimoStato) {
            case TicketComment.STATO_CLI_ATTESA_ASSISTENZA:
            case TicketComment.STATO_CLI_SOLLECITO_ASSISTENZA:
            case TicketComment.STATO_CLI_RICHIESTA_CHIUSURA:
            case TicketComment.STATO_CLI_RICHIESTA_CANCELLAZIONE:
                return true;
            default:
                return false;
        }
    }

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