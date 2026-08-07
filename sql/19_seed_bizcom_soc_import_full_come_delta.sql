-- ============================================================================
-- be-batch — Definition per il caricamento "FILE FULL TRATTATO COME DELTA" delle liste societarie.
--
-- E' la modalita' adatta alla fornitura REALE: il fornitore manda sempre l'export integrale, ma
-- ricaricarlo con TRUNCATE+reload (definition 'bizcom-soc-import') distrugge la versione precedente e
-- quindi non lascia modo di sapere che cosa e' cambiato — che e' esattamente cio' che serve al
-- monitoraggio societario.
--
-- Che cosa fa: il file entra nelle tabelle di appoggio soc_*_stg (le uniche svuotate), si confrontano le
-- impronte hash_riga per chiave naturale e si applica SOLO la differenza:
--    nuovi      -> INSERT
--    variati    -> la versione corrente viene copiata in soc_*_storico e la riga aggiornata IN PLACE
--                  (l'id resta stabile: "Ric. Societa'" apre il dettaglio per id)
--    invariati  -> NON toccati (e' il caso piu' frequente: e' cio' che evita variazioni fantasma)
--    usciti     -> sulle tabelle FIGLIE (soci, esponenti, unita' locali) una riga assente dal file, per
--                  una societa' che l'export menziona, e' un socio uscito / una carica cessata / una
--                  unita' chiusa: va nello storico e lascia le correnti. Sul PADRE e sui BILANCI no —
--                  una societa' assente non e' cessata, e i bilanci vecchi non spariscono, e' il file
--                  che porta solo gli ultimi esercizi.
--
-- Salvagente: se il file porta molte meno righe di quante ce ne sono a database (default oltre il 20%
-- in meno) il caricamento si FERMA senza applicare nulla — un export troncato lascerebbe mezzo archivio
-- senza aggiornamenti facendo credere che sia aggiornato. Per accettare un calo legittimo, aggiungere
-- &caloMax=<percentuale> all'URL (oppure -1 per disattivare il controllo).
--
-- Prerequisiti:
--   1) be-openapi/sql/12_bizcom_soc_sincro.sql eseguito su db_aidati (impronte, tabelle _stg,
--      soc_import_run) — oltre a 03/04/05 gia' richiesti dal caricamento;
--   2) i 6 CSV su be-storage sotto <intermediario>/<type>/<folder>/ (default 0/bizcom/liste);
--   3) su be-openapi: BIZCOM_IMPORT_ENABLED=true, API_SERVICE_STORAGE, TOKEN_CHIAMATE_INTERNE,
--      API_SERVICE_BATCH (senza quest'ultima la telecronaca non viene scritta, in silenzio).
--
-- NB: URL DIRETTO a be-openapi (porta 8094, context /be-openapi), stessa scelta del seed 09: nella
-- tabella routing del gateway non esiste una riga 'bizcom-import'. In produzione sostituire con l'host
-- del servizio.
--
-- Eseguire su db_base (MySQL 8). Idempotente: se il codice esiste gia' non viene duplicato.
-- ============================================================================

SET @esiste = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'bizcom-soc-import-full-come-delta');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `stop_url`, `data_creazione`)
SELECT
  'bizcom-soc-import-full-come-delta',
  'Caricamento liste societarie bizcom: file INTEGRALE trattato come delta (staging + confronto impronte). Applica solo le differenze: nuovi, variati con storicizzazione della versione precedente, invariati non toccati, nessuna cancellazione',
  'http://localhost:8094/be-openapi/bizcom-import/run?mode=FULL_COME_DELTA',
  '{}',
  'POST',
  1,
  -- Stesso job control delle altre due modalita' ("bizcom-import"): il loader e' lo stesso, e due
  -- caricamenti insieme non devono poter partire.
  'http://localhost:8094/be-openapi/batch-control/bizcom-import/stop',
  NOW()
WHERE @esiste = 0;

-- Se la definition esisteva gia' senza stop_url (script eseguito prima del 10), lo si valorizza.
UPDATE `db_base`.`batch_definition`
   SET `stop_url` = 'http://localhost:8094/be-openapi/batch-control/bizcom-import/stop'
 WHERE `code` = 'bizcom-soc-import-full-come-delta' AND (`stop_url` IS NULL OR `stop_url` = '');

-- ----------------------------------------------------------------------------
-- SCHEDULAZIONE (batch_subscription): NON si inserisce via SQL — `password_enc` deve essere cifrata da
-- be-batch. Si crea dalla pagina Impostazioni -> Schedulazioni batch, sulla definition
-- 'bizcom-soc-import-full-come-delta', con la cadenza con cui il fornitore deposita i file.
--
-- CONCATENAMENTO CONSIGLIATO: sulla schedulazione, campo "job successivo" = 'soc-monitor-rileva'.
-- Cosi' appena il caricamento chiude con esito positivo parte la rilevazione delle variazioni
-- societarie, che e' la ragione per cui questo caricamento esiste. I due job restano avviabili
-- singolarmente.
--
-- ⚠ IL PRIMO GIRO VA GUARDATO PRIMA DI CONCATENARE IL MONITORAGGIO. Conviene lanciarlo SENZA "job
-- successivo", leggere i conteggi e solo dopo agganciare la catena:
--   SELECT tabella, righe_file, nuovi, variati, allineati, invariati, chiusi, duplicati_scartati, esito
--     FROM `db_aidati`.`soc_import_run` ORDER BY id DESC LIMIT 12;
-- Che cosa aspettarsi:
--   * variati = 0 e allineati ~= righe_file. Le righe caricate prima che l'impronta esistesse non sono
--     confrontabili con nulla: vengono allineate e NON segnalate come variazioni (segnalarle vorrebbe
--     dire dichiarare 13 milioni di variazioni mai avvenute). Dal secondo giro i numeri sono quelli veri.
--   * chiusi FUNZIONA GIA' dal primo giro: le uscite si vedono per assenza, non per impronta. Se il
--     database ha accumulato righe fantasma nei caricamenti precedenti (soci usciti mai rimossi), qui
--     escono tutte in una volta — ed e' giusto che vengano ripulite, ma se il monitoraggio parte subito
--     dietro genera un evento per ognuna. Un numero di "chiusi" fuori scala e' anche il primo sintomo di
--     un file incompleto: in quel caso NON lasciare girare il monitoraggio e verificare l'export.
-- ============================================================================
