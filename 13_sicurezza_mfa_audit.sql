-- =====================================================================
-- 13_sicurezza_mfa_audit.sql
-- Requisiti di sicurezza lato applicazione:
--   - MFA TOTP (segreto cifrato su ticket_user, obbligo per cliente)
--   - blocco temporaneo dopo tentativi di accesso falliti
--   - registro attività (audit log) append-only
-- Idempotente: rieseguibile senza effetti.
--
-- Da eseguire come postgres (owner delle tabelle), connessi a ticketdb:
--   psql -U postgres -d ticketdb -f 13_sicurezza_mfa_audit.sql
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. ticket_user — MFA e blocco tentativi
-- ---------------------------------------------------------------------
ALTER TABLE ticket_user ADD COLUMN IF NOT EXISTS mfa_secret      VARCHAR(200);  -- segreto TOTP cifrato AES-GCM ("E1:...")
ALTER TABLE ticket_user ADD COLUMN IF NOT EXISTS mfa_attivata_il TIMESTAMP;
ALTER TABLE ticket_user ADD COLUMN IF NOT EXISTS mfa_ultimo_step BIGINT;        -- anti-riuso dello stesso codice
ALTER TABLE ticket_user ADD COLUMN IF NOT EXISTS login_falliti   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ticket_user ADD COLUMN IF NOT EXISTS bloccato_fino   TIMESTAMP;

COMMENT ON COLUMN ticket_user.mfa_secret      IS 'Segreto TOTP cifrato (chiave MFA_ENC_KEY fuori dal DB). NULL = MFA non attivata';
COMMENT ON COLUMN ticket_user.mfa_ultimo_step IS 'Ultimo periodo TOTP (30 s) accettato: impedisce il riuso dello stesso codice';
COMMENT ON COLUMN ticket_user.login_falliti   IS 'Tentativi falliti consecutivi (password o codice MFA)';
COMMENT ON COLUMN ticket_user.bloccato_fino   IS 'Se nel futuro, utente temporaneamente bloccato';

-- ---------------------------------------------------------------------
-- 2. ticket_cliente_config — obbligo MFA per cliente
-- ---------------------------------------------------------------------
ALTER TABLE ticket_cliente_config ADD COLUMN IF NOT EXISTS mfa_obbligatoria BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN ticket_cliente_config.mfa_obbligatoria IS 'TRUE = gli utenti del cliente devono usare il secondo fattore (TOTP) al logon';

-- ---------------------------------------------------------------------
-- 3. ticket_audit_log — registro attività append-only
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ticket_audit_log (
    id          BIGSERIAL    PRIMARY KEY,
    ts          TIMESTAMP    NOT NULL DEFAULT NOW(),
    id_user     VARCHAR(40),             -- autore (o username tentato, per i logon falliti)
    kunnr       VARCHAR(10),
    ruolo       VARCHAR(15),
    evento      VARCHAR(40)  NOT NULL,   -- LOGIN_OK, LOGIN_FAIL, MFA_FAIL, PASSWORD_RESET, ALLEGATO_SCARICATO, ...
    esito       VARCHAR(10)  NOT NULL DEFAULT 'OK',
    oggetto     VARCHAR(200),            -- ticket, utente o cliente interessato
    dettaglio   VARCHAR(2000),
    ip_address  VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_audit_ts      ON ticket_audit_log (ts);
CREATE INDEX IF NOT EXISTS idx_audit_user_ts ON ticket_audit_log (id_user, ts);
CREATE INDEX IF NOT EXISTS idx_audit_evento  ON ticket_audit_log (evento, ts);

-- L'utente applicativo può solo scrivere nuove righe e leggerle.
REVOKE ALL ON ticket_audit_log FROM ticket_app;
GRANT SELECT, INSERT ON ticket_audit_log TO ticket_app;
GRANT USAGE, SELECT ON SEQUENCE ticket_audit_log_id_seq TO ticket_app;

-- Protezione aggiuntiva: nessuna modifica/cancellazione, nemmeno dal
-- proprietario, salvo la pulizia per conservazione (funzione sotto).
CREATE OR REPLACE FUNCTION ticket_audit_log_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('ticket.audit_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'ticket_audit_log è append-only: % non consentito', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_append_only ON ticket_audit_log;
CREATE TRIGGER trg_audit_append_only
    BEFORE UPDATE OR DELETE ON ticket_audit_log
    FOR EACH ROW EXECUTE FUNCTION ticket_audit_log_append_only();

-- Pulizia per conservazione: elimina le righe più vecchie di N mesi.
-- Da lanciare SOLO come postgres (es. cron mensile):
--   SELECT ticket_audit_log_purge(13);
CREATE OR REPLACE FUNCTION ticket_audit_log_purge(mesi INTEGER) RETURNS BIGINT AS $$
DECLARE n BIGINT;
BEGIN
    IF mesi < 6 THEN RAISE EXCEPTION 'Conservazione minima 6 mesi'; END IF;
    PERFORM set_config('ticket.audit_purge', 'on', true);
    DELETE FROM ticket_audit_log WHERE ts < NOW() - (mesi * INTERVAL '1 month');
    GET DIAGNOSTICS n = ROW_COUNT;
    PERFORM set_config('ticket.audit_purge', 'off', true);
    RETURN n;
END;
$$ LANGUAGE plpgsql;
REVOKE ALL ON FUNCTION ticket_audit_log_purge(INTEGER) FROM PUBLIC;

COMMENT ON TABLE ticket_audit_log IS 'Registro attività applicativo (append-only). Scritto da eone.ticket.service.AuditService';

COMMIT;

-- Verifica
SELECT column_name, data_type FROM information_schema.columns
 WHERE table_name = 'ticket_user' AND column_name IN ('mfa_secret','mfa_attivata_il','mfa_ultimo_step','login_falliti','bloccato_fino')
 ORDER BY column_name;
SELECT kunnr, nome_cliente, mfa_obbligatoria FROM ticket_cliente_config ORDER BY kunnr;
