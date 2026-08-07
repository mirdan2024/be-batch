-- ============================================================================
-- be-batch — colonna `ordine` su sftp_schedule: ordinamento manuale dell'elenco.
--
-- Stessa esigenza, stessa soluzione delle schedulazioni batch (vedi 20_batch_subscription_ordine.sql):
-- nella pagina Sistema -> Schedulazioni SFTP l'ordine delle righe serve a tenere vicini i trasferimenti
-- che si leggono insieme — lo scarico e il carico dello stesso flusso, le schedulazioni dello stesso
-- cliente — e ordinando per id un trasferimento aggiunto dopo finisce in fondo, spezzando il gruppo.
--
-- Ordinamento MANUALE con le frecce: il criterio del raggruppamento lo conosce chi configura, dai dati
-- non e' deducibile.
--
-- VALORE INIZIALE = id: l'elenco resta com'e' oggi e si riordina da li'. Le righe nuove nascono in fondo.
--
-- PREREQUISITO: 14_sftp_schema.sql (la tabella sftp_schedule).
-- Eseguire su db_base (MySQL 8). Idempotente.
-- ============================================================================

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = 'db_base' AND TABLE_NAME = 'sftp_schedule' AND COLUMN_NAME = 'ordine');
SET @sql := IF(@c = 0,
  'ALTER TABLE `db_base`.`sftp_schedule` ADD COLUMN `ordine` INT NULL',
  'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- Numerazione iniziale: solo le righe non ancora numerate, cosi' rilanciare lo script non rimescola un
-- ordinamento gia' deciso.
UPDATE `db_base`.`sftp_schedule` SET `ordine` = `id` WHERE `ordine` IS NULL;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = 'db_base' AND TABLE_NAME = 'sftp_schedule'
             AND INDEX_NAME = 'idx_sftpsched_ordine');
SET @sql := IF(@c = 0,
  'ALTER TABLE `db_base`.`sftp_schedule` ADD KEY `idx_sftpsched_ordine` (`ordine`)',
  'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- Verifica:
-- SELECT id, ordine, nome, id_intermediario, direzione FROM `db_base`.`sftp_schedule` ORDER BY ordine, id;
-- ============================================================================
