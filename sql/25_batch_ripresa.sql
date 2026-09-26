-- ============================================================================
-- be-batch — RIPRESA delle esecuzioni ("Riprendi" nello storico esecuzioni).
--
-- IL PROBLEMA. Un lavoro lungo che si ferma a meta' (errore del database, riavvio, interruzione a
-- mano) si poteva solo rilanciare da capo. Il caricamento delle liste societarie dura ore: il 26-09-2026
-- in produzione si e' fermato dopo cinque ore di lavoro e sarebbe ripartito dal primo file.
--
-- LA SOLUZIONE. Un servizio che sa ripartire da dove si era fermato lo dichiara nella definizione con
-- `resume_url`; lo storico esecuzioni mostra allora "Riprendi" sull'ultima esecuzione, se e' FAILED o
-- INTERROTTA. be-batch crea una nuova esecuzione legata a quella ripresa (`id_ripresa_di`) e chiama il
-- resume_url con gli header idExecution (la nuova) e idExecutionOriginale (quella che aveva avviato il
-- lavoro, risalendo le riprese precedenti).
--
-- Per ora lo dichiarano le tre definizioni del caricamento liste societarie (FULL, DELTA e
-- FULL_COME_DELTA): lo stesso servizio, che alla ripresa legge la modalita' dal punto salvato.
-- L'URL si ricava da quello dell'avvio, cosi' vale su ogni ambiente senza scriverne l'host.
--
-- PREREQUISITO: be-openapi/sql/17_bizcom_ripresa.sql su db_aidati (senza, la ripresa risponde che non
-- e' disponibile).
--
-- Eseguire su db_base (MySQL 8). Idempotente.
-- ============================================================================

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = 'db_base' AND TABLE_NAME = 'batch_definition' AND COLUMN_NAME = 'resume_url');
SET @sql := IF(@c = 0,
  'ALTER TABLE `db_base`.`batch_definition` ADD COLUMN `resume_url` VARCHAR(255) NULL AFTER `stop_url`',
  'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = 'db_base' AND TABLE_NAME = 'batch_execution' AND COLUMN_NAME = 'id_ripresa_di');
SET @sql := IF(@c = 0,
  'ALTER TABLE `db_base`.`batch_execution` ADD COLUMN `id_ripresa_di` BIGINT NULL AFTER `batch_subscription_id`',
  'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- Caricamento liste societarie: .../bizcom-import/run?mode=X  ->  .../bizcom-import/riprendi
UPDATE `db_base`.`batch_definition`
   SET `resume_url` = CONCAT(SUBSTRING_INDEX(`endpoint_url`, '/bizcom-import/run', 1), '/bizcom-import/riprendi')
 WHERE `endpoint_url` LIKE '%/bizcom-import/run?%'
   AND (`resume_url` IS NULL OR `resume_url` = '');

-- ----------------------------------------------------------------------------
-- Verifica
-- ----------------------------------------------------------------------------
-- SELECT id, code, endpoint_url, resume_url FROM `db_base`.`batch_definition` WHERE resume_url IS NOT NULL;
--
-- Le riprese e l'esecuzione che riprendono:
-- SELECT id, status, started_at, ended_at, id_ripresa_di FROM `db_base`.`batch_execution`
--  WHERE id_ripresa_di IS NOT NULL ORDER BY id DESC LIMIT 20;
-- ============================================================================
