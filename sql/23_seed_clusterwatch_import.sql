-- ============================================================================
-- 23_seed_clusterwatch_import.sql  (db_base)
--
-- Le due schedulazioni del caricamento della lista Clusterwatch. Sono definizioni, non
-- sottoscrizioni: la periodicita' e le credenziali si impostano dalla pagina Schedulazioni batch,
-- dove queste voci compaiono da sole appena esistono queste righe (la pagina legge la tabella, non
-- ha elenchi scritti nel codice).
--
--   clusterwatch-import        -> ?mode=FULL   svuota e ricarica tutto: primo popolamento
--   clusterwatch-import-delta  -> ?mode=DELTA  applica la sola differenza: uso quotidiano
--
-- La modalita' sta nella QUERY della definizione e non nel corpo: il servizio dà la precedenza alla
-- query, quindi chi configura una sottoscrizione non puo' trasformare un delta in un caricamento
-- integrale scrivendo nel corpo della chiamata.
--
-- `stop_url` punta a /batch-control/clusterwatch-import/stop, cioe' al nome con cui il caricamento si
-- registra fra i job: e' quello che rende funzionante il pulsante di interruzione. Un nome diverso
-- creerebbe un job vuoto e la richiesta di stop non arriverebbe a nessuno.
--
-- Idempotente.
-- ============================================================================

SET @esisteFull = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'clusterwatch-import');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `stop_url`, `data_creazione`)
SELECT 'clusterwatch-import',
       'Clusterwatch: caricamento integrale della lista (svuota e ricarica le tabelle cw_*)',
       'http://localhost:8094/be-openapi/clusterwatch-import/run?mode=FULL',
       '{}', 'POST', 1,
       'http://localhost:8094/be-openapi/batch-control/clusterwatch-import/stop', NOW()
WHERE @esisteFull = 0;

SET @esisteDelta = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'clusterwatch-import-delta');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `stop_url`, `data_creazione`)
SELECT 'clusterwatch-import-delta',
       'Clusterwatch: aggiornamento della lista (inserisce le nuove, aggiorna le variate, lascia le altre)',
       'http://localhost:8094/be-openapi/clusterwatch-import/run?mode=DELTA',
       '{}', 'POST', 1,
       'http://localhost:8094/be-openapi/batch-control/clusterwatch-import/stop', NOW()
WHERE @esisteDelta = 0;

-- ----------------------------------------------------------------------------
-- Verifica
-- ----------------------------------------------------------------------------
-- SELECT id, code, endpoint_url, stop_url, enabled FROM db_base.batch_definition
--   WHERE code LIKE 'clusterwatch%';
-- ============================================================================
