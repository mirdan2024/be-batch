-- ============================================================================
-- be-batch — Migrazione: aggiunge la colonna job_successivo a batch_subscription (CONCATENAMENTO).
--
-- A COSA SERVE. Alcuni lavori hanno senso solo in fila: prima si caricano le liste societarie, poi si
-- rilevano le variazioni. Finora la sequenza si otteneva solo azzeccando due orari di cron distanti
-- abbastanza, cioe' tirando a indovinare quanto dura il primo. Con questo campo la sequenza e'
-- DICHIARATA: a fine esecuzione, e solo con esito positivo, be-batch lancia il job indicato.
--
-- IL VALORE E' IL CODICE DI UNA DEFINITION, NON L'ID DI UNA SOTTOSCRIZIONE. Un caricamento e' unico e
-- globale, mentre i lavori che ne dipendono sono spesso uno per intermediario: dovendo indicare una
-- singola sottoscrizione se ne servirebbe una catena diversa per ogni cliente. Indicando la definition,
-- be-batch lancia TUTTE le sottoscrizioni attive di quella definition.
--
-- REGOLE (attuate nel codice, non nello schema):
--   * parte solo su esito POSITIVO (COMPLETED). Un caricamento fallito non deve far girare a vuoto cio'
--     che viene dopo;
--   * la catena non puo' rientrare su se' stessa: si porta dietro l'origine e rifiuta un job gia'
--     presente nella catena in corso, con un tetto di profondita';
--   * il momento in cui scatta e' la CHIUSURA dell'esecuzione. Per i servizi che rispondono 202 la
--     chiusura arriva dopo, via /batch-execution/{id}/finish: agganciarsi alla risposta HTTP farebbe
--     partire il secondo job mentre il primo sta ancora lavorando.
--
-- Il campo sta sulla SOTTOSCRIZIONE e non sulla definizione: la stessa lavorazione puo' essere
-- schedulata piu' volte con seguiti diversi (o senza seguito), ed e' una scelta di esercizio, non una
-- proprieta' del servizio.
--
-- MySQL 8 non ha ADD COLUMN IF NOT EXISTS: idempotenza via INFORMATION_SCHEMA + SQL dinamico.
-- Eseguire su db_base (MySQL 8). Rieseguibile senza errori.
-- ============================================================================

SET @col_job_succ := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'db_base'
    AND TABLE_NAME   = 'batch_subscription'
    AND COLUMN_NAME  = 'job_successivo'
);
SET @ddl_job_succ := IF(@col_job_succ = 0,
  'ALTER TABLE `db_base`.`batch_subscription`
     ADD COLUMN `job_successivo` VARCHAR(100) NULL AFTER `enabled`',
  'DO 0');
PREPARE s FROM @ddl_job_succ; EXECUTE s; DEALLOCATE PREPARE s;

-- ----------------------------------------------------------------------------
-- Verifica
-- ----------------------------------------------------------------------------
-- SHOW COLUMNS FROM `db_base`.`batch_subscription` LIKE 'job_successivo';
--
-- Catene configurate:
-- SELECT s.id, d.code AS job, s.job_successivo, s.id_intermediario, s.cron_expression
--   FROM `db_base`.`batch_subscription` s
--   JOIN `db_base`.`batch_definition` d ON d.id = s.batch_definition_id
--  WHERE s.job_successivo IS NOT NULL;
--
-- Catene che puntano a un codice inesistente (refuso nel valore): non lanciano nulla, si vedono qui.
-- SELECT DISTINCT s.job_successivo FROM `db_base`.`batch_subscription` s
--  WHERE s.job_successivo IS NOT NULL
--    AND s.job_successivo NOT IN (SELECT code FROM `db_base`.`batch_definition`);
-- ============================================================================
