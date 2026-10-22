-- =====================================================================
-- 12_password_policy_cliente.sql
-- Policy di composizione password per cliente (Kunnr) su
-- ticket_cliente_config. Idempotente: rieseguibile senza effetti.
--
-- I clienti già presenti ricevono automaticamente i default
-- (DEFAULT delle nuove colonne NOT NULL):
--   lunghezza minima 8, speciali 0, numerici 0, maiusc/minusc FALSE
-- = comportamento attuale, nessun utente esistente viene penalizzato.
--
-- Eseguire connessi a ticketdb:  psql -U postgres -d ticketdb -f 12_password_policy_cliente.sql
-- =====================================================================

BEGIN;

ALTER TABLE ticket_cliente_config ADD COLUMN IF NOT EXISTS pwd_lunghezza_minima INTEGER NOT NULL DEFAULT 8;
ALTER TABLE ticket_cliente_config ADD COLUMN IF NOT EXISTS pwd_min_speciali     INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ticket_cliente_config ADD COLUMN IF NOT EXISTS pwd_min_numerici     INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ticket_cliente_config ADD COLUMN IF NOT EXISTS pwd_maiusc_minusc    BOOLEAN NOT NULL DEFAULT FALSE;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_cliente_config_pwd_policy') THEN
        ALTER TABLE ticket_cliente_config ADD CONSTRAINT ck_cliente_config_pwd_policy
            CHECK (pwd_lunghezza_minima BETWEEN 6 AND 64
               AND pwd_min_speciali     BETWEEN 0 AND 20
               AND pwd_min_numerici     BETWEEN 0 AND 20);
    END IF;
END $$;

COMMENT ON COLUMN ticket_cliente_config.pwd_lunghezza_minima IS 'Policy password utenti del cliente: lunghezza minima (default 8)';
COMMENT ON COLUMN ticket_cliente_config.pwd_min_speciali     IS 'Policy password utenti del cliente: n. minimo caratteri speciali (non lettera/cifra/spazio)';
COMMENT ON COLUMN ticket_cliente_config.pwd_min_numerici     IS 'Policy password utenti del cliente: n. minimo cifre';
COMMENT ON COLUMN ticket_cliente_config.pwd_maiusc_minusc    IS 'Policy password utenti del cliente: TRUE = almeno una maiuscola e una minuscola';

COMMIT;

-- Verifica
SELECT kunnr, nome_cliente, abilitato,
       pwd_lunghezza_minima, pwd_min_speciali, pwd_min_numerici, pwd_maiusc_minusc
  FROM ticket_cliente_config
 ORDER BY kunnr;
