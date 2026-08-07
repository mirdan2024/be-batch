-- ============================================================================
-- be-batch — colonna `ultimo_aggiornamento` su batch_execution.
--
-- IL PROBLEMA. La rete di sicurezza che chiude le esecuzioni rimaste PENDING (BatchScheduler
-- .chiudiEsecuzioniPiantate) diceva "nessun aggiornamento dal servizio da oltre N ore", ma il suo
-- filtro era `started_at < limite`: misurava la DURATA, non il silenzio. Risultato: un'elaborazione
-- piu' lunga della soglia veniva chiusa come FAILED anche mentre stava lavorando e scrivendo la
-- telecronaca — successo sul caricamento delle liste societarie, che sui volumi reali puo' durare un
-- giorno intero.
--
-- LA CORREZIONE. Ogni riga di telecronaca aggiorna questa colonna: e' il battito del servizio. La rete
-- di sicurezza guarda quello, quindi chiude solo cio' che TACE davvero da N ore — un servizio morto,
-- irraggiungibile o con l'URL di callback sbagliato, che e' il caso per cui era stata scritta.
--
-- Valore iniziale = started_at per le righe esistenti: senza, le esecuzioni gia' in corso al momento
-- del rilascio verrebbero considerate mute dall'inizio dei tempi e chiuse al primo giro del controllo.
--
-- Eseguire su db_base (MySQL 8). Idempotente.
-- ============================================================================

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = 'db_base' AND TABLE_NAME = 'batch_execution'
             AND COLUMN_NAME = 'ultimo_aggiornamento');
SET @sql := IF(@c = 0,
  'ALTER TABLE `db_base`.`batch_execution` ADD COLUMN `ultimo_aggiornamento` DATETIME NULL AFTER `started_at`',
  'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE `db_base`.`batch_execution` SET `ultimo_aggiornamento` = `started_at`
 WHERE `ultimo_aggiornamento` IS NULL;

-- Il controllo gira ogni 10 minuti e cerca le PENDING silenziose: senza indice e' una scansione della
-- tabella delle esecuzioni, che cresce a ogni giro di ogni schedulazione.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = 'db_base' AND TABLE_NAME = 'batch_execution'
             AND INDEX_NAME = 'idx_batchexec_pendenti');
SET @sql := IF(@c = 0,
  'ALTER TABLE `db_base`.`batch_execution` ADD KEY `idx_batchexec_pendenti` (`status`, `ended_at`, `ultimo_aggiornamento`)',
  'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- Verifica: le esecuzioni in corso e da quanto tacciono.
-- SELECT id, status, started_at, ultimo_aggiornamento,
--        TIMESTAMPDIFF(MINUTE, ultimo_aggiornamento, NOW()) AS silenzio_minuti
--   FROM `db_base`.`batch_execution` WHERE status = 'PENDING' AND ended_at IS NULL
--  ORDER BY ultimo_aggiornamento;
-- ============================================================================
