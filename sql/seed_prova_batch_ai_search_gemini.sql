-- ============================================================================
-- be-batch — Seed di prova: schedulare la POST verso ai-search-gemini/prova-batch.
--
-- Equivalente Postman:
--   POST http://localhost:8095/routing/ai-search-gemini/prova-batch
--   Authorization: Bearer <token>          (be-batch lo ottiene da solo via doLoginBatch)
--   Content-Type: application/json
--   body: {"checkTxt": "mir"}
--
-- Il body e le credenziali NON stanno qui: la definition porta solo endpoint + metodo.
-- Il body effettivo e username/password vanno sulla batch_subscription (vedi sotto).
-- Eseguire su db_base.
-- ============================================================================

-- 1) DEFINITION: il "cosa" (endpoint + metodo). body_json qui è solo un template di default.
INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `data_creazione`)
VALUES
  ('ai-search-gemini-prova-batch',
   'Prova batch: POST ai-search-gemini/prova-batch (checkTxt)',
   'http://localhost:8095/routing/ai-search-gemini/prova-batch',
   '{"checkTxt": "mir"}',
   'POST',
   1,
   NOW());

-- Id appena creato (serve per la subscription):
--   SELECT id FROM `db_base`.`batch_definition` WHERE code = 'ai-search-gemini-prova-batch';

-- 2) SUBSCRIPTION: il "quando/per chi" + body reale + credenziali.
--    ATTENZIONE: password_enc deve essere CIFRATA da be-batch (CredentialCipher), NON si può
--    scrivere in chiaro via SQL. Creare quindi la sottoscrizione via API (POST /batch-subscriptions),
--    che cifra la password. Esempio (Postman) — chiamata DIRETTA a be-batch, così non serve il gateway:
--
--    POST http://localhost:8080/batch/batch-subscriptions
--    Authorization: Bearer <un token valido dell'app>
--    Content-Type: application/json
--    {
--      "idIntermediario": 1,
--      "customerName": "Prova",
--      "batchDefinitionId": <ID della definition creata sopra>,
--      "cronExpression": "0 * * * * *",          -- Spring cron a 6 campi: ogni minuto al secondo 0
--      "username": "<utenza reale che esegue il servizio>",
--      "password": "<password reale>",           -- verrà cifrata a riposo
--      "timezone": "Europe/Rome",
--      "enabled": true,
--      "paramsJson": null,                        -- nessun {segnaposto} nell'URL
--      "bodyJson": "{\"checkTxt\": \"mir\"}",     -- QUI il body realmente inviato
--      "idUtenteAdmin": 1
--    }
