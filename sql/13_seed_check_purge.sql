-- ============================================================================
-- be-batch — Definition per la PULIZIA delle tabelle di check in db_aidati.
--
--   purge-check-ai        db_aidati.check_ai        retention  90 giorni
--   purge-check-openapi   db_aidati.check_openapi   retention  30 giorni
--   purge-check-risk      db_aidati.check_risk      retention  30 giorni
--
-- Criterio: si cancella per eta' della riga, `data_creazione < NOW() - INTERVAL <retention> DAY`.
-- La retention e' gia' nell'URL (?giorni=N): per cambiarla basta aggiornare endpoint_url, senza
-- toccare il codice.
--
-- Il servizio risponde 202 e scrive la telecronaca (record candidati, avanzamento, esito) su
-- batch_execution: be-batch non resta appeso. Ogni pulizia ha il PROPRIO job, quindi si puo'
-- interrompere una senza fermare le altre.
--
-- La cancellazione va A BLOCCHI (DELETE ... LIMIT 5000) per non tenere lock lunghi.
--
-- PREREQUISITO: eseguire be-openapi/sql/06_check_purge_indexes.sql, che crea gli indici su
-- data_creazione. Senza, ogni blocco scansiona la tabella intera e la pulizia diventa impraticabile.
--
-- NB: URL DIRETTI a be-openapi (porta 8094, context /be-openapi): be-batch chiama i servizi
-- direttamente, non serve una riga in db_base.routing.
--
-- Eseguire su db_base (MySQL 8). Idempotente: se il codice esiste gia' non viene duplicato.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) check_ai — 90 giorni
-- ----------------------------------------------------------------------------
SET @e1 = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'purge-check-ai');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `stop_url`, `body_json`, `http_method`, `enabled`, `data_creazione`)
SELECT
  'purge-check-ai',
  'Pulizia db_aidati.check_ai: cancella i record con data_creazione piu vecchia di 90 giorni (a blocchi)',
  'http://localhost:8094/be-openapi/purge/check-ai?giorni=90',
  'http://localhost:8094/be-openapi/batch-control/purge-check-ai/stop',
  '{}',
  'POST',
  1,
  NOW()
WHERE @e1 = 0;

-- ----------------------------------------------------------------------------
-- 2) check_openapi — 30 giorni
-- ----------------------------------------------------------------------------
SET @e2 = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'purge-check-openapi');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `stop_url`, `body_json`, `http_method`, `enabled`, `data_creazione`)
SELECT
  'purge-check-openapi',
  'Pulizia db_aidati.check_openapi: cancella i record con data_creazione piu vecchia di 30 giorni (a blocchi)',
  'http://localhost:8094/be-openapi/purge/check-openapi?giorni=30',
  'http://localhost:8094/be-openapi/batch-control/purge-check-openapi/stop',
  '{}',
  'POST',
  1,
  NOW()
WHERE @e2 = 0;

-- ----------------------------------------------------------------------------
-- 3) check_risk — 30 giorni
-- ----------------------------------------------------------------------------
SET @e3 = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'purge-check-risk');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `stop_url`, `body_json`, `http_method`, `enabled`, `data_creazione`)
SELECT
  'purge-check-risk',
  'Pulizia db_aidati.check_risk: cancella i record con data_creazione piu vecchia di 30 giorni (a blocchi)',
  'http://localhost:8094/be-openapi/purge/check-risk?giorni=30',
  'http://localhost:8094/be-openapi/batch-control/purge-check-risk/stop',
  '{}',
  'POST',
  1,
  NOW()
WHERE @e3 = 0;

-- Id delle definition (servono per creare le schedulazioni):
--   SELECT id, code, endpoint_url FROM `db_base`.`batch_definition` WHERE code LIKE 'purge-check-%';

-- In produzione sostituire l'host in ENTRAMBI gli URL di ciascuna riga, es.
--   UPDATE `db_base`.`batch_definition`
--      SET `endpoint_url` = REPLACE(`endpoint_url`, 'http://localhost:8094', 'http://openapi-service:8080'),
--          `stop_url`     = REPLACE(`stop_url`,     'http://localhost:8094', 'http://openapi-service:8080')
--    WHERE `code` LIKE 'purge-check-%';

-- ============================================================================
-- SCHEDULAZIONI (batch_subscription)
--
-- NON si inseriscono via SQL: `password_enc` deve essere cifrata da be-batch (CredentialCipher).
-- Crearle da Impostazioni -> Schedulazioni batch, oppure via API:
--
--   POST http://localhost:8086/be-batch/batch-subscriptions
--   {
--     "idIntermediario": 1,
--     "batchDefinitionId": <ID della definition>,
--     "cronExpression": "0 0 3 * * *",     -- ogni notte alle 03:00 (Spring, 6 campi)
--     "username": "<utenza batch>",
--     "password": "<password>",
--     "timezone": "Europe/Rome",
--     "enabled": true,
--     "paramsJson": null,
--     "bodyJson": "{}",
--     "idUtenteAdmin": 1
--   }
--
-- Consiglio: orari SFALSATI fra le tre pulizie e rispetto agli altri job (caricamenti bizcom,
-- alert Pibisi), per non concentrare il carico sul DB nella stessa finestra. Es. 03:00 / 03:20 / 03:40.
-- Per una prima esecuzione controllata: cronExpression = null (solo "Esegui ora") e si osserva la
-- telecronaca, poi si imposta il cron.
-- ============================================================================
