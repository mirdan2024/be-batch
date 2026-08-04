-- ============================================================================
-- be-batch — RIMOZIONE del job 'soc-cariche-rigenera'.
--
-- Il job ricavava la decodifica dei codici carica incrociando i dati caricati (soc_esponenti +
-- soc_generale). Approccio abbandonato: la tabella db_aidati.soc_cariche viene ora compilata a mano
-- fuori dall'applicazione, quindi non c'e' piu' niente da schedulare. be-openapi la legge e basta.
--
-- Eseguire su db_base (MySQL 8) SOLO se era stata inserita la definition (file 16 precedente).
-- Idempotente: se non c'e' nulla non fa nulla.
--
-- ATTENZIONE ALL'ORDINE: batch_execution -> batch_subscription -> batch_definition. Le FK
-- (01_batch_schema.sql) impediscono di cancellare la definition finche' esistono schedulazioni, e le
-- schedulazioni finche' esistono esecuzioni. In alternativa si cancella la schedulazione dalla UI
-- (Impostazioni -> Schedulazioni batch) e poi si esegue solo l'ultima DELETE.
-- ============================================================================

SET @def = (SELECT `id` FROM `db_base`.`batch_definition` WHERE `code` = 'soc-cariche-rigenera');

-- Storico delle esecuzioni del job (log di avanzamento compreso).
DELETE FROM `db_base`.`batch_execution`
 WHERE `batch_subscription_id` IN (
       SELECT `id` FROM `db_base`.`batch_subscription` WHERE `batch_definition_id` = @def);

-- Schedulazioni che puntano al job.
DELETE FROM `db_base`.`batch_subscription` WHERE `batch_definition_id` = @def;

-- La definition.
DELETE FROM `db_base`.`batch_definition` WHERE `id` = @def;

-- Verifica: deve restituire 0 righe.
-- SELECT * FROM `db_base`.`batch_definition` WHERE `code` = 'soc-cariche-rigenera';
-- ============================================================================
