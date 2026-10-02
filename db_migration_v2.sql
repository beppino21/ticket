-- =============================================================
-- MIGRATION v2 — ticket_requester: aggiunta colonna ruolo
-- Da eseguire su: ticketdb (PostgreSQL)
-- =============================================================

-- 1. Aggiunge la colonna ruolo (nullable per compatibilità con righe esistenti)
ALTER TABLE ticket_requester
    ADD COLUMN IF NOT EXISTS ruolo VARCHAR(20) NOT NULL DEFAULT 'CLIENTE';

-- Valori previsti:
--   CLIENTE  → utente del cliente: vede i propri ticket, stati limitati
--   AMS      → utente del servizio assistenza: vede tutti, stati completi
--   ADMIN    → amministratore (uso futuro)

COMMENT ON COLUMN ticket_requester.ruolo IS 'Ruolo utente: CLIENTE | AMS | ADMIN';

-- 2. Indice sullo username per il logon (ricerca frequente per reqid + attivo)
CREATE INDEX IF NOT EXISTS idx_requester_logon ON ticket_requester (reqid, attivo);

-- 3. Verifica struttura finale
-- SELECT column_name, data_type, character_maximum_length, column_default, is_nullable
-- FROM information_schema.columns
-- WHERE table_name = 'ticket_requester'
-- ORDER BY ordinal_position;
