-- ============================================================================
-- be-batch (db_base) — colonna `log` su batch_execution: la "telecronaca" dell'elaborazione.
--
-- MOTIVO. Prima be-batch restava appeso in attesa della risposta HTTP del servizio: su un caricamento
-- lungo scattava il read timeout (ResourceAccessException: Read timed out) e l'esecuzione veniva
-- marcata FAILED anche se il servizio stava lavorando correttamente. Inoltre l'esito era un blocco
-- unico a fine corsa: non si sapeva a che punto fosse.
--
-- NUOVA STRATEGIA. Il servizio chiamato scrive lui l'avanzamento (POST /batch-executions/{id}/log) e
-- alla fine imposta lo stato definitivo (POST /batch-executions/{id}/finish). be-batch non aspetta:
-- se il servizio risponde 202 ACCEPTED lascia l'esecuzione PENDING e sara' il servizio a chiuderla.
-- L'id dell'esecuzione viaggia gia' verso il servizio nell'header `idExecution`.
--
-- Eseguire su db_base (MySQL 8). Idempotente: guardia su INFORMATION_SCHEMA.
-- ============================================================================

SET @col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'db_base' AND TABLE_NAME = 'batch_execution' AND COLUMN_NAME = 'log'
);
SET @ddl := IF(@col = 0,
  'ALTER TABLE `db_base`.`batch_execution` ADD COLUMN `log` LONGTEXT NULL AFTER `response_body`',
  'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Verifica:
--   SHOW COLUMNS FROM `db_base`.`batch_execution` LIKE 'log';
