package it.be.batch.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.ai.client.constants.AppConstants;
import it.be.batch.entity.BatchDefinition;
import it.be.batch.entity.BatchExecution;
import it.be.batch.entity.BatchSubscription;
import it.be.batch.repo.BatchExecutionRepository;
import it.be.batch.repo.BatchSubscriptionRepository;

@Service
public class BatchExecutor {

	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BatchExecutor.class);

	/** Stesso formato delle righe di telecronaca (BatchExecutionService.appendLog). */
	private static final java.time.format.DateTimeFormatter FORMATO_LOG = java.time.format.DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final RestTemplate restTemplate;
	private final ObjectMapper objectMapper;

	private final BatchExecutionRepository executionRepository;
	private final BatchSubscriptionRepository subscriptionRepository;
	// La chiamata HTTP NON deve stare dentro una transazione: con timeout fino a 10 minuti terrebbe
	// occupata una connessione Hikari (pool da 15) per tutta la durata, esaurendolo con poche
	// esecuzioni lente. Si aprono quindi due transazioni brevi (registrazione iniziale e salvataggio
	// esito) attorno all'HTTP, non una che lo avvolge. TransactionTemplate e non @Transactional perché
	// i metodi verrebbero invocati dall'interno della classe, bypassando il proxy transazionale di Spring.
	private final TransactionTemplate transactionTemplate;
	// Concatenamento: a esecuzione conclusa con esito positivo lancia il "job successivo" dichiarato
	// sulla schedulazione.
	private final BatchChainService chainService;

	public BatchExecutor(RestTemplate restTemplate, ObjectMapper objectMapper,
			BatchExecutionRepository executionRepository, BatchSubscriptionRepository subscriptionRepository,
			TransactionTemplate transactionTemplate, BatchChainService chainService) {
		super();
		this.restTemplate = restTemplate;
		this.objectMapper = objectMapper;
		this.executionRepository = executionRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.transactionTemplate = transactionTemplate;
		this.chainService = chainService;
	}

	public void execute(BatchSubscription subscription, String jwt) {
		esegui(subscription, jwt, null, null);
	}

	/**
	 * RIPRESA di un'esecuzione fallita o interrotta ("Riprendi" nello storico esecuzioni): una nuova
	 * esecuzione, legata a quella ripresa, che chiama il resume_url della definizione al posto
	 * dell'endpoint. Per il resto e' un'esecuzione come le altre: telecronaca, 202, chiusura dal servizio
	 * e, se va a buon fine, il lavoro successivo della catena.
	 *
	 * @param idDaRiprendere esecuzione su cui l'operatore ha premuto "Riprendi"
	 * @param idOriginale    esecuzione che aveva avviato il lavoro (inizio della catena di riprese): e'
	 *                       con quella che il servizio ritrova il punto a cui era arrivato
	 */
	public void riprendi(BatchSubscription subscription, String jwt, Long idDaRiprendere, Long idOriginale) {
		esegui(subscription, jwt, idDaRiprendere, idOriginale);
	}

	private void esegui(BatchSubscription subscription, String jwt, Long idDaRiprendere, Long idOriginale) {
		final boolean ripresa = (idDaRiprendere != null);

		// 1) Transazione breve: registra l'esecuzione come "in corso" (PENDING). Diventerà COMPLETED o
		// FAILED al termine della chiamata (passo 3). Se l'app viene riavviata mentre è ancora PENDING,
		// il recupero all'avvio (BatchStartupRecovery) la marca FAILED: una PENDING non conclusa è orfana.
		BatchExecution execution = transactionTemplate
				.execute(status -> registraAvvio(subscription, idDaRiprendere, idOriginale));

		// 2) FUORI transazione: chiamata all'endpoint del batch.
		final Esito esito = chiamaServizio(execution, subscription, jwt, ripresa, idOriginale);

		// 3) Transazione breve: salva l'esito e riprogramma la sottoscrizione (atomici insieme).
		transactionTemplate.executeWithoutResult(txStatus -> {
			LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
			// Con 202 l'esecuzione resta PENDING: la chiudera' il servizio. Si aggiorna solo la
			// riprogrammazione della sottoscrizione, che non dipende dall'esito.
			// Scritture MIRATE, non save(execution): quell'oggetto e' la copia letta all'avvio, e intanto il
			// servizio ha gia' scritto telecronaca (e magari chiuso l'esecuzione). Risalvarlo per intero
			// cancellava la telecronaca e ne riportava lo stato indietro.
			if (!esito.presoInCarico()) {
				executionRepository.registraEsito(execution.getId(), esito.responseCode(), esito.responseBody(),
						esito.status(), esito.errorMessage(), now);
			} else {
				executionRepository.registraCodice(execution.getId(), 202);
			}

			subscription.setLastRunAt(now);
			subscription.setNextRunAt(calculateNextRun(subscription));
			subscriptionRepository.save(subscription);
		});

		// 4) Concatenamento. SOLO nel ramo sincrono: con il 202 l'esecuzione e' ancora in corso e la
		// chiuderà il servizio chiamando /batch-execution/{id}/finish — è li' che scatta la catena
		// (BatchExecutionService.finish). Lanciare qui il seguito significherebbe farlo partire mentre il
		// lavoro precedente sta ancora elaborando.
		if (!esito.presoInCarico()) {
			try {
				chainService.esecuzioneConclusa(subscription, esito.status());
			} catch (Exception e) {
				// Il seguito e' un servizio in piu': se non parte, l'esecuzione appena conclusa resta
				// valida e il suo esito registrato.
				logger.error("Catena non avviata dopo la subscription {}: {}", subscription.getId(), e.getMessage(), e);
			}
		}
	}

	/** Passo 1: la riga di batch_execution "in corso", con la prima riga di telecronaca se e' una ripresa. */
	private BatchExecution registraAvvio(BatchSubscription subscription, Long idDaRiprendere, Long idOriginale) {
		BatchExecution e = new BatchExecution();
		e.setBatchSubscription(subscription);
		e.setStatus(AppConstants.STATUS_PENDING);
		e.setStartedAt(LocalDateTime.now(ZoneId.systemDefault()));
		// Primo battito: da qui in poi lo aggiorna ogni riga di telecronaca. Serve a far partire il
		// conteggio del silenzio dall'avvio anche per i servizi che non scrivono nulla.
		e.setUltimoAggiornamento(LocalDateTime.now(ZoneId.systemDefault()));
		if (idDaRiprendere != null) {
			e.setIdRipresaDi(idDaRiprendere);
			// Prima riga della telecronaca: chi apre lo storico vede subito che non e' un avvio da capo.
			e.setLog(LocalDateTime.now(ZoneId.systemDefault()).format(FORMATO_LOG) + "  Ripresa dell'esecuzione #" + idDaRiprendere
					+ ((idOriginale != null && !idOriginale.equals(idDaRiprendere))
							? " (lavoro avviato dall'esecuzione #" + idOriginale + ")"
							: ""));
		}
		return executionRepository.save(e);
	}

	/** Esito della chiamata al servizio, cosi' come va registrato su batch_execution. */
	private record Esito(String status, Integer responseCode, String responseBody, String errorMessage,
			boolean presoInCarico) {
	}

	/** Passo 2, FUORI transazione: la chiamata all'endpoint del batch. Non solleva: l'errore e' un esito. */
	private Esito chiamaServizio(BatchExecution execution, BatchSubscription subscription, String jwt,
			boolean ripresa, Long idOriginale) {
		try {
			ResponseEntity<String> response = callRestBatch(execution, subscription, jwt, ripresa, idOriginale);
			// 202 ACCEPTED = "preso in carico, ti aggiorno io": il servizio elabora in background, scrive
			// l'avanzamento su /batch-executions/{id}/log e chiude l'esecuzione con /finish. In quel caso
			// be-batch NON tocca lo stato (resta PENDING) e soprattutto non resta appeso ad aspettare:
			// su elaborazioni lunghe il read timeout marcava FAILED un servizio che stava lavorando bene.
			boolean presoInCarico = response.getStatusCode().value() == 202;
			if (presoInCarico) {
				logger.info("Batch subscription {}: preso in carico dal servizio (202), esito atteso via callback",
						subscription.getId());
			}
			// Il RestTemplate lancia eccezione sui 4xx/5xx (finiscono nel catch), quindi qui la risposta è
			// sempre 2xx: l'esecuzione è conclusa con successo -> COMPLETED (non PENDING, che era un bug).
			return new Esito(AppConstants.STATUS_COMPLETED, response.getStatusCode().value(), response.getBody(),
					null, presoInCarico);
		} catch (org.springframework.web.client.RestClientResponseException ex) {
			// Il servizio ha risposto con un errore (4xx/5xx): il MOTIVO sta nel body, che spesso contiene
			// il dettaglio per-file/per-record. Senza salvarlo, nello storico resterebbe solo "500 Internal
			// Server Error" e non ci sarebbe modo di capire cosa correggere.
			Integer responseCode = ex.getStatusCode().value();
			String responseBody = ex.getResponseBodyAsString();
			// Nel log va lo stato. Il corpo, col suo dettaglio per-file e per-record, sta nello storico
			// dell'esecuzione (batch_execution.response_body), che e' dove lo si va a leggere: fino al 02-10-2026
			// i suoi primi 2000 caratteri finivano anche qui.
			logger.error("Batch subscription {}: chiamata fallita con status {} (il corpo e' nello storico"
					+ " dell'esecuzione)", subscription.getId(), responseCode);
			return new Esito(AppConstants.STATUS_FAILED, responseCode, responseBody,
					ex.getStatusCode().value() + " " + ex.getStatusText(), false);
		} catch (Exception ex) {
			// Errore senza risposta HTTP (timeout, host irraggiungibile, ecc.): si salva il tipo oltre al
			// messaggio, perche' getMessage() da solo puo' essere null (es. NullPointerException).
			String errorMessage = ex.getClass().getSimpleName()
					+ (ex.getMessage() != null ? ": " + ex.getMessage() : "");
			logger.error("Batch subscription {}: esecuzione fallita: {}", subscription.getId(), errorMessage, ex);
			return new Esito(AppConstants.STATUS_FAILED, null, null, errorMessage, false);
		}
	}

	private ResponseEntity<String> callRestBatch(BatchExecution execution, BatchSubscription subscription,
			String jwtToken, boolean ripresa, Long idOriginale) {

		BatchDefinition definition = subscription.getBatchDefinition();

		// La ripresa chiama l'URL che il servizio dichiara per ripartire, non quello dell'avvio.
		String resolvedUrl = resolveUrl(ripresa ? definition.getResumeUrl() : definition.getEndpointUrl(),
				subscription.getParamsJson());

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(jwtToken);
		headers.add("idExecution", execution.getId() + "");
		if (ripresa && idOriginale != null) {
			headers.add("idExecutionOriginale", idOriginale + "");
		}

		// INTERMEDIARIO della sottoscrizione, quando c'e'. Il campo e' facoltativo: lasciandolo vuoto la
		// schedulazione non e' ristretta a un cliente e il servizio a valle lavora su TUTTI i record.
		// Va inoltrato perche' il servizio non puo' dedurlo: dal JWT ricava l'intermediario dell'UTENZA
		// con cui il batch si autentica, che e' un'altra cosa e non sa nulla di questa scelta.
		if (subscription.getIdIntermediario() != null) {
			headers.add("idIntermediario", subscription.getIdIntermediario() + "");
		}

		HttpEntity<String> request = new HttpEntity<>(subscription.getBodyJson(), headers);

		return restTemplate.exchange(resolvedUrl, HttpMethod.valueOf(definition.getHttpMethod().name()), request,
				String.class);
	}

	private String resolveUrl(String endpointUrl, String paramsJson) {

		if (paramsJson == null || paramsJson.isBlank()) {
			return endpointUrl;
		}

		try {
			Map<String, Object> params = objectMapper.readValue(paramsJson, new TypeReference<>() {
			});

			String resolvedUrl = endpointUrl;

			for (Map.Entry<String, Object> entry : params.entrySet()) {
				resolvedUrl = resolvedUrl.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
			}

			return resolvedUrl;

		} catch (Exception e) {
			throw new RuntimeException("Errore nella risoluzione parametri batch", e);
		}
	}

	// Cron non ha occorrenze future (nextRun null): si mantiene il valore precedente.
	// NB: si passa anche startAt — un'esecuzione UNA TANTUM lanciata prima della decorrenza non deve
	// rischedulare next_run_at prima di start_at (per i run schedulati, già oltre la decorrenza, il
	// comportamento è identico a prima).
	private LocalDateTime calculateNextRun(BatchSubscription subscription) {
		LocalDateTime next = CronScheduleUtil.nextRun(subscription.getCronExpression(), subscription.getTimezone(),
				subscription.getStartAt());
		return (next != null) ? next : subscription.getNextRunAt();
	}
}
