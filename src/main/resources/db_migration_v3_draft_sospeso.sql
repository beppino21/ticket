-- =============================================================
-- MIGRATION v3 — ticket_draft: nuovo stato SOSPESO
-- Da eseguire su: ticketdb (PostgreSQL)
--
-- Introduce un terzo stato per ticket_draft, oltre a DRAFT (in attesa
-- di smistamento) e MERGED (fuso nel ticket SAP): SOSPESO, per i DRAFT
-- che il DISPATCHER o il RICHIEDENTE decidono di "parcheggiare" senza
-- convertirli in un ticket SAP. Un DRAFT SOSPESO:
--   - esce dalla lista dei ticket "aperti" e dal conteggio DRAFT
--   - compare tra i ticket "conclusi" (vista Archivio), con colore dedicato
--   - resta reversibile: può tornare DRAFT attivo (riattivazione)
-- Vedi TicketDraft.java / TicketDraftService.java.
-- =============================================================

-- 1. Amplia il CHECK constraint sullo stato per includere SOSPESO.
--    Il nome del constraint è quello di default assegnato da PostgreSQL
--    a un CHECK di colonna senza nome esplicito (<tabella>_<colonna>_check).
--    Se il DROP fallisce con "constraint does not exist", verificare il
--    nome reale con: \d ticket_draft   (sezione "Check constraints")
DO $$
BEGIN
    ALTER TABLE ticket_draft DROP CONSTRAINT IF EXISTS ticket_draft_stato_check;
END $$;

ALTER TABLE ticket_draft
    ADD CONSTRAINT ticket_draft_stato_check CHECK (stato IN ('DRAFT', 'MERGED', 'SOSPESO'));

-- 2. Colonne di audit per la sospensione (chi, quando, perché — opzionale)
ALTER TABLE ticket_draft ADD COLUMN IF NOT EXISTS sospeso_da     VARCHAR(20);
ALTER TABLE ticket_draft ADD COLUMN IF NOT EXISTS sospeso_motivo VARCHAR(500);
ALTER TABLE ticket_draft ADD COLUMN IF NOT EXISTS sospeso_at     TIMESTAMP;

COMMENT ON COLUMN ticket_draft.stato IS 'DRAFT = in attesa di smistamento; MERGED = fuso nel ticket SAP (tickt_sap); SOSPESO = parcheggiato da DISPATCHER o RICHIEDENTE, non convertito in ticket, escluso dai DRAFT attivi';
COMMENT ON COLUMN ticket_draft.sospeso_da IS 'id_user di chi ha sospeso il draft (DISPATCHER o RICHIEDENTE)';
COMMENT ON COLUMN ticket_draft.sospeso_motivo IS 'Motivo libero della sospensione, opzionale';
COMMENT ON COLUMN ticket_draft.sospeso_at IS 'Data/ora della sospensione';

-- 3. Verifica struttura finale
-- \d ticket_draft
