-- =====================================================================
-- 14_mfa_codici_recupero.sql
-- Codici di recupero MFA: 10 codici monouso per utente, salvati solo
-- come hash BCrypt. Usati al posto del codice dell'app quando il
-- telefono non è disponibile. Idempotente.
--
--   psql -U postgres -d ticketdb -f 14_mfa_codici_recupero.sql
-- =====================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS ticket_mfa_recovery (
    id         BIGSERIAL    PRIMARY KEY,
    id_user    VARCHAR(20)  NOT NULL REFERENCES ticket_user(id_user) ON DELETE CASCADE,
    code_hash  VARCHAR(100) NOT NULL,          -- BCrypt del codice normalizzato (8 caratteri, senza trattino)
    creato_il  TIMESTAMP    NOT NULL DEFAULT NOW(),
    usato_il   TIMESTAMP                        -- NULL = ancora utilizzabile
);

CREATE INDEX IF NOT EXISTS idx_mfa_recovery_user ON ticket_mfa_recovery (id_user) WHERE usato_il IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON ticket_mfa_recovery TO ticket_app;
GRANT USAGE, SELECT ON SEQUENCE ticket_mfa_recovery_id_seq TO ticket_app;

COMMENT ON TABLE ticket_mfa_recovery IS 'Codici di recupero MFA monouso (solo hash). Gestiti da eone.ticket.service.MfaService';

COMMIT;
