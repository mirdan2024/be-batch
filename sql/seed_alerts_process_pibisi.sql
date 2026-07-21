-- ============================================================================
-- be-batch — Definition per la sincronizzazione alert Pibisi (alerts-process).
--
-- Il servizio (be-searchPibisi, POST /customers/alerts-process) legge gli alert dei customer in
-- monitoraggio, li salva in db_monitor.pibisi_alert e li contrassegna come accettati su Pibisi
-- (accettazione al momento commentata per i test). Con body vuoto/{} processa TUTTI i customer
-- dell'account risolto dal token: e' la modalita' pensata per l'esecuzione schedulata.
--
-- NB: URL DIRETTO a be-searchPibisi (porta 8088, context /pibisi): nella tabella routing del gateway
-- non esiste una riga 'customers', quindi via gateway (8095) il servizio non e' raggiungibile senza
-- aggiungerla. In produzione sostituire con l'host del servizio (es. http://pibisi-service-new:8080/pibisi)
-- oppure impostare l'URL corretto direttamente qui sotto.
--
-- Eseguire su db_base (MySQL 8). Idempotente: se il codice esiste gia' non viene duplicato.
-- ============================================================================

SET @esiste = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'pibisi-alerts-process');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `data_creazione`)
SELECT
  'pibisi-alerts-process',
  'Sincronizzazione alert Pibisi: legge gli alert dei customer in monitoraggio e li salva in db_monitor.pibisi_alert',
  'http://localhost:8088/pibisi/customers/alerts-process',
  '{}',
  'POST',
  1,
  NOW()
WHERE @esiste = 0;

-- Id della definition (serve per creare la schedulazione):
--   SELECT id FROM `db_base`.`batch_definition` WHERE code = 'pibisi-alerts-process';

-- ============================================================================
-- SCHEDULAZIONE: crearla dalla pagina Impostazioni -> Schedulazioni batch (consigliato: l'autocomplete
-- intermediario, il costruttore cron e la cifratura password fanno tutto loro), oppure via API.
-- NON inserirla via SQL: password_enc deve essere cifrata da be-batch (CredentialCipher).
--
-- Esempio via API (Postman, diretto a be-batch):
--   POST http://localhost:8080/batch/batch-subscriptions
--   Authorization: Bearer <token valido>
--   Content-Type: application/json
--   {
--     "idIntermediario": 1,
--     "batchDefinitionId": <ID della definition creata sopra>,
--     "cronExpression": "0 0 * * * *",           -- ogni ora al minuto 0 (Spring, 6 campi)
--     "username": "<utenza batch>",              -- es. l'utente batch creato in Gestione utenti
--     "password": "<password>",                  -- verra' cifrata a riposo
--     "timezone": "Europe/Rome",
--     "enabled": true,
--     "paramsJson": null,
--     "bodyJson": "{}",                          -- vuoto = tutti i customer dell'account
--     "idUtenteAdmin": 1
--   }
--
-- Per processare UN SOLO customer (test): bodyJson = "{\"customerId\": \"<uuid customer Pibisi>\"}".
-- Test manuale senza attendere il cron: azione "Esegui ora" (razzo) nella pagina Schedulazioni batch.
-- ============================================================================
