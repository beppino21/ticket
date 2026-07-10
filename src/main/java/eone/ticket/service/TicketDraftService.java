package eone.ticket.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import eone.ticket.config.DBConfig;
import eone.ticket.model.TicketDraft;

/**
 * Service per la gestione dei ticket DRAFT (locali, non ancora fusi in SAP).
 */
public class TicketDraftService {

    // =========================
    // CREAZIONE DRAFT
    // =========================

    /**
     * Crea un nuovo ticket DRAFT e restituisce l'id generato.
     * Non salva il commento iniziale — quello va fatto subito dopo
     * con CommentService.saveComment() usando getTicktKey() come tickt.
     */
    public long createDraft(TicketDraft draft) throws SQLException {
        String sql = "INSERT INTO ticket_draft (kunnr, reqid, id_user, titolo, stato) " +
                     "VALUES (?, ?, ?, ?, 'DRAFT') RETURNING id";

        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, draft.getKunnr());
            ps.setString(2, draft.getReqid());
            ps.setString(3, draft.getIdUser());
            ps.setString(4, draft.getTitolo());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    draft.setId(id);
                    System.out.println("[TicketDraftService] Draft creato con id=" + id +
                                       " ticktKey=" + draft.getTicktKey());
                    return id;
                }
                throw new SQLException("Inserimento draft non ha restituito l'id generato");
            }
        }
    }

    // =========================
    // LISTA DRAFT PER CLIENTE
    // =========================

    /** Tutti i DRAFT del richiedente (kunnr+reqid), inclusi i MERGED. */
    public List<TicketDraft> getDraftsByRequester(String kunnr, String reqid) throws SQLException {
        String sql = "SELECT id, kunnr, reqid, id_user, titolo, stato, tickt_sap, created_at, updated_at " +
                     "FROM ticket_draft WHERE kunnr = ? AND reqid = ? " +
                     "ORDER BY created_at DESC";

        return queryList(sql, kunnr, reqid);
    }

    /**
     * Come {@link #getDraftsByRequester}, ma per più reqid contemporaneamente —
     * usato per includere i DRAFT dei colleghi attualmente sostituiti.
     */
    public List<TicketDraft> getDraftsByRequesters(String kunnr, List<String> reqids) throws SQLException {
        if (reqids == null || reqids.isEmpty()) return new ArrayList<>();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < reqids.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }
        String sql = "SELECT id, kunnr, reqid, id_user, titolo, stato, tickt_sap, created_at, updated_at " +
                     "FROM ticket_draft WHERE kunnr = ? AND reqid IN (" + placeholders + ") " +
                     "ORDER BY created_at DESC";
        List<String> params = new ArrayList<>();
        params.add(kunnr);
        params.addAll(reqids);
        return queryList(sql, params.toArray(new String[0]));
    }

    /** Solo i DRAFT in stato DRAFT — per il DISPATCHER. */
    public List<TicketDraft> getPendingDrafts() throws SQLException {
        String sql = "SELECT id, kunnr, reqid, id_user, titolo, stato, tickt_sap, created_at, updated_at " +
                     "FROM ticket_draft WHERE stato = 'DRAFT' " +
                     "ORDER BY created_at ASC"; // ordine cronologico: prima i più vecchi

        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return mapRows(rs);
        }
    }

    // =========================
    // FUSIONE (MERGE) DRAFT -> SAP
    // =========================

    /**
     * Fonde un DRAFT con il ticket SAP appena creato dal DISPATCHER backoffice.
     * 1. Aggiorna ticket_draft: stato=MERGED, tickt_sap=ticktSap
     * 2. Migra i commenti: UPDATE ticket_comment SET tickt=ticktSap WHERE tickt='DRAFT-{id}'
     * Operazione atomica: rollback se uno dei due passi fallisce.
     */
    /**
     * Controlla se il ticket SAP ha già commenti esistenti.
     * Usato da DispatcherUI prima della fusione per avvisare il DISPATCHER.
     * @return numero di commenti esistenti (0 = ticket pulito, >0 = già in uso)
     */
    public int countExistingComments(String ticktSap) throws SQLException {
        String sql = "SELECT COUNT(*) FROM ticket_comment WHERE tickt = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ticktSap.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void mergeDraft(long draftId, String ticktSap) throws SQLException {
        String draftKey = TicketDraft.toDraftTickt(draftId);

        String sqlUpdateDraft = "UPDATE ticket_draft SET stato='MERGED', tickt_sap=?, " +
                                "updated_at=NOW() WHERE id=? AND stato='DRAFT'";
        String sqlMigrateComments = "UPDATE ticket_comment SET tickt=? WHERE tickt=?";

        try (Connection con = DBConfig.getConnection()) {
            con.setAutoCommit(false);
            try {
                // Step 1: chiudi il draft
                try (PreparedStatement ps = con.prepareStatement(sqlUpdateDraft)) {
                    ps.setString(1, ticktSap.trim());
                    ps.setLong  (2, draftId);
                    int rows = ps.executeUpdate();
                    if (rows == 0) {
                        throw new SQLException("Draft id=" + draftId +
                            " non trovato o già fuso (stato non DRAFT)");
                    }
                }

                // Step 2: migra commenti e allegati (gli allegati sono FK su comment_id,
                // quindi si spostano automaticamente con i commenti)
                try (PreparedStatement ps = con.prepareStatement(sqlMigrateComments)) {
                    ps.setString(1, ticktSap.trim());
                    ps.setString(2, draftKey);
                    int commentsMigrati = ps.executeUpdate();
                    System.out.println("[TicketDraftService] Migrati " + commentsMigrati +
                                       " commenti da " + draftKey + " a " + ticktSap);
                }

                con.commit();
                System.out.println("[TicketDraftService] Fusione completata: DRAFT-" +
                                   draftId + " -> SAP " + ticktSap);

            } catch (SQLException e) {
                con.rollback();
                System.err.println("[TicketDraftService] Rollback fusione: " + e.getMessage());
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    // =========================
    // UTILITY
    // =========================

    private List<TicketDraft> queryList(String sql, String... params) throws SQLException {
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return mapRows(rs);
            }
        }
    }

    private List<TicketDraft> mapRows(ResultSet rs) throws SQLException {
        List<TicketDraft> list = new ArrayList<>();
        while (rs.next()) {
            TicketDraft d = new TicketDraft();
            d.setId     (rs.getLong  ("id"));
            d.setKunnr  (rs.getString("kunnr"));
            d.setReqid  (rs.getString("reqid"));
            d.setIdUser (rs.getString("id_user"));
            d.setTitolo (rs.getString("titolo"));
            d.setStato  (rs.getString("stato"));
            d.setTicktSap(rs.getString("tickt_sap"));
            Timestamp cat = rs.getTimestamp("created_at");
            if (cat != null) d.setCreatedAt(cat.toLocalDateTime());
            Timestamp uat = rs.getTimestamp("updated_at");
            if (uat != null) d.setUpdatedAt(uat.toLocalDateTime());
            list.add(d);
        }
        return list;
    }

    /**
     * Raccoglie i warning "sorpassabili" prima della fusione DRAFT → ticket SAP
     * (il dispatcher li vede ma può comunque decidere di procedere):
     *   1. Commenti già esistenti sul ticket SAP
     *   2. Richiedente SAP diverso da quello del DRAFT
     *   3. Data creazione ticket SAP precedente alla creazione del DRAFT
     *
     * Se invece il ticket SAP non esiste (o non è leggibile), NON viene
     * aggiunto alla lista dei warning: viene lanciata TicketSapNotFoundException,
     * perché in quel caso non c'è nulla con cui fondere il DRAFT — non è un
     * giudizio di merito sorpassabile con "conferma".
     */
    public List<String> checkMergeWarnings(long draftId, String ticktSap,
                                           eone.ticket.service.SAPTicketService sapService)
            throws TicketSapNotFoundException, Exception {

        List<String> warnings = new ArrayList<>();

        // Legge il DRAFT
        TicketDraft draft = null;
        String sqlDraft = "SELECT id, kunnr, reqid, id_user, titolo, stato, tickt_sap, " +
                          "created_at, updated_at FROM ticket_draft WHERE id = ?";
        try (Connection con = DBConfig.getConnection();
             PreparedStatement ps = con.prepareStatement(sqlDraft)) {
            ps.setLong(1, draftId);
            try (ResultSet rs = ps.executeQuery()) {
                List<TicketDraft> list = mapRows(rs);
                if (!list.isEmpty()) draft = list.get(0);
            }
        }

        // 1. Commenti esistenti sul ticket SAP
        int commenti = countExistingComments(ticktSap);
        if (commenti > 0) {
            warnings.add("⚠ Il ticket SAP " + ticktSap + " ha già " + commenti +
                         " commento/i — i commenti del DRAFT si aggiungeranno a quelli esistenti.");
        }

        // Legge il ticket SAP per i controlli 2 e 3
        eone.ticket.model.Ticket ticketSap = null;
        try {
            // Se conosciamo già il Kunnr del DRAFT, lo passiamo come suggerimento:
            // restringe la ricerca lato SAP invece di scaricare l'intero elenco
            // ticket del sistema (vedi commento in SAPTicketService.getTicketById).
            String kunnrHint = (draft != null) ? draft.getKunnr() : null;
            ticketSap = sapService.getTicketById(ticktSap, kunnrHint);
        } catch (Exception e) {
            // Non è un giudizio di merito che il dispatcher può ignorare: se non
            // riusciamo a leggerlo, non sappiamo nemmeno se esiste — blocco vero.
            throw new TicketSapNotFoundException(
                "Impossibile leggere il ticket SAP " + ticktSap + " (verificare il numero): " +
                e.getMessage(), e);
        }

        if (ticketSap == null) {
            // Idem: nessun ticket con cui fondere il DRAFT — non è "procedi comunque",
            // altrimenti il DRAFT verrebbe marcato MERGED verso un ticket fantasma.
            throw new TicketSapNotFoundException(
                "Il ticket SAP " + ticktSap + " non è stato trovato nel sistema. " +
                "Verificare che il numero sia corretto.");
        }

        // 2. Richiedente diverso
        if (draft != null) {
            String reqidDraft  = draft.getReqid() != null ? draft.getReqid().trim() : "";
            String reqidSap    = ticketSap.getReqid() != null ? ticketSap.getReqid().trim() : "";
            if (!reqidDraft.isEmpty() && !reqidSap.isEmpty() && !reqidDraft.equalsIgnoreCase(reqidSap)) {
                warnings.add("⚠ Il richiedente del DRAFT (" + reqidDraft + ") è diverso da quello " +
                             "del ticket SAP " + ticktSap + " (" + reqidSap + ").");
            }

            // 3. Data SAP precedente alla creazione del DRAFT
            if (draft.getCreatedAt() != null && ticketSap.getErdat() != null
                    && !ticketSap.getErdat().isEmpty()) {
                try {
                    // getErdat() restituisce "dd/MM/yyyy" dopo la conversione in GridTicketItem,
                    // ma nel Ticket raw arriva come "Date(ms)" o "YYYYMMDD" — usiamo il raw
                    java.time.LocalDate dataSap = parseSapDate(ticketSap.getErdat());
                    java.time.LocalDate dataDraft = draft.getCreatedAt().toLocalDate();
                    if (dataSap != null && dataSap.isBefore(dataDraft)) {
                        warnings.add("⚠ Il ticket SAP " + ticktSap + " è stato creato il " +
                                     dataSap + " — precedente alla creazione del DRAFT (" +
                                     dataDraft + "). Potrebbe trattarsi di un ticket preesistente" +
                                     " non correlato.");
                    }
                } catch (Exception ignored) {
                    // Se non riusciamo a parsare la data, non blocchiamo
                }
            }
        }

        return warnings;
    }

    /** Parsa la data SAP nei formati "Date(ms)" o "YYYYMMDD" */
    private java.time.LocalDate parseSapDate(String erdat) {
        if (erdat == null || erdat.isEmpty()) return null;
        try {
            if (erdat.contains("Date")) {
                long ms = Long.parseLong(erdat.replaceAll("[^0-9]", ""));
                return java.time.Instant.ofEpochMilli(ms)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            }
            if (erdat.length() >= 8) {
                int y = Integer.parseInt(erdat.substring(0, 4));
                int m = Integer.parseInt(erdat.substring(4, 6));
                int d = Integer.parseInt(erdat.substring(6, 8));
                return java.time.LocalDate.of(y, m, d);
            }
        } catch (Exception ignored) {}
        return null;
    }
}