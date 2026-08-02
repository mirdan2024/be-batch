-- ============================================================================
-- Voce di menu "Impostazioni -> Schedulazioni SFTP" (codice: settings-sftp-schedulazioni).
--
-- Gemella di 02_menu_batch_schedulazioni.sql: stessa sezione, stessi ruoli.
-- Da eseguire sul database db_base (MySQL 8).
-- Le PK di menu/ruoli_menu NON sono auto_increment: gli id sono calcolati a runtime.
-- Dopo l'esecuzione l'utente deve ri-effettuare il LOGIN (i menuCodes si caricano al login).
--
-- Idempotente: se la voce esiste gia' non viene duplicata.
-- ============================================================================

-- 1) Nuova voce di menu
SET @esiste = (SELECT COUNT(*) FROM `db_base`.`menu` WHERE `codice` = 'settings-sftp-schedulazioni');
SET @new_menu_id = (SELECT COALESCE(MAX(id), 0) + 1 FROM `db_base`.`menu`);

INSERT INTO `db_base`.`menu` (`id`, `descrizione`, `codice`)
SELECT @new_menu_id, 'Schedulazioni SFTP', 'settings-sftp-schedulazioni'
WHERE @esiste = 0;

-- 2) Assegna la nuova voce agli stessi ruoli che hanno gia' 'settings-batch-schedulazioni'
--    (chi amministra le schedulazioni batch amministra anche i trasferimenti SFTP).
SET @base_rm = (SELECT COALESCE(MAX(id_ruoli_menu), 0) FROM `db_base`.`ruoli_menu`);

INSERT INTO `db_base`.`ruoli_menu` (`id_ruoli_menu`, `fk_menu`, `fk_ruolo`)
SELECT @base_rm + ROW_NUMBER() OVER (ORDER BY r.fk_ruolo), @new_menu_id, r.fk_ruolo
FROM (
    SELECT DISTINCT rm.fk_ruolo
    FROM `db_base`.`ruoli_menu` rm
    JOIN `db_base`.`menu` m ON m.id = rm.fk_menu
    WHERE m.codice = 'settings-batch-schedulazioni'
) r
WHERE @esiste = 0;

-- Verifica (facoltativa):
-- SELECT m.id, m.codice, rm.fk_ruolo FROM db_base.menu m
--   JOIN db_base.ruoli_menu rm ON rm.fk_menu = m.id
--   WHERE m.codice IN ('settings-sftp-schedulazioni','settings-batch-schedulazioni')
--   ORDER BY m.codice, rm.fk_ruolo;
