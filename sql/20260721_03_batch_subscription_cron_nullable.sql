-- ============================================================================
-- be-batch (db_base) — schedulazioni "manuali" (manual-batch).
--
-- Rende cron_expression NULLABLE: una sottoscrizione senza cron e' eseguibile SOLO manualmente
-- ("Esegui ora"); lo scheduler non la seleziona perche' next_run_at resta null.
--
-- Eseguire su db_base (MySQL 8). Idempotente (MODIFY reimposta lo stesso stato).
-- ============================================================================

ALTER TABLE `db_base`.`batch_subscription`
  MODIFY COLUMN `cron_expression` VARCHAR(100) NULL;
