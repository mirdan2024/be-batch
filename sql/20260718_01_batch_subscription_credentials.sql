-- ============================================================================
-- be-batch — Migrazione: aggiunge le colonne credenziali a batch_subscription.
--
-- Serve SOLO se la tabella batch_subscription era già stata creata da una versione
-- precedente dello schema (senza `username` / `password_enc`): in quel caso
-- CREATE TABLE IF NOT EXISTS non aggiunge le colonne e lo scheduler fallisce con
-- "Unknown column 'password_enc' in 'field list'".
--
-- Chi crea il DB da zero con 20260717_01_batch_schema.sql NON deve eseguire questo file.
--
-- MySQL 8 non ha ADD COLUMN IF NOT EXISTS: l'idempotenza è ottenuta controllando
-- INFORMATION_SCHEMA e costruendo l'ALTER in SQL dinamico solo se la colonna manca.
-- Rieseguibile senza errori. Eseguire una volta su MySQL.
-- ============================================================================

-- --- username: utenza con cui la schedulazione esegue il servizio -----------
SET @col_username := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'db_base'
    AND TABLE_NAME   = 'batch_subscription'
    AND COLUMN_NAME  = 'username'
);
SET @ddl_username := IF(@col_username = 0,
  'ALTER TABLE `db_base`.`batch_subscription`
     ADD COLUMN `username` VARCHAR(255) NOT NULL DEFAULT '''' AFTER `cron_expression`',
  'DO 0');
PREPARE s FROM @ddl_username; EXECUTE s; DEALLOCATE PREPARE s;

-- --- password_enc: password CIFRATA a riposo (mai in chiaro) ----------------
-- Nullable in migrazione per non fallire l'ALTER su tabelle già popolate: l'applicazione
-- valorizza sempre la colonna in insert (l'entità la dichiara NOT NULL a livello JPA).
SET @col_password := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'db_base'
    AND TABLE_NAME   = 'batch_subscription'
    AND COLUMN_NAME  = 'password_enc'
);
SET @ddl_password := IF(@col_password = 0,
  'ALTER TABLE `db_base`.`batch_subscription`
     ADD COLUMN `password_enc` TEXT NULL AFTER `username`',
  'DO 0');
PREPARE s FROM @ddl_password; EXECUTE s; DEALLOCATE PREPARE s;

-- Verifica (facoltativa):
-- SHOW COLUMNS FROM `db_base`.`batch_subscription` LIKE '%username%';
-- SHOW COLUMNS FROM `db_base`.`batch_subscription` LIKE '%password_enc%';
