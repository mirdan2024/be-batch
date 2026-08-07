package it.be.batch.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import it.ai.client.constants.AppConstants;
import it.be.batch.entity.BatchSubscription;
import it.be.batch.repo.BatchSubscriptionRepository;

/**
 * CONCATENAMENTO dei lavori: quando una schedulazione dichiara un {@code job_successivo}, a fine
 * esecuzione riuscita be-batch lancia quel lavoro.
 *
 * <h3>Perche' serve</h3>
 * Alcuni lavori hanno senso solo in fila - prima si caricano le liste societarie, poi si rilevano le
 * variazioni. Senza concatenamento la sequenza si ottiene solo mettendo due cron abbastanza distanti,
 * cioe' indovinando quanto dura il primo: se un giorno il caricamento dura di piu' del previsto, il
 * secondo lavoro parte a meta' e legge dati incompleti.
 *
 * <h3>Quando scatta</h3>
 * Alla CHIUSURA dell'esecuzione e solo se COMPLETED. Non alla risposta HTTP: i servizi lunghi
 * rispondono 202 e chiudono dopo, via {@code /batch-execution/{id}/finish}. Agganciarsi alla risposta
 * farebbe partire il secondo lavoro mentre il primo sta ancora lavorando, che e' esattamente il problema
 * da risolvere.
 *
 * <h3>Che cosa puo' lanciare</h3>
 * Tutte le schedulazioni di quel lavoro, <b>bloccate comprese</b>: "bloccata" ferma il cron, non i lanci
 * espliciti — il pulsante "Esegui ora" gira gia' su una schedulazione bloccata, e la catena e' un lancio
 * esplicito quanto quello. E' anzi la configurazione naturale di un lavoro che deve girare SOLO in coda a
 * un altro. Restano fuori le schedulazioni eliminate e quelle di una definition disattivata.
 *
 * <h3>La catena non rientra su se stessa</h3>
 * Si tiene traccia dei codici gia' attraversati e si rifiuta un lavoro gia' presente nella catena in
 * corso; in piu' c'e' un tetto di profondita'. Un anello (A chiama B, B chiama A) girerebbe altrimenti
 * per sempre.
 */
@Service
public class BatchChainService {

	private static final Logger logger = LoggerFactory.getLogger(BatchChainService.class);

	/** Quanti lavori al massimo in una stessa catena. Oltre, si taglia e si logga. */
	private static final int PROFONDITA_MAX = 5;

	private final BatchSubscriptionRepository subscriptionRepository;
	/**
	 * ObjectProvider e non iniezione diretta: BatchScheduler dipende da BatchExecutor, che dipende da
	 * questo servizio. Chiedendo il bean solo al momento dell'uso il ciclo non si forma all'avvio.
	 */
	private final ObjectProvider<BatchScheduler> scheduler;

	/**
	 * Codici gia' attraversati, per sottoscrizione lanciata dalla catena. Si popola al lancio e si
	 * consuma quando quella sottoscrizione chiude la propria esecuzione. Una sottoscrizione avviata a
	 * mano non ha voce qui: la sua catena parte da zero, che e' il comportamento atteso.
	 */
	private final Map<Long, List<String>> catenaPerSubscription = new ConcurrentHashMap<>();

	public BatchChainService(BatchSubscriptionRepository subscriptionRepository,
			ObjectProvider<BatchScheduler> scheduler) {
		this.subscriptionRepository = subscriptionRepository;
		this.scheduler = scheduler;
	}

	/**
	 * Da chiamare alla chiusura di un'esecuzione. Lancia il seguito solo se l'esito e' positivo: un
	 * caricamento fallito non deve far girare a vuoto cio' che viene dopo, e soprattutto non deve far
	 * elaborare dati vecchi come se fossero nuovi.
	 */
	public void esecuzioneConclusa(BatchSubscription subscription, String status) {
		if (subscription == null) {
			return;
		}
		List<String> catena = catenaPerSubscription.remove(subscription.getId());

		String successivo = subscription.getJobSuccessivo();
		if (successivo == null || successivo.isBlank()) {
			return;
		}
		if (!AppConstants.STATUS_COMPLETED.equalsIgnoreCase(status)) {
			logger.info("Catena: '{}' NON lanciato, l'esecuzione della subscription {} si e' chiusa con stato {}",
					successivo, subscription.getId(), status);
			return;
		}

		String codiceCorrente = (subscription.getBatchDefinition() != null)
				? subscription.getBatchDefinition().getCode()
				: null;

		List<String> percorso = new ArrayList<>(catena != null ? catena : List.of());
		if (codiceCorrente != null) {
			percorso.add(codiceCorrente);
		}

		if (percorso.contains(successivo)) {
			logger.warn("Catena interrotta: '{}' e' gia' stato eseguito in questa catena ({}). Verificare la"
					+ " configurazione del campo 'job successivo': la catena rientra su se stessa.", successivo,
					String.join(" -> ", percorso));
			return;
		}
		if (percorso.size() >= PROFONDITA_MAX) {
			logger.warn("Catena interrotta dopo {} lavori ({}): '{}' non viene lanciato.", percorso.size(),
					String.join(" -> ", percorso), successivo);
			return;
		}

		List<BatchSubscription> daLanciare = subscriptionRepository.findAttiveByDefinitionCode(successivo);
		if (daLanciare.isEmpty()) {
			// Silenzio no: chi ha configurato la catena si aspetta che qualcosa parta. Restano solo due
			// cause — il codice non esiste in batch_definition, oppure la definition e' disattivata o la
			// schedulazione eliminata — perche' le bloccate la catena le lancia.
			logger.warn("Catena: nessuna sottoscrizione lanciabile per il lavoro '{}' (dopo '{}'). Nulla da"
					+ " lanciare: verificare che il codice esista in batch_definition, che la definition sia"
					+ " abilitata e che la schedulazione non sia stata eliminata.", successivo, codiceCorrente);
			return;
		}

		BatchScheduler s = scheduler.getObject();
		for (BatchSubscription succ : daLanciare) {
			catenaPerSubscription.put(succ.getId(), percorso);
			String stato = s.eseguiUnaTantum(succ.getId());
			// Si dichiara quando si lancia una schedulazione bloccata: e' il comportamento voluto (il
			// blocco ferma il cron, non i lanci espliciti) ma davanti a un lavoro partito "da solo" a un
			// orario inatteso, questa riga e' la differenza fra capirlo subito e cercarlo per un'ora.
			logger.info("Catena {} -> {}: lanciata subscription {} (intermediario {}{}), esito avvio: {}",
					codiceCorrente, successivo, succ.getId(), succ.getIdIntermediario(),
					succ.isEnabled() ? "" : ", schedulazione BLOCCATA: la lancia la catena, non il cron",
					stato);
		}
	}
}
