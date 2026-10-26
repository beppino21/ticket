-- =====================================================================
-- 15_username_case_insensitive.sql
-- Gli username (ticket_user.id_user) non distinguono maiuscole e
-- minuscole: "MRossi" e "mrossi" sono lo stesso utente al login.
-- L'indice univoco su LOWER(id_user) impedisce di creare due utenze che
-- differiscono solo per le maiuscole e rende veloce la ricerca usata da
-- RequesterService.authenticate (WHERE LOWER(id_user) = LOWER(?)).
-- Lo username resta memorizzato come è stato scritto. La password NON
-- cambia: resta sensibile a maiuscole e minuscole.
-- Idempotente. Da eseguire con l'utente proprietario delle tabelle:
--
--   psql -U postgres -d ticketdb -f 15_username_case_insensitive.sql
-- =====================================================================

BEGIN;

-- Controllo preventivo: se esistono già username uguali a meno delle
-- maiuscole, lo script si ferma senza modificare nulla.
DO $$
DECLARE
    doppi TEXT;
BEGIN
    SELECT string_agg(utenze, '; ') INTO doppi
    FROM (SELECT string_agg(id_user, ', ') AS utenze
          FROM ticket_user
          GROUP BY LOWER(id_user)
          HAVING COUNT(*) > 1) d;
    IF doppi IS NOT NULL THEN
        RAISE EXCEPTION 'Username che differiscono solo per maiuscole/minuscole: %. Risolvere prima di creare l''indice.', doppi;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_ticket_user_id_user_lower ON ticket_user (LOWER(id_user));

COMMENT ON INDEX ux_ticket_user_id_user_lower IS 'Username univoci senza distinzione di maiuscole/minuscole (login case-insensitive)';

COMMIT;
