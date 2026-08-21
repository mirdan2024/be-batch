-- ============================================================================
-- Ambito per intermediario dichiarato dalla DEFINIZIONE del job
--
-- PERCHE'. Il form "Nuova schedulazione" chiedeva un intermediario per ogni
-- job, anche per quelli che lavorano su dati comuni a tutti (ripasso fonti,
-- purge, caricamenti bizcom). Un campo che si puo' compilare ma che il servizio
-- ignora e' peggio di un campo assente: fa credere di aver ristretto qualcosa.
--
-- La scelta non puo' stare nel front-end: e' una proprieta' del JOB, e chi
-- aggiunge un job domani deve poterla dichiarare qui senza toccare la UI.
--
-- VALORI (NULL = come 'NESSUNO'):
--   NULL / 'NESSUNO'  il job non e' per cliente: il form NON chiede
--                     l'intermediario e la schedulazione vale per tutti;
--   'FILTRO'          il job legge il campo: valorizzato = solo quel cliente,
--                     lasciato vuoto = tutti. E' il caso di report-ristampa,
--                     che riceve l'intermediario nell'header idIntermediario;
--   'UTENZA'          l'ambito sono le CREDENZIALI con cui il batch si
--                     autentica; il campo resta chiedibile ma e' solo
--                     un'etichetta per ritrovare la schedulazione in elenco.
--                     Serve una schedulazione per cliente.
--
-- Perche' tre valori e non un flag: 'UTENZA' e 'FILTRO' si comportano in modo
-- opposto proprio sul caso che conta, cioe' il campo lasciato vuoto. Con un
-- flag solo, il suggerimento sotto al campo tornerebbe a mentire su meta' dei
-- job — che e' il problema da cui siamo partiti.
-- ============================================================================

-- ENUM e non VARCHAR di proposito: i valori ammessi si leggono dallo schema, con
-- un semplice SHOW FULL COLUMNS, senza dover cercare in quale file sono scritti.
-- NULL e' il default e vale "nessun filtro": il job non e' per cliente.
--
-- La creazione e' resa RIESEGUIBILE a mano: MySQL non ha ADD COLUMN IF NOT
-- EXISTS, e senza questa guardia il secondo lancio dello script si ferma qui
-- con "Duplicate column name" — lasciando UPDATE e verifica non eseguite, cioe'
-- facendo credere di aver applicato qualcosa che non e' stato applicato.
SET @esiste := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = 'db_base'
       AND table_name = 'batch_definition'
       AND column_name = 'ambito_intermediario');

SET @ddl := IF(@esiste = 0,
    'ALTER TABLE `db_base`.`batch_definition`
        ADD COLUMN `ambito_intermediario` ENUM(''FILTRO'',''UTENZA'') NULL DEFAULT NULL
        COMMENT ''Ambito per intermediario del job. NULL = non per cliente, il form non chiede l''''intermediario. FILTRO = il job legge il campo: vuoto = tutti, valorizzato = solo quel cliente. UTENZA = l''''ambito sono le credenziali del batch, il campo e'''' solo un''''etichetta.''
        AFTER `stop_url`',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Il tipo si allinea comunque, cosi' una colonna creata VARCHAR da una versione
-- precedente diventa ENUM. ATTENZIONE: cambiare il tipo di una colonna gia'
-- popolata NON e' neutro — la conversione VARCHAR -> ENUM ha gia' azzerato i
-- valori senza un solo warning. Per questo le UPDATE qui sotto vengono DOPO, e
-- la verifica in fondo va guardata.
ALTER TABLE `db_base`.`batch_definition`
    MODIFY COLUMN `ambito_intermediario` ENUM('FILTRO','UTENZA') NULL DEFAULT NULL
    COMMENT 'Ambito per intermediario del job. NULL = non per cliente, il form non chiede l''intermediario. FILTRO = il job legge il campo: vuoto = tutti, valorizzato = solo quel cliente. UTENZA = l''ambito sono le credenziali del batch, il campo e'' solo un''etichetta.';

-- Si riparte sempre da zero: rende lo script rieseguibile.
UPDATE `db_base`.`batch_definition` SET `ambito_intermediario` = NULL;

-- ---------------------------------------------------------------------------
-- CONFIGURAZIONE ATTUALE: quasi tutto NULL, di proposito.
-- ---------------------------------------------------------------------------
-- Solo il job di prova legge il campo Intermediario, ed e' comodo che sia
-- proprio lui: serve a provare la selezione del cliente senza toccare un job
-- che lavora su dati veri.
UPDATE `db_base`.`batch_definition` SET `ambito_intermediario` = 'FILTRO'
 WHERE `code` IN ('ai-search-gemini-prova-batch');

-- TUTTI GLI ALTRI RESTANO NULL, per due ragioni diverse:
--
-- 1) Non possono filtrare, e non e' una scelta. Ripasso fonti, i tre purge e i
--    caricamenti bizcom lavorano su tabelle di db_aidati che NON hanno una
--    colonna intermediario (check_ai, check_openapi, check_risk, soc_*):
--    l'archivio AI e' condiviso fra i tenant per deduplica, quindi non c'e'
--    proprio nulla su cui filtrare.
--
-- 2) Potrebbero, ma si e' scelto di no. `report-ristampa` SA restringersi a un
--    cliente (riceve l'intermediario nell'header idIntermediario) e i tre job
--    per cliente — soc-monitor-rileva e i due Pibisi — prendono l'ambito dalle
--    credenziali con cui il batch si autentica. Per attivarli basta assegnare
--    qui il valore, senza toccare il codice:
--
--    UPDATE `db_base`.`batch_definition` SET `ambito_intermediario` = 'FILTRO'
--     WHERE `code` IN ('report-ristampa');
--
--    UPDATE `db_base`.`batch_definition` SET `ambito_intermediario` = 'UTENZA'
--     WHERE `code` IN ('soc-monitor-rileva', 'pibisi-alerts-process',
--                      'pibisi-alerts-process-all');
--
-- E' esattamente il motivo per cui questa scelta sta a DATABASE e non nel
-- codice: cambiarla e' una riga di SQL per ambiente, non un rilascio.

-- VERIFICA, da eseguire sempre e da GUARDARE: atteso FILTRO sul solo job di
-- prova, NULL su tutti gli altri.
SELECT `code`, IFNULL(`ambito_intermediario`, '(NULL)') AS `ambito`
  FROM `db_base`.`batch_definition` ORDER BY `ambito`, `code`;
