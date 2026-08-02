-- ============================================================================
-- be-batch — Definition per la POTATURA delle tabelle di storico bizcom.
--
-- Il caricamento DELTA sposta in `soc_*_storico` ogni versione sostituita: senza potatura lo storico
-- cresce senza limite e si sposta soltanto il problema di dimensione dalle tabelle correnti a quelle
-- di storico. Questo job cancella le versioni superate piu' vecchie di N mesi (default 12, si cambia
-- col parametro ?mesi= nell'URL oppure con BIZCOM_STORICO_RETENTION_MONTHS su be-openapi).
--
-- La cancellazione avviene A BLOCCHI (DELETE ... LIMIT, default 5000): su tabelle da milioni di righe
-- una DELETE massiva terrebbe un lock lungo e gonfierebbe il redo log.
--
-- Il servizio risponde 202 e scrive la telecronaca (avanzamento + esito) su batch_execution, quindi
-- be-batch non resta appeso. E' interrompibile dalla pagina Schedulazioni batch.
--
-- NB: l'endpoint sta sotto /bizcom-import di proposito, cosi' riusa la riga di routing del gateway
-- gia' esistente per 'bizcom-import' (nessuna nuova riga in db_base.routing).
--
-- Eseguire su db_base (MySQL 8). Idempotente: se il codice esiste gia' non viene duplicato.
-- ============================================================================

SET @esiste = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'bizcom-storico-purge');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `stop_url`, `body_json`, `http_method`, `enabled`, `data_creazione`)
SELECT
  'bizcom-storico-purge',
  'Potatura storico liste societarie: cancella dalle tabelle soc_*_storico le versioni superate piu vecchie di 12 mesi (cancellazione a blocchi)',
  'http://localhost:8094/be-openapi/bizcom-import/purge-storico?mesi=12',
  'http://localhost:8094/be-openapi/batch-control/bizcom-storico-purge/stop',
  '{}',
  'POST',
  1,
  NOW()
WHERE @esiste = 0;

-- Id della definition (serve per creare la schedulazione):
--   SELECT id FROM `db_base`.`batch_definition` WHERE code = 'bizcom-storico-purge';

-- In produzione sostituire l'host in ENTRAMBI gli URL (come per le altre definition), es.
--   UPDATE `db_base`.`batch_definition`
--      SET `endpoint_url` = 'http://openapi-service:8080/be-openapi/bizcom-import/purge-storico?mesi=12',
--          `stop_url`     = 'http://openapi-service:8080/be-openapi/batch-control/bizcom-storico-purge/stop'
--    WHERE `code` = 'bizcom-storico-purge';

-- ============================================================================
-- SCHEDULAZIONE — UNA TANTUM (come richiesto)
--
-- cronExpression = null  =>  sottoscrizione MANUALE: lo scheduler non la seleziona mai
-- (next_run_at resta null) e si lancia a mano con "Esegui ora" (razzo) dalla pagina
-- Impostazioni -> Schedulazioni batch. E' il modo corretto per un'esecuzione una tantum.
--
-- NON inserirla via SQL: `password_enc` deve essere cifrata da be-batch (CredentialCipher).
-- Crearla dalla pagina Schedulazioni batch (consigliato) oppure via API con questi valori:
--
--   POST http://localhost:8086/be-batch/batch-subscriptions
--   Authorization: Bearer <token valido>
--   Content-Type: application/json
--   {
--     "idIntermediario": 1,
--     "batchDefinitionId": <ID della definition 'bizcom-storico-purge'>,
--     "cronExpression": null,                     -- null = solo esecuzione manuale (una tantum)
--     "username": "<utenza batch>",
--     "password": "<password>",
--     "timezone": "Europe/Rome",
--     "enabled": true,
--     "paramsJson": null,
--     "bodyJson": "{}",
--     "idUtenteAdmin": 1
--   }
--
-- Se in futuro la si vuole ricorrente (consigliato: mensile, di notte), basta valorizzare il cron,
-- es. "0 0 3 1 * *" = ogni primo del mese alle 03:00.
-- ============================================================================
