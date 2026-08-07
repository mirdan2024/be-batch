-- ============================================================================
-- be-batch — Definition per il caricamento delle liste societarie "bizcom".
--
-- Il servizio (be-openapi, POST /bizcom-import/run) recupera da be-storage i 6 file pipe-delimited
-- AEGISX_EXPORT_*.csv e li carica nelle tabelle soc_* di db_aidati (soc_generale come padre, poi unita'
-- locali / bilanci consolidati / bilanci esercizio / esponenti / soci). Ogni file viene ricaricato
-- integralmente (TRUNCATE + reload), quindi l'esecuzione e' idempotente e ripetibile.
--
-- NB: URL DIRETTO a be-openapi (porta 8094, context /be-openapi): nella tabella routing del gateway
-- non esiste una riga 'bizcom-import', quindi via gateway (8095) il servizio non e' raggiungibile
-- senza aggiungerla. In produzione sostituire con l'host del servizio
-- (es. http://openapi-service:8080/be-openapi) oppure impostare l'URL corretto direttamente qui sotto.
--
-- Prerequisiti:
--   1) eseguire una volta be-openapi/sql/03_bizcom_soc.sql su db_aidati (crea le tabelle soc_*);
--   2) caricare i 6 CSV su be-storage sotto <intermediario>/<type>/<folder>/
--      (default: 0/bizcom/liste, configurabile con BIZCOM_STORAGE_INTERMEDIARIO/_TYPE/_FOLDER);
--   3) su be-openapi: BIZCOM_IMPORT_ENABLED=true, API_SERVICE_STORAGE e TOKEN_CHIAMATE_INTERNE valorizzati.
--
-- Eseguire su db_base (MySQL 8). Idempotente: se il codice esiste gia' non viene duplicato.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) FULL — snapshot integrale: TRUNCATE + reload di tutte e 6 le tabelle.
-- ----------------------------------------------------------------------------
SET @esiste = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'bizcom-soc-import');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `data_creazione`)
SELECT
  'bizcom-soc-import',
  'Caricamento FULL liste societarie bizcom: legge i 6 file AEGISX_EXPORT_*.csv da be-storage e ricarica integralmente le tabelle soc_* di db_aidati (TRUNCATE + reload)',
  'http://localhost:8094/be-openapi/bizcom-import/run?mode=FULL',
  '{}',
  'POST',
  1,
  NOW()
WHERE @esiste = 0;

-- ----------------------------------------------------------------------------
-- 2) DELTA — file che porta solo cio' che e' cambiato. Passa dal confronto delle impronte come la
--    modalita' FULL_COME_DELTA (vedi seed 19): nessun TRUNCATE delle correnti, le versioni superate
--    finiscono in soc_*_storico, le righe invariate non si toccano e sulle tabelle figlie le righe
--    assenti dal file — limitatamente alle societa' che l'export menziona — vengono dichiarate USCITE.
-- ----------------------------------------------------------------------------
SET @esisteDelta = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'bizcom-soc-import-delta');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `data_creazione`)
SELECT
  'bizcom-soc-import-delta',
  'Caricamento DELTA liste societarie bizcom: applica la differenza del file (nuovi, variati con storicizzazione della versione precedente, uscite sulle tabelle figlie), senza TRUNCATE delle correnti',
  'http://localhost:8094/be-openapi/bizcom-import/run?mode=DELTA',
  '{}',
  'POST',
  1,
  NOW()
WHERE @esisteDelta = 0;

-- Id delle definition (servono per creare le schedulazioni):
--   SELECT id, code FROM `db_base`.`batch_definition` WHERE code LIKE 'bizcom-soc-import%';

-- ----------------------------------------------------------------------------
-- VARIANTE (facoltativa): caricamento di UNA SOLA tabella, per ricariche mirate.
-- L'endpoint e' /bizcom-import/run/{table} con table in:
--   soc_generale | soc_unita_locali | soc_bilanci_consolidati |
--   soc_bilanci_esercizio_ridotti | soc_esponenti | soc_soci
-- Esempio (solo i soci): decommentare e adattare.
-- ----------------------------------------------------------------------------
-- SET @esisteSoci = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'bizcom-soc-import-soci');
-- INSERT INTO `db_base`.`batch_definition`
--   (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `data_creazione`)
-- SELECT
--   'bizcom-soc-import-soci',
--   'Caricamento liste societarie bizcom: solo tabella soc_soci',
--   'http://localhost:8094/be-openapi/bizcom-import/run/soc_soci',
--   '{}',
--   'POST',
--   1,
--   NOW()
-- WHERE @esisteSoci = 0;

-- ============================================================================
-- SCHEDULAZIONI (batch_subscription) — DUE, una per modalita'.
--
-- NON si inseriscono via SQL: `password_enc` deve essere cifrata da be-batch (CredentialCipher).
-- Crearle dalla pagina Impostazioni -> Schedulazioni batch (l'autocomplete intermediario, il
-- costruttore cron e la cifratura password fanno tutto loro) oppure via API, con questi valori.
--
-- ----------------------------------------------------------------------------
-- 1) FULL — UNA TANTUM (caricamento iniziale)
--    cronExpression = null  =>  sottoscrizione MANUALE: lo scheduler NON la seleziona mai
--    (next_run_at resta null). Si lancia a mano con l'azione "Esegui ora" (razzo) nella pagina
--    Schedulazioni batch. E' il modo corretto per una esecuzione una tantum.
-- ----------------------------------------------------------------------------
--   POST http://localhost:8080/batch/batch-subscriptions
--   Authorization: Bearer <token valido>
--   Content-Type: application/json
--   {
--     "idIntermediario": 1,
--     "batchDefinitionId": <ID della definition 'bizcom-soc-import'>,
--     "cronExpression": null,                     -- null = solo esecuzione manuale (una tantum)
--     "username": "<utenza batch>",               -- es. l'utente batch creato in Gestione utenti
--     "password": "<password>",                   -- verra' cifrata a riposo
--     "timezone": "Europe/Rome",
--     "enabled": true,
--     "paramsJson": null,
--     "bodyJson": "{}",
--     "idUtenteAdmin": 1
--   }
--
-- ----------------------------------------------------------------------------
-- 2) DELTA — GIORNALIERO
--    Ogni notte alle 02:30 (cron Spring a 6 campi: sec min ora giorno mese giorno-settimana).
--    Orario notturno e sfalsato rispetto agli altri job; allinearlo alla finestra in cui i file
--    delta vengono depositati su be-storage.
--    Facoltativo: "startAt" per far decorrere la schedulazione da una certa data/ora
--    (next_run_at = prima occorrenza del cron >= startAt).
-- ----------------------------------------------------------------------------
--   POST http://localhost:8080/batch/batch-subscriptions
--   Authorization: Bearer <token valido>
--   Content-Type: application/json
--   {
--     "idIntermediario": 1,
--     "batchDefinitionId": <ID della definition 'bizcom-soc-import-delta'>,
--     "cronExpression": "0 30 2 * * *",           -- ogni giorno alle 02:30
--     "username": "<utenza batch>",
--     "password": "<password>",
--     "timezone": "Europe/Rome",
--     "enabled": true,
--     "paramsJson": null,
--     "bodyJson": "{}",
--     "idUtenteAdmin": 1
--   }
--
-- NB: il FULL fa TRUNCATE+reload (durante l'esecuzione i dati sono temporaneamente incompleti),
-- quindi va eseguito in finestra notturna / di fermo. Il DELTA non tocca le correnti fino a confronto
-- finito: carica in staging e applica solo la differenza.
-- Test manuale senza attendere il cron: azione "Esegui ora" (razzo) nella pagina Schedulazioni batch.
-- ============================================================================
