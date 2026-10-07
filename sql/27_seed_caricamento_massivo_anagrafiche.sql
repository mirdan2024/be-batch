-- ============================================================================
-- 27_seed_caricamento_massivo_anagrafiche.sql  (db_base)
--
-- DUE SCHEDULAZIONI PER IL CARICAMENTO MASSIVO DELLE ANAGRAFICHE DAI FILE DELLO STORAGE (04-10-2026)
--
--   caricamento-massivo-anagrafiche   prende i file CSV presenti in
--                                     <intermediario>/CARICAMENTI/ANAGS/INPUT e li carica, uno alla volta,
--                                     con l'operazione scritta nel Body della schedulazione
--   caricamenti-storico-purge         cancella i file piu' vecchi di N giorni (30 se il Body non dice altro)
--                                     dagli storici (INPUT/storico e OUTPUT/storico) e, dalla cartella OUTPUT,
--                                     gli archivi di esito che nessuno ha consegnato
--
-- Sono definizioni, non sottoscrizioni: periodicita', credenziali e parametri si impostano dalla pagina
-- Sistema -> Schedulazioni batch, dove le due voci compaiono da sole appena esistono queste righe. La
-- pagina ha anche la scheda di ciascuna (pulsante Guida), con i parametri e gli esempi.
--
-- I PARAMETRI stanno nel campo "Body della chiamata (JSON)" della schedulazione, come per gli altri job:
--   caricamento   {"operazione":"MONITUS_AI","file":"monitus_*.csv","output":"EXCEL","lingua":"it"}
--                 operazione = una delle operazioni della pagina Anagrafica - Caricamento Massivo
--                 (senza: SOLO_CARICAMENTO); file = filtro sui nomi; output = COMPLETO oppure EXCEL
--   pulizia       {"giorni":"60"}
--
-- PERCHE' UNA SOTTOSCRIZIONE PER INTERMEDIARIO (ambito UTENZA). Cartelle e anagrafiche sono del cliente:
-- i due job lavorano sull'intermediario dell'utenza con cui il batch si autentica, quindi serve una
-- sottoscrizione per ogni cliente che usa il flusso. Il campo Intermediario del form e' solo un'etichetta.
--
-- IL FLUSSO COMPLETO si compone con le Schedulazioni SFTP, che esistono gia':
--   1. SFTP -> storage     <intermediario> / CARICAMENTI / ANAGS/INPUT          i file da caricare
--   2. caricamento-massivo-anagrafiche                                           li carica e li archivia
--   3. storage -> SFTP     <intermediario> / CARICAMENTI / ANAGS/OUTPUT         consegna gli archivi di esito
--                          dopo il trasferimento: SPOSTA in "storico"
--   4. caricamenti-storico-purge                                                 svuota i due storici e toglie
--                                                                                da OUTPUT gli esiti mai consegnati
-- Le cartelle non vanno create a mano: nascono alla prima esecuzione, o aprendo la pagina del caricamento.
--
-- PERCHE' LA PULIZIA TOCCA ANCHE OUTPUT. Un archivio di esito contiene i report del caricamento. Se la
-- consegna SFTP (passo 3) non e' configurata, o si ferma, gli archivi resterebbero in OUTPUT senza una
-- scadenza: dopo i giorni di conservazione la pulizia li cancella, anche se nessuno li ha mai ricevuti.
-- La cartella INPUT invece non si tocca: li' stanno i file che aspettano di essere caricati.
--
-- `stop_url` punta a /batch-control/<nome del job>/stop, cioe' al nome con cui il job si registra: e'
-- quello che rende funzionante il pulsante di interruzione.
--
-- NB: URL DIRETTI a be-anag (porta 8085, context /anag): be-batch chiama i servizi direttamente. Negli
-- ambienti rilasciati l'host e' quello del servizio, come per le altre definizioni di be-anag.
--
-- PREREQUISITO: be-anag/sql/53_anagrafica_file_job_storage.sql su ogni schema tenant, e be-anag
-- rilasciato con questi endpoint.
--
-- Eseguire su db_base (MySQL 8). Idempotente: se il codice esiste gia' non viene duplicato.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) Caricamento dei file in ingresso
-- ----------------------------------------------------------------------------
SET @esisteCar = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'caricamento-massivo-anagrafiche');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `stop_url`, `ambito_intermediario`, `data_creazione`)
SELECT 'caricamento-massivo-anagrafiche',
       'Caricamento Massivo: carica i file CSV di anagrafiche presenti nella cartella CARICAMENTI/ANAGS/INPUT dello storage (arrivati via SFTP), con l''operazione indicata nel Body; i file caricati passano in INPUT/storico e l''esito di ognuno in OUTPUT',
       'http://localhost:8085/anag/anagrafica/massivo-batch/run',
       '{}', 'POST', 1,
       'http://localhost:8085/anag/batch-control/caricamento-massivo-anagrafiche/stop',
       'UTENZA', NOW()
WHERE @esisteCar = 0;

-- ----------------------------------------------------------------------------
-- 2) Pulizia degli storici e degli esiti non consegnati
-- ----------------------------------------------------------------------------
SET @esistePul = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'caricamenti-storico-purge');
SET @descrizionePul = 'Pulizia del Caricamento Massivo: cancella i file piu'' vecchi di 30 giorni (giorni configurabili nel Body) dagli storici CARICAMENTI/ANAGS/INPUT/storico e OUTPUT/storico e, dalla cartella OUTPUT, gli archivi di esito mai consegnati';

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `http_method`, `enabled`, `stop_url`, `ambito_intermediario`, `data_creazione`)
SELECT 'caricamenti-storico-purge',
       @descrizionePul,
       'http://localhost:8085/anag/anagrafica/massivo-batch/pulizia',
       '{}', 'POST', 1,
       'http://localhost:8085/anag/batch-control/caricamenti-storico-purge/stop',
       'UTENZA', NOW()
WHERE @esistePul = 0;

-- La descrizione si riallinea anche se la riga c'e' gia': la prima stesura di questo script parlava dei soli
-- storici, e la pagina delle schedulazioni mostra questo testo.
UPDATE `db_base`.`batch_definition` SET `description` = @descrizionePul
 WHERE `code` = 'caricamenti-storico-purge';

-- L'ambito si riallinea anche quando le righe ci sono gia': lo script 01 (ambito per intermediario), se
-- rieseguito, lo azzera su tutti i job, e senza ambito il form non lascerebbe indicare l'intermediario con
-- cui ritrovare in elenco la schedulazione di ciascun cliente.
UPDATE `db_base`.`batch_definition` SET `ambito_intermediario` = 'UTENZA'
 WHERE `code` IN ('caricamento-massivo-anagrafiche', 'caricamenti-storico-purge');

-- ----------------------------------------------------------------------------
-- Verifica
-- ----------------------------------------------------------------------------
SELECT `id`, `code`, `endpoint_url`, `stop_url`, IFNULL(`ambito_intermediario`, '(NULL)') AS `ambito`, `enabled`,
       LEFT(`description`, 60) AS `descrizione`
  FROM `db_base`.`batch_definition`
 WHERE `code` IN ('caricamento-massivo-anagrafiche', 'caricamenti-storico-purge');
