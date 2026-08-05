-- ============================================================================
-- be-batch — Definition per la rilevazione delle variazioni societarie (soc-monitor-rileva).
--
-- COSA FA. Chiama be-anag (POST /anagrafica/monitoraggio-societario/rileva-variazioni): per le societa'
-- in monitoraggio dell'intermediario della schedulazione confronta le liste BIZCOM con la fotografia
-- precedente e trasforma le variazioni in notifiche. Con l'interruttore FONTE_SOCIETARIA_BIZCOM spento
-- il servizio non fa nulla e lo scrive nel log dell'esecuzione: la schedulazione puo' restare attiva
-- anche prima dell'accensione.
--
-- UNA SOTTOSCRIZIONE PER INTERMEDIARIO, NON UNA SOLA. Il caricamento delle liste e' unico e globale,
-- ma i monitoraggi vivono nello schema di ciascun cliente e il servizio lavora sul tenant dell'utenza
-- con cui il batch si autentica. Quindi: una definition (questa) e tante subscription quante sono gli
-- intermediari che usano il monitoraggio societario interno.
--
-- STOP. Il servizio dichiara il job 'soc-monitor-rileva' su /batch-control: stop_url permette di
-- interromperlo dalla pagina Schedulazioni batch senza aspettare la fine (interruzione cooperativa,
-- si ferma fra una societa' e l'altra e quanto gia' rilevato resta salvato).
--
-- URL DIRETTO a be-anag (porta 8085, context /anag). Il prefisso 'anagrafica' e' gia' instradato dal
-- gateway, quindi in alternativa si puo' usare http://localhost:8095/routing/anagrafica/... ; in
-- produzione sostituire con l'host reale del servizio.
--
-- Eseguire su db_base (MySQL 8). Idempotente: se il codice esiste gia' non viene duplicato.
-- ============================================================================

SET @esiste = (SELECT COUNT(*) FROM `db_base`.`batch_definition` WHERE `code` = 'soc-monitor-rileva');

INSERT INTO `db_base`.`batch_definition`
  (`code`, `description`, `endpoint_url`, `body_json`, `stop_url`, `http_method`, `enabled`, `data_creazione`)
SELECT
  'soc-monitor-rileva',
  'Monitoraggio societario da liste BIZCOM: rileva le variazioni delle societa'' monitorate e crea le notifiche',
  'http://localhost:8085/anag/anagrafica/monitoraggio-societario/rileva-variazioni',
  '{}',
  'http://localhost:8085/anag/batch-control/soc-monitor-rileva/stop',
  'POST',
  1,
  NOW()
WHERE @esiste = 0;

-- ----------------------------------------------------------------------------
-- CONCATENAMENTO AL CARICAMENTO DELLE LISTE.
--
-- Ha senso rilevare le variazioni subito dopo che le liste sono state caricate. Il legame NON e'
-- cablato nel codice: si dichiara sulla schedulazione del caricamento, indicando questa definition come
-- job successivo, dalla pagina Impostazioni -> Schedulazioni batch. A fine caricamento CON ESITO
-- POSITIVO be-batch lancia tutte le subscription attive di 'soc-monitor-rileva' (una per intermediario).
-- I due job restano comunque avviabili singolarmente con "Esegui ora".
--
-- Da SQL, sulla subscription del caricamento:
--   UPDATE `db_base`.`batch_subscription` SET `job_successivo` = 'soc-monitor-rileva'
--    WHERE `batch_definition_id` = (SELECT id FROM `db_base`.`batch_definition` WHERE code='bizcom-soc-import');
-- ----------------------------------------------------------------------------

-- Id della definition (serve per creare la schedulazione):
--   SELECT id FROM `db_base`.`batch_definition` WHERE code = 'soc-monitor-rileva';
--
-- SCHEDULAZIONE: crearla dalla pagina Impostazioni -> Schedulazioni batch (l'autocomplete intermediario,
-- il costruttore cron e la cifratura della password fanno tutto loro). NON inserirla via SQL:
-- password_enc deve essere cifrata da be-batch (CredentialCipher).
--
-- Se il job parte in catena dopo il caricamento, la subscription puo' essere creata SENZA cron
-- (cronExpression = null): non parte da sola, la lancia la catena, e resta disponibile "Esegui ora".
-- ============================================================================
