-- ============================================================================
-- L'intermediario di una schedulazione SFTP diventa FACOLTATIVO
--
-- Stessa ragione delle schedulazioni batch: un trasferimento puo' non essere
-- intestato a un cliente. Con la colonna NOT NULL non si poteva nemmeno
-- salvare:
--   ERROR 1048: Column 'id_intermediario' cannot be null
--
-- Nullo = "vale per tutti". In quel caso i file vanno nella cartella CONDIVISA
-- di be-storage, cioe' intermediario 0: e' cio' che finora si scriveva a mano
-- nel campo "Intermediario (storage)" e che ora il form propone da solo.
--
-- ATTENZIONE alla distinzione fra i due campi, che hanno nomi simili:
--   id_intermediario       chi e' l'intestatario della schedulazione (questo);
--   storage_intermediario  PRIMO SEGMENTO del percorso su be-storage
--                          (<intermediario>/<tipo>/<cartella>), che resta
--                          obbligatorio perche' e' il percorso vero.
--
-- ORDINE: questo script PRIMA del rilascio di be-batch.
-- ============================================================================

ALTER TABLE `db_base`.`sftp_schedule`
    MODIFY COLUMN `id_intermediario` BIGINT NULL;

-- Verifica
-- SHOW COLUMNS FROM `db_base`.`sftp_schedule` LIKE 'id_intermediario';
