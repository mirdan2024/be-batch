-- ============================================================================
-- be-batch — Migrazione: aggiunge la colonna start_at (decorrenza) a batch_subscription.
--
-- Serve solo se la tabella batch_subscription esiste già senza `start_at`. Chi crea il DB da zero
-- con 20260717_01_batch_schema.sql (aggiornato) NON deve eseguire questo file.
--
-- start_at = data/ora di partenza della schedulazione: next_run_at viene calcolato come prima
-- occorrenza del cron >= start_at (e comunque mai nel passato). NULL = nessun vincolo di decorrenza.
--
-- MySQL 8 non ha ADD COLUMN IF NOT EXISTS: idempotenza via INFORMATION_SCHEMA + SQL dinamico.
-- Rieseguibile senza errori. Eseguire una volta su MySQL.
-- ============================================================================

SET @col_start_at := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'db_base'
    AND TABLE_NAME   = 'batch_subscription'
    AND COLUMN_NAME  = 'start_at'
);
SET @ddl_start_at := IF(@col_start_at = 0,
  'ALTER TABLE `db_base`.`batch_subscription`
     ADD COLUMN `start_at` DATETIME NULL AFTER `next_run_at`',
  'DO 0');
PREPARE s FROM @ddl_start_at; EXECUTE s; DEALLOCATE PREPARE s;

-- Verifica (facoltativa):
-- SHOW COLUMNS FROM `db_base`.`batch_subscription` LIKE 'start_at';
