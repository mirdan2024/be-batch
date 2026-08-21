-- ============================================================================
-- L'intermediario di una schedulazione diventa FACOLTATIVO
--
-- La colonna era NOT NULL, quindi una schedulazione senza cliente non si poteva
-- proprio salvare:
--   ERROR 1048: Column 'id_intermediario' cannot be null
--
-- Nullo significa "non ristretta a un cliente". Cosa comporti lo dichiara la
-- DEFINIZIONE del job, con batch_definition.ambito_intermediario:
--   NULL / 'NESSUNO'  il job non e' per cliente: il form non chiede nulla;
--   'FILTRO'          vuoto = tutti, valorizzato = solo quel cliente;
--   'UTENZA'          l'ambito sono le credenziali, il campo e' un'etichetta.
--
-- ORDINE DI ESECUZIONE: questo script PRIMA del rilascio di be-batch, insieme a
-- 01_batch_definition_ambito_intermediario.sql. L'entity non dichiara piu' il
-- campo obbligatorio, ma finche' la colonna resta NOT NULL il salvataggio
-- fallisce a database, non nel codice.
-- ============================================================================

ALTER TABLE `db_base`.`batch_subscription`
    MODIFY COLUMN `id_intermediario` BIGINT NULL;

-- Verifica
-- SHOW COLUMNS FROM `db_base`.`batch_subscription` LIKE 'id_intermediario';
