-- ============================================================================
-- be-batch — colonna `ordine` su batch_subscription: ordinamento manuale dell'elenco.
--
-- PERCHE'. Nella pagina Sistema -> Schedulazioni batch l'ordine delle righe non e' una preferenza
-- estetica: i lavori vanno letti a gruppi (il caricamento delle liste con la rilevazione delle
-- variazioni che lo segue, le schedulazioni dello stesso cliente una sotto l'altra). Con l'elenco
-- ordinato per id, un lavoro aggiunto dopo finisce in fondo e il gruppo si spezza — e chi guarda deve
-- ricostruire a mente quali righe vanno insieme.
--
-- Ordinamento MANUALE, con le frecce, e non automatico per codice o per cliente: il criterio con cui i
-- lavori si raggruppano lo conosce chi li configura, non e' deducibile dai dati.
--
-- VALORE INIZIALE = id: cosi' l'elenco resta esattamente com'e' oggi e si riordina da li'. Le righe
-- nuove nascono in fondo (max + 1).
--
-- Eseguire su db_base (MySQL 8). Idempotente.
-- ============================================================================

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = 'db_base' AND TABLE_NAME = 'batch_subscription' AND COLUMN_NAME = 'ordine');
SET @sql := IF(@c = 0,
  'ALTER TABLE `db_base`.`batch_subscription` ADD COLUMN `ordine` INT NULL',
  'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- Numerazione iniziale: l'ordine attuale (per id). Tocca solo le righe non ancora numerate, quindi
-- rilanciare lo script non rimescola un ordinamento gia' deciso da qualcuno.
UPDATE `db_base`.`batch_subscription` SET `ordine` = `id` WHERE `ordine` IS NULL;

-- L'elenco si legge SEMPRE ordinato per questa colonna: senza indice diventa una sort a ogni apertura
-- della pagina.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = 'db_base' AND TABLE_NAME = 'batch_subscription'
             AND INDEX_NAME = 'idx_batchsub_ordine');
SET @sql := IF(@c = 0,
  'ALTER TABLE `db_base`.`batch_subscription` ADD KEY `idx_batchsub_ordine` (`ordine`)',
  'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- Verifica:
-- SELECT id, ordine, id_intermediario, batch_definition_id FROM `db_base`.`batch_subscription`
--  ORDER BY ordine, id;
-- ============================================================================
