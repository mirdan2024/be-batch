-- ============================================================================
-- be-batch (db_base) — Schedulazioni SFTP.
--
-- Trasferimenti di file schedulati, nelle due direzioni:
--   SFTP_TO_STORAGE   scarica dal server SFTP e scrive su be-storage
--   STORAGE_TO_SFTP   legge da be-storage e carica sul server SFTP
--
-- Funziona come le "Schedulazioni batch" (cron, esegui ora, blocca/sblocca, storico con telecronaca,
-- interruzione), ma invece di chiamare un endpoint HTTP esegue il trasferimento.
--
-- CREDENZIALI: host, porta, username e password. La password e' CIFRATA a riposo con lo stesso
-- CredentialCipher delle schedulazioni batch (batch.credentials.secret/salt): il DB non la vede mai
-- in chiaro e non viene mai restituita in lettura dalle API.
--
-- Eseguire su db_base (MySQL 8). Idempotente: CREATE TABLE IF NOT EXISTS.
-- ============================================================================

CREATE TABLE IF NOT EXISTS `db_base`.`sftp_schedule` (
  `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
  `nome`                  VARCHAR(255) NOT NULL,              -- etichetta leggibile in elenco
  `id_intermediario`      BIGINT       NOT NULL,
  -- Direzione del trasferimento: SFTP_TO_STORAGE | STORAGE_TO_SFTP
  `direzione`             VARCHAR(20)  NOT NULL,

  -- --- Connessione SFTP ---
  `sftp_host`             VARCHAR(255) NOT NULL,
  `sftp_port`             INT          NOT NULL DEFAULT 22,
  `sftp_username`         VARCHAR(255) NOT NULL,
  `sftp_password_enc`     TEXT         NOT NULL,              -- CIFRATA a riposo, mai in chiaro
  `sftp_path`             VARCHAR(1000) NOT NULL,             -- cartella remota (origine o destinazione)

  -- --- Selezione file ---
  -- Filtro sul nome file. Vuoto/NULL = tutti i file della cartella. Due modi:
  --   * SENZA jolly = PREFISSO ("il nome deve iniziare per"), es. 'LIS_' prende LIS_20260802.csv
  --     e scarta ALTRO_20260802.csv;
  --   * CON * o ?   = glob classico su tutto il nome, es. '*.csv', 'LIS_*_DEF.csv'.
  -- Puo' contenere SEGNAPOSTO DI DATA fra percentuali, risolti a ogni esecuzione nel fuso della
  -- schedulazione: 'LIS_%YYYYMMDD%' -> 'LIS_20260802', 'LIS_%DD-MM-YYYY%' -> 'LIS_02-08-2026'.
  -- Token: YYYY anno, YY anno a 2 cifre, MM mese, DD giorno, HH ora, MI minuti, SS secondi.
  -- Scostamento in giorni dopo una barra verticale: '%YYYYMMDD|-1%' = giorno precedente (job notturni).
  `file_pattern`          VARCHAR(255) NULL,

  -- --- Destinazione/origine su be-storage: <intermediario>/<type>/<folder> ---
  `storage_intermediario` VARCHAR(100) NOT NULL,
  `storage_type`          VARCHAR(100) NOT NULL,
  `storage_folder`        VARCHAR(255) NOT NULL,

  -- --- Cosa fare del file di ORIGINE dopo un trasferimento riuscito ---
  -- LASCIA | CANCELLA | SPOSTA (in post_transfer_folder, sottocartella dell'origine)
  `post_transfer`         VARCHAR(20)  NOT NULL DEFAULT 'LASCIA',
  `post_transfer_folder`  VARCHAR(255) NULL,

  -- --- Schedulazione (identica alle batch) ---
  `cron_expression`       VARCHAR(100) NULL,                  -- NULL = solo "Esegui ora" (manuale)
  `timezone`              VARCHAR(100) NOT NULL DEFAULT 'Europe/Rome',
  `enabled`               BOOLEAN      NOT NULL DEFAULT TRUE,
  `last_run_at`           DATETIME     NULL,
  `next_run_at`           DATETIME     NULL,
  `start_at`              DATETIME     NULL,                  -- decorrenza; NULL = nessun vincolo

  `id_utente_admin`       BIGINT       NULL,
  `data_creazione`        DATETIME     NOT NULL,
  `data_cessazione`       DATETIME     NULL,                  -- NULL = schedulazione attiva
  PRIMARY KEY (`id`),
  KEY `idx_sftp_schedule_next_run` (`enabled`, `next_run_at`),
  KEY `idx_sftp_schedule_interm` (`id_intermediario`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS `db_base`.`sftp_execution` (
  `id`                BIGINT      NOT NULL AUTO_INCREMENT,
  `sftp_schedule_id`  BIGINT      NOT NULL,
  `status`            VARCHAR(20) NOT NULL,                   -- PENDING | COMPLETED | FAILED | INTERROTTA
  `started_at`        DATETIME    NOT NULL,
  `ended_at`          DATETIME    NULL,
  `file_trasferiti`   INT         NULL,
  `byte_trasferiti`   BIGINT      NULL,
  `error_message`     TEXT        NULL,
  -- Telecronaca: file per file, come per le esecuzioni batch.
  `log`               LONGTEXT    NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_sftp_execution_schedule`
    FOREIGN KEY (`sftp_schedule_id`) REFERENCES `db_base`.`sftp_schedule` (`id`),
  KEY `idx_sftp_execution_schedule` (`sftp_schedule_id`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- Verifica
-- ----------------------------------------------------------------------------
-- SHOW TABLES FROM `db_base` LIKE 'sftp%';
-- SELECT id, nome, direzione, sftp_host, cron_expression, enabled FROM `db_base`.`sftp_schedule`;
