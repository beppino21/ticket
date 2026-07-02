-- =====================================================================
-- RICOSTRUZIONE COMPLETA ticketdb — Lamplast/e-One Ticketing App
-- Consolidato da: 01_create_tables.sql, 02_ticket_requester.sql (evoluto
-- in ticket_user), 04_ticket_stato_transcodifica.sql, 06_rimuovi_not_
-- null_kunnr_reqid.sql, 08_ticket_draft.sql, 09_update_colori_stati.sql
-- (versione finale pastello)
--
-- LEGENDA AFFIDABILITA':
--   [CONFERMATO]     = ritrovato verbatim in chat passate, generato da Claude
--   [RICOSTRUITO]    = dedotto dall'uso nel codice Java, MAI generato/
--                       verificato come DDL — verificare prima di eseguire
--
-- Esegui in ordine con: psql -U postgres -f 00_ticketdb_full_rebuild.sql
-- =====================================================================


-- =====================================================================
-- SEZIONE 0 — DB e utente applicativo                          [CONFERMATO]
-- =====================================================================
-- Da eseguire come superuser (postgres), fuori da una sessione già
-- connessa a ticketdb:

CREATE USER ticket_app WITH PASSWORD 'changeme';
CREATE DATABASE ticketdb OWNER ticket_app;

-- Da qui in poi connettersi a ticketdb:
-- \c ticketdb

GRANT ALL ON SCHEMA public TO ticket_app;


-- =====================================================================
-- SEZIONE 1 — ticket_comment                                    [CONFERMATO]
-- =====================================================================

CREATE TABLE IF NOT EXISTS ticket_comment (
    id            BIGSERIAL    PRIMARY KEY,
    tickt         VARCHAR(20)  NOT NULL,
    kunnr         VARCHAR(10),
    autore_tipo   VARCHAR(10)  NOT NULL CHECK (autore_tipo IN ('CLIENTE','ASSISTENZA')),
    autore_id     VARCHAR(40)  NOT NULL,
    testo         TEXT         NOT NULL,
    stato_ticket  VARCHAR(30)  NOT NULL,   -- ampliato da VARCHAR(15) per i 7 nuovi codici (es. CLI_SOLLECITO_ASSISTENZA)
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_comment_tickt   ON ticket_comment(tickt);
CREATE INDEX IF NOT EXISTS idx_comment_created ON ticket_comment(tickt, created_at);

COMMENT ON TABLE ticket_comment IS 'Commenti ai ticket SAP, inseriti da cliente o assistenza';
COMMENT ON COLUMN ticket_comment.tickt        IS 'Numero ticket SAP (es. 0000001234), oppure DRAFT-{id} per i ticket non ancora fusi';
COMMENT ON COLUMN ticket_comment.kunnr        IS 'Codice cliente SAP (valorizzato se autore_tipo=CLIENTE)';
COMMENT ON COLUMN ticket_comment.autore_tipo  IS 'CLIENTE o ASSISTENZA';
COMMENT ON COLUMN ticket_comment.autore_id    IS 'Username utente loggato (id_user)';
COMMENT ON COLUMN ticket_comment.stato_ticket IS 'Uno dei 7 codici stato commento — vedi sezione 5';


-- =====================================================================
-- SEZIONE 2 — ticket_attachment (1:N con ticket_comment)         [CONFERMATO]
-- =====================================================================

CREATE TABLE IF NOT EXISTS ticket_attachment (
    id            BIGSERIAL    PRIMARY KEY,
    comment_id    BIGINT       NOT NULL REFERENCES ticket_comment(id) ON DELETE CASCADE,
    filename      VARCHAR(255) NOT NULL,
    mime_type     VARCHAR(100),
    file_size     BIGINT,
    file_data     BYTEA        NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_attach_comment ON ticket_attachment(comment_id);

COMMENT ON TABLE ticket_attachment IS 'Allegati binari ai commenti ticket (BYTEA)';
COMMENT ON COLUMN ticket_attachment.file_data IS 'Contenuto binario del file — caricare solo su richiesta (download)';

GRANT SELECT, INSERT, UPDATE, DELETE ON ticket_comment    TO ticket_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ticket_attachment TO ticket_app;
GRANT USAGE, SELECT ON SEQUENCE ticket_comment_id_seq    TO ticket_app;
GRANT USAGE, SELECT ON SEQUENCE ticket_attachment_id_seq TO ticket_app;


-- =====================================================================
-- SEZIONE 3 — ticket_user                                       [RICOSTRUITO]
-- Questa tabella l'hai creata TU manualmente in sostituzione di
-- ticket_requester ("ho creato la tabella ticket_user e eliminato
-- ticket_requester"). Non ho mai generato/visto il DDL originale.
-- Le colonne sotto sono dedotte dalle query in RequesterService.java:
--   SELECT id_user, kunnr, reqid, nome, email, password_hash, ruolo,
--          vede_tutti, attivo FROM ticket_user WHERE ...
-- Ruoli confermati in uso: CLIENTE, AMS, ADMIN, DISPATCHER
-- kunnr/reqid nullable (per AMS/ADMIN) dopo la migrazione 06.
-- VERIFICA QUESTA SEZIONE PRIMA DI ESEGUIRLA — è la meno affidabile.
-- =====================================================================

CREATE TABLE IF NOT EXISTS ticket_user (
    id_user       VARCHAR(20)   PRIMARY KEY,     -- assegnato dal backoffice, è anche lo username di login
    kunnr         VARCHAR(10),                   -- NULL per AMS/ADMIN/DISPATCHER
    reqid         VARCHAR(40),                   -- NULL per AMS/ADMIN/DISPATCHER
    nome          VARCHAR(100),
    email         VARCHAR(150),
    password_hash VARCHAR(255)  NOT NULL,        -- hash BCrypt (jbcrypt)
    ruolo         VARCHAR(15)   NOT NULL CHECK (ruolo IN ('CLIENTE','AMS','ADMIN','DISPATCHER')),
    vede_tutti    BOOLEAN       NOT NULL DEFAULT FALSE,
    attivo        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_kunnr_reqid ON ticket_user(kunnr, reqid);

GRANT SELECT, INSERT, UPDATE, DELETE ON ticket_user TO ticket_app;

-- Esempio inserimento utente (da adattare — password_hash va generato con BCrypt):
-- INSERT INTO ticket_user (id_user, kunnr, reqid, nome, email, password_hash, ruolo, vede_tutti, attivo)
-- VALUES ('MARIO', '0000123456', 'MARIO', 'Mario Rossi', 'mario.rossi@cliente.it', '$2a$10$...', 'CLIENTE', FALSE, TRUE);


-- =====================================================================
-- SEZIONE 4 — ticket_stato_rstat (transcodifica 13 stati SAP)    [CONFERMATO]
-- Colori: versione finale "pastello vero" (dopo due iterazioni di
-- schiarimento richieste esplicitamente).
-- =====================================================================

CREATE TABLE IF NOT EXISTS ticket_stato_rstat (
    rstat             VARCHAR(4)   PRIMARY KEY,   -- codice stato SAP (es. 'NEW', 'WIP', 'CLO')
    descrizione       VARCHAR(50)  NOT NULL,       -- descrizione estesa (es. 'IN PROGRESS')
    descrizione_corta VARCHAR(20)  NOT NULL,       -- descrizione corta per spazi ridotti
    ordine            INT          NOT NULL,       -- posizione nel ciclo di vita (per sort)
    colore            VARCHAR(7)   NOT NULL,       -- colore badge (#RRGGBB)
    colore_testo      VARCHAR(7)   NOT NULL DEFAULT '#FFFFFF'  -- colore testo contrastante
);

-- Popolamento diretto con i colori pastello FINALI (salta le due iterazioni intermedie)
INSERT INTO ticket_stato_rstat (rstat, descrizione, descrizione_corta, ordine, colore, colore_testo) VALUES
    ('NEW', 'NEW',                   'NEW',         0, '#FFFFFF', '#333333'),
    ('ANA', 'IN ANALYSIS',           'ANALYSIS',    1, '#EDE8F8', '#5C4A8A'),
    ('INA', 'IN APPROVAL',           'IN APPROV.',  2, '#E2DAF5', '#4A3878'),
    ('APP', 'APPROVED',              'APPROVED',    3, '#D4CBF0', '#3D2F72'),
    ('ASS', 'ASSIGNED',              'ASSIGNED',    4, '#C8D4EE', '#2C3E6B'),
    ('WIP', 'IN PROGRESS',           'WIP',         5, '#BDD0EE', '#1E3A6E'),
    ('UAT', 'USER ACCEPTANCE TEST',  'UAT',         6, '#B8DDF2', '#1A4060'),
    ('RES', 'RESOLVED',              'RESOLVED',    7, '#B5E5F0', '#164050'),
    ('REL', 'RELEASED',              'RELEASED',    8, '#B2E8E0', '#144040'),
    ('CLO', 'CLOSED',                'CLOSED',      9, '#B5E8DC', '#144038'),
    ('STB', 'STAND BY',              'STAND BY',   90, '#D8DFE3', '#3D4E56'),
    ('REF', 'REFUSED',               'REFUSED',    91, '#E5E9EC', '#4A5560'),
    ('CAN', 'CANCELLED',             'CANCELLED',  92, '#E5E9EC', '#4A5560')
ON CONFLICT (rstat) DO UPDATE SET
    descrizione       = EXCLUDED.descrizione,
    descrizione_corta = EXCLUDED.descrizione_corta,
    ordine            = EXCLUDED.ordine,
    colore            = EXCLUDED.colore,
    colore_testo      = EXCLUDED.colore_testo;

GRANT SELECT ON ticket_stato_rstat TO ticket_app;

COMMENT ON TABLE ticket_stato_rstat IS 'Transcodifica stati ticket SAP (rstat) -> etichetta + colore badge';


-- =====================================================================
-- SEZIONE 5 — Stati commento (7 codici, scala termica)           [CONFERMATO]
-- NON è una tabella a parte: sono valori applicativi gestiti in
-- ticket_comment.stato_ticket e in TicketComment.java (costanti +
-- getStatoColor()). Documentati qui per completezza/riferimento.
--
-- Scala termica: urgenza crescente verso il rosso, calma verso il
-- violetto. Chiusura = "fredda" (blu/viola), attesa neutra = centro
-- (verde/giallo), sollecito = "caldo" (arancione/rosso).
-- =====================================================================

-- | Codice                     | Etichetta                        | Ruolo che lo imposta | Colore   | Hex       |
-- |----------------------------|-----------------------------------|-----------------------|----------|-----------|
-- | ASS_CONCLUSO               | Ticket concluso                   | Assistenza             | Viola    | #7B68EE   |
-- | CLI_RISOLTO                | Ticket risolto                    | Cliente                | Blu      | #4A90D9   |
-- | CLI_RICHIESTA_CHIUSURA     | Richiesta chiusura ticket         | Cliente                | Ciano    | #26A69A   |
-- | ASS_ATTESA_CLIENTE         | Attesa attività Cliente           | Assistenza             | Verde    | #66BB6A   |
-- | CLI_ATTESA_ASSISTENZA      | Attesa attività Assistenza        | Cliente                | Giallo   | #FBC02D   |
-- | ASS_SOLLECITO_CLIENTE      | Sollecito Attività Cliente        | Assistenza             | Arancio  | #FB8C00   |
-- | CLI_SOLLECITO_ASSISTENZA   | Sollecito attività Assistenza     | Cliente                | Rosso    | #E53935   |

-- Nessuna riga da inserire: sono costanti Java in TicketComment.java,
-- non dati di parametrizzazione a DB.


-- =====================================================================
-- SEZIONE 6 — ticket_draft (ticket locali pre-fusione SAP)       [CONFERMATO]
-- =====================================================================

CREATE TABLE IF NOT EXISTS ticket_draft (
    id          BIGSERIAL       PRIMARY KEY,
    kunnr       VARCHAR(10)     NOT NULL,
    reqid       VARCHAR(10),
    id_user     VARCHAR(20)     NOT NULL REFERENCES ticket_user(id_user),
    titolo      VARCHAR(255)    NOT NULL,
    stato       VARCHAR(10)     NOT NULL DEFAULT 'DRAFT'
                                CHECK (stato IN ('DRAFT', 'MERGED')),
    tickt_sap   VARCHAR(20),    -- valorizzato dal DISPATCHER alla fusione
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_draft_kunnr_reqid ON ticket_draft(kunnr, reqid);
CREATE INDEX IF NOT EXISTS idx_draft_stato       ON ticket_draft(stato);
CREATE INDEX IF NOT EXISTS idx_draft_id_user     ON ticket_draft(id_user);

GRANT SELECT, INSERT, UPDATE, DELETE ON ticket_draft TO ticket_app;
GRANT USAGE, SELECT ON SEQUENCE ticket_draft_id_seq TO ticket_app;

COMMENT ON TABLE ticket_draft IS 'Ticket locali creati dal cliente via WebApp, non ancora fusi in SAP dal DISPATCHER';


-- =====================================================================
-- SEZIONE 7 — ticket_access_log (audit logon)                    [CONFERMATO]
-- =====================================================================

CREATE TABLE IF NOT EXISTS ticket_access_log (
    id            BIGSERIAL   PRIMARY KEY,
    reqid         VARCHAR(40) NOT NULL,
    kunnr         VARCHAR(10),
    esito         VARCHAR(10) NOT NULL,   -- 'OK' o 'FAIL'
    ip_address    VARCHAR(45),
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

GRANT SELECT, INSERT ON ticket_access_log TO ticket_app;
GRANT USAGE, SELECT ON SEQUENCE ticket_access_log_id_seq TO ticket_app;


-- =====================================================================
-- VERIFICA FINALE
-- =====================================================================

SELECT rstat, descrizione_corta, colore, colore_testo FROM ticket_stato_rstat ORDER BY ordine;
SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;
