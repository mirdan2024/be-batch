-- ============================================================================
-- 26_batch_descrizioni_liste_societarie.sql  (db_base)
--
-- Descrizioni dei lavori delle liste societarie senza i nomi dei fornitori: sono i testi che la pagina
-- delle schedulazioni mostra (28-09-2026). I CODICI dei lavori restano quelli di sempre: li usano le
-- schedulazioni, il lavoro successivo della catena e la guida.
--
-- Solo testi. Eseguire su db_base (MySQL 8). Idempotente.
-- ============================================================================

UPDATE `db_base`.`batch_definition`
   SET `description` = 'Caricamento FULL liste societarie: legge i 6 file AEGISX_EXPORT_*.csv da be-storage e ricarica integralmente le tabelle soc_* di db_aidati (TRUNCATE + reload)'
 WHERE `code` = 'bizcom-soc-import';

UPDATE `db_base`.`batch_definition`
   SET `description` = 'Caricamento DELTA liste societarie: applica la differenza del file (nuovi, variati con storicizzazione della versione precedente, uscite sulle tabelle figlie), senza TRUNCATE delle correnti'
 WHERE `code` = 'bizcom-soc-import-delta';

UPDATE `db_base`.`batch_definition`
   SET `description` = 'Caricamento liste societarie: file INTEGRALE trattato come delta (staging + confronto impronte). Applica solo le differenze: nuovi, variati e usciti con storicizzazione della versione precedente, invariati non toccati. Le colonne nuove del file si salvano; se si ferma, riprende dal punto raggiunto'
 WHERE `code` = 'bizcom-soc-import-full-come-delta';

UPDATE `db_base`.`batch_definition`
   SET `description` = 'Monitoraggio societario dalle liste societarie: rileva le variazioni delle societa'' monitorate e crea le notifiche (non per unita'' locali, bilanci e cambi di sede)'
 WHERE `code` = 'soc-monitor-rileva';

-- Verifica
-- SELECT `code`, `description` FROM `db_base`.`batch_definition`
--  WHERE `code` IN ('bizcom-soc-import', 'bizcom-soc-import-delta', 'bizcom-soc-import-full-come-delta', 'soc-monitor-rileva');
