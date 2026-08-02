-- ============================================================================
-- be-batch (db_base) — colonna stop_url su batch_definition.
--
-- Un processo remoto non si puo' "uccidere" dall'esterno: se il servizio chiamato supporta la
-- CANCELLAZIONE COOPERATIVA espone un endpoint di stop (che alza un flag controllato durante
-- l'elaborazione) e lo dichiara qui. Quando l'operatore interrompe una schedulazione dalla UI,
-- be-batch chiude la riga di batch_execution E invoca questo URL, fermando davvero il lavoro a valle.
-- NULL = il servizio non e' interrompibile: si chiude solo la riga lato be-batch.
--
-- Eseguire su db_base (MySQL 8). Idempotente: guardia su INFORMATION_SCHEMA.
-- ============================================================================

SET @col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'db_base' AND TABLE_NAME = 'batch_definition' AND COLUMN_NAME = 'stop_url'
);
SET @ddl := IF(@col = 0,
  'ALTER TABLE `db_base`.`batch_definition` ADD COLUMN `stop_url` VARCHAR(1000) NULL AFTER `endpoint_url`',
  'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ----------------------------------------------------------------------------
-- Stop-url di TUTTI i servizi schedulabili.
-- Convenzione UNICA (endpoint standard di commonBase, presente in ogni servizio):
--     <host>/<context>/batch-control/<nome-job>/stop
-- e lo stato ("clessidra") si legge da .../batch-control/<nome-job>/status
-- I nomi job sono costanti nel codice del servizio (es. CustomersAlertsSyncService.JOB_ALERTS_PROCESS).
-- ----------------------------------------------------------------------------

-- be-openapi (8094, /be-openapi) — import liste societarie: FULL e DELTA condividono lo stesso loader,
-- quindi lo stesso job "bizcom-import".
UPDATE `db_base`.`batch_definition`
   SET `stop_url` = 'http://localhost:8094/be-openapi/batch-control/bizcom-import/stop'
 WHERE `code` IN ('bizcom-soc-import', 'bizcom-soc-import-delta');

-- be-searchPibisi (8088, /pibisi) — sincronizzazione alert.
UPDATE `db_base`.`batch_definition`
   SET `stop_url` = 'http://localhost:8088/pibisi/batch-control/pibisi-alerts-process/stop'
 WHERE `code` = 'pibisi-alerts-process';

UPDATE `db_base`.`batch_definition`
   SET `stop_url` = 'http://localhost:8088/pibisi/batch-control/pibisi-alerts-process-all/stop'
 WHERE `code` = 'pibisi-alerts-process-all';

-- be-google-ai (8093, /be-gemini-ai) — job di prova.
UPDATE `db_base`.`batch_definition`
   SET `stop_url` = 'http://localhost:8093/be-gemini-ai/batch-control/gemini-prova-batch/stop'
 WHERE `code` = 'ai-search-gemini-prova-batch';

-- Verifica:
--   SELECT code, endpoint_url, stop_url FROM `db_base`.`batch_definition` ORDER BY code;
--
-- In produzione sostituire gli host (come per endpoint_url), mantenendo il suffisso
-- /batch-control/<nome-job>/stop.
--
-- NUOVI SERVIZI: qualsiasi servizio schedulabile eredita gli endpoint /batch-control da commonBase.
-- Basta che il codice avvolga l'elaborazione con BatchJobControl.begin()/end() e controlli
-- isStopRequested() nel ciclo; poi si valorizza qui lo stop_url con il nome del job scelto.
