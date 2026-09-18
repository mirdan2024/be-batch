-- ============================================================================
-- 24_seed_clusterwatch_monitor.sql  (db_base)
--
-- LA SCHEDULAZIONE DEL MONITORAGGIO CLUSTERWATCH. E' una definizione, non una sottoscrizione: la
-- periodicita' e le credenziali si impostano dalla pagina Schedulazioni batch, dove questa voce
-- compare da sola appena esiste questa riga.
--
-- QUANDO ESEGUIRLO. Il fornitore manda la lista una volta al mese: il momento giusto e' subito DOPO il
-- caricamento, impostando `job_successivo = 'clusterwatch-monitor'` sulla sottoscrizione di
-- `clusterwatch-import-delta`. A un orario indovinato, un mese su due confronterebbe la lista con se
-- stessa e non troverebbe nulla da segnalare.
--
-- PERCHE' UNA SOTTOSCRIZIONE PER INTERMEDIARIO. La lista e' unica e condivisa, ma i monitoraggi e le
-- evidenze vivono nello schema del cliente: il job lavora sul tenant dell'utenza con cui si autentica,
-- quindi serve una sottoscrizione per ogni cliente che usa il prodotto.
--
-- `stop_url` punta a /batch-control/clusterwatch-monitor/stop, cioe' al nome con cui il job si
-- registra: e' quello che rende funzionante il pulsante di interruzione. Un nome diverso creerebbe un
-- job vuoto e la richiesta di stop non arriverebbe a nessuno.
--
-- Idempotente.
-- ============================================================================

SET @esisteMon = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'clusterwatch-monitor');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `stop_url`, `data_creazione`)
SELECT 'clusterwatch-monitor',
       'Clusterwatch: ricontrolla le posizioni in monitoraggio e crea le evidenze (da eseguire dopo il caricamento della lista)',
       'http://localhost:8085/anag/anagrafica/clusterwatch/monitoraggio/rileva',
       '{}', 'POST', 1,
       'http://localhost:8085/anag/batch-control/clusterwatch-monitor/stop', NOW()
WHERE @esisteMon = 0;

-- ----------------------------------------------------------------------------
-- Verifica
-- ----------------------------------------------------------------------------
-- SELECT id, code, endpoint_url, stop_url, enabled FROM db_base.batch_definition
--   WHERE code LIKE 'clusterwatch%';
-- ============================================================================
