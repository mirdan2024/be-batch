-- ============================================================================
-- be-batch — Definition per la sincronizzazione COMPLETA alert Pibisi (alerts-process-all).
--
-- Il servizio (be-searchPibisi, POST /customers/alerts-process-all) legge TUTTI gli alert dei customer
-- in monitoraggio restituiti dalla GET "get customer alerts" (INCLUSI quelli gia' accettati), li salva
-- in db_monitor.pibisi_alert (senza duplicare quelli gia' presenti) e li RI-accetta tutti su Pibisi.
-- Utile per ri-sincronizzare l'intero storico / recupero in caso di perdita o disallineamento del DB.
-- Con body vuoto/{} processa TUTTI i customer dell'account risolto dal token.
--
-- Differenza da 'pibisi-alerts-process': quella normale SALTA gli alert gia' accettati; questa li
-- processa e ri-accetta tutti.
--
-- NB: URL DIRETTO a be-searchPibisi (porta 8088, context /pibisi). In produzione sostituire con l'host
-- del servizio (es. http://pibisi-service-new:8080/pibisi/customers/alerts-process-all).
--
-- Eseguire su db_base (MySQL 8). Idempotente: se il codice esiste gia' non viene duplicato.
-- ============================================================================

SET @esiste = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'pibisi-alerts-process-all');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `data_creazione`)
SELECT
  'pibisi-alerts-process-all',
  'Sincronizzazione COMPLETA alert Pibisi: processa TUTTI gli alert (anche gia'' accettati) senza accettazione',
  'http://localhost:8088/pibisi/customers/alerts-process-all',
  '{}',
  'POST',
  1,
  NOW()
WHERE @esiste = 0;

-- Id della definition (serve per creare la schedulazione):
--   SELECT id FROM `db_base`.`batch_definition` WHERE code = 'pibisi-alerts-process-all';

-- ============================================================================
-- SCHEDULAZIONE: crearla dalla pagina Impostazioni -> Schedulazioni batch (consigliato: autocomplete
-- intermediario, costruttore cron e cifratura password automatici), oppure via API.
-- NON inserirla via SQL: password_enc deve essere cifrata da be-batch (CredentialCipher).
--
-- Esempio via API (Postman, diretto a be-batch):
--   POST http://localhost:8080/batch/batch-subscriptions
--   Authorization: Bearer <token valido>
--   Content-Type: application/json
--   {
--     "idIntermediario": 1,
--     "batchDefinitionId": <ID della definition creata sopra>,
--     "cronExpression": "0 0 3 * * *",            -- ogni giorno alle 03:00 (Spring, 6 campi)
--     "username": "<utenza batch>",               -- es. l'utente batch creato in Gestione utenti
--     "password": "<password>",                   -- verra' cifrata a riposo
--     "timezone": "Europe/Rome",
--     "enabled": true,
--     "paramsJson": null,
--     "bodyJson": "{}",                           -- vuoto = tutti i customer dell'account
--     "idUtenteAdmin": 1
--   }
--
-- Per processare UN SOLO customer (test): bodyJson = "{\"customerId\": \"<uuid customer Pibisi>\"}".
-- Test manuale senza attendere il cron: azione "Esegui ora" (razzo) nella pagina Schedulazioni batch.
--
-- Suggerimento: essendo un re-sync completo (piu' pesante), schedularlo con bassa frequenza
-- (es. una volta al giorno / a settimana), non ogni ora come la sincronizzazione normale.
-- ============================================================================
