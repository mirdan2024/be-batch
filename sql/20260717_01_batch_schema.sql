-- ============================================================================
-- be-batch — Schema completo del servizio di schedulazione batch.
-- Crea il database (se assente) e le tre tabelle del batch, allineate alle entità JPA
-- (BatchDefinition / BatchSubscription / BatchExecution). be-batch NON usa ddl-auto: lo schema
-- non viene creato da Hibernate, quindi va applicato con questo script.
--
-- be-batch gira sullo stesso database di be-base: db_base (vedi spring.datasource.url).
-- Convenzione del workspace: data_cessazione NULL = record attivo, valorizzata = disattivato.
-- Idempotente (IF NOT EXISTS): può essere rieseguito senza errori.
-- Eseguire una volta su MySQL.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `db_base`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- Definizione del batch: il "cosa" (endpoint, metodo, body di default). Popolata anche in automatico
-- dall'auto-registrazione dei servizi (RestServiceRegistryService).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `db_base`.`batch_definition` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(100) NOT NULL,
  `description` VARCHAR(500) NULL,
  `endpoint_url` VARCHAR(1000) NOT NULL,
  `body_json` TEXT NULL,                          -- template body di default; NULL per i GET
  `http_method` VARCHAR(10) NOT NULL,             -- enum HttpMethodType (GET/POST) come stringa
  `enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  `data_creazione` DATETIME NOT NULL,
  `data_cessazione` DATETIME NULL,                -- NULL = definizione attiva
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_definition_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- Sottoscrizione: il "quando/per chi" (cron + fuso + intermediario), con eventuali override di
-- parametri URL (params_json) e body (body_json) rispetto alla definizione.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `db_base`.`batch_subscription` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `id_intermediario` BIGINT NOT NULL,
  `batch_definition_id` BIGINT NOT NULL,
  `cron_expression` VARCHAR(100) NOT NULL,
  `username` VARCHAR(255) NOT NULL,               -- utenza con cui la schedulazione esegue il servizio
  `password_enc` TEXT NOT NULL,                   -- password CIFRATA a riposo (mai in chiaro)
  `timezone` VARCHAR(100) NOT NULL DEFAULT 'Europe/Rome',
  `enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  `last_run_at` DATETIME NULL,
  `next_run_at` DATETIME NULL,
  `start_at` DATETIME NULL,                        -- decorrenza: non parte prima; NULL = nessun vincolo
  `params_json` TEXT NULL,                        -- sostituzioni {chiave} nell'endpoint_url
  `body_json` TEXT NULL,                          -- body effettivamente inviato alla chiamata
  `id_utente_admin` BIGINT NOT NULL,
  `data_creazione` DATETIME NOT NULL,
  `data_cessazione` DATETIME NULL,                -- NULL = sottoscrizione attiva
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_batch_subscription_definition`
    FOREIGN KEY (`batch_definition_id`) REFERENCES `db_base`.`batch_definition` (`id`),
  KEY `idx_batch_subscription_next_run` (`enabled`, `next_run_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- Esecuzione: una riga per ogni scatto dello scheduler, con esito e risposta del servizio target.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `db_base`.`batch_execution` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_subscription_id` BIGINT NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `started_at` DATETIME NOT NULL,
  `ended_at` DATETIME NULL,
  `response_code` INT NULL,
  `error_message` TEXT NULL,
  `response_body` LONGTEXT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_batch_execution_subscription`
    FOREIGN KEY (`batch_subscription_id`) REFERENCES `db_base`.`batch_subscription` (`id`),
  KEY `idx_batch_execution_subscription` (`batch_subscription_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
