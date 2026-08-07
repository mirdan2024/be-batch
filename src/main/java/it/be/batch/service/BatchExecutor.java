package it.be.batch.service;

import java.time.LocalDateTime;
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

	/** Taglia i testi lunghi per i log (il body completo va comunque su batch_execution.response_body). */
	private static String abbrevia(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() <= max ? s : s.substring(0, max) + "... (" + s.length() + " caratteri)";
	}

	public void execute(BatchSubscription subscription, String jwt) {

		// 1) Transazione breve: registra l'esecuzione come "in corso" (PENDING). Diventerà COMPLETED o
		// FAILED al termine della chiamata (passo 3). Se l'app viene riavviata mentre è ancora PENDING,
		// il recupero all'avvio (BatchStartupRecovery) la marca FAILED: una PENDING non conclusa è orfana.
		BatchExecution execution = transactionTemplate.execute(status -> {
			BatchExecution e = new BatchExecution();
			e.setBatchSubscription(subscription);
			e.setStatus(AppConstants.STATUS_PENDING);
			e.setStartedAt(LocalDateTime.now());
			// Primo battito: da qui in poi lo aggiorna ogni riga di telecronaca. Serve a far partire il
			// conteggio del silenzio dall'avvio anche per i servizi che non scrivono nulla.
			e.setUltimoAggiornamento(LocalDateTime.now());
			return executionRepository.save(e);
		});

		// 2) FUORI transazione: chiamata all'endpoint del batch.
		String status;
		Integer responseCode = null;
		String responseBody = null;
		String errorMessage = null;
		// 202 ACCEPTED = "preso in carico, ti aggiorno io": il servizio elabora in background, scrive
		// l'avanzamento su /batch-executions/{id}/log e chiude l'esecuzione con /finish. In quel caso
		// be-batch NON tocca lo stato (resta PENDING) e soprattutto non resta appeso ad aspettare:
		// su elaborazioni lunghe il read timeout marcava FAILED un servizio che stava lavorando bene.
		boolean presoInCarico = false;
		try {
			ResponseEntity<String> response = callRestBatch(execution, subscription, jwt);
			if (response.getStatusCode().value() == 202) {
				presoInCarico = true;
				logger.info("Batch subscription {}: preso in carico dal servizio (202), esito atteso via callback",
						subscription.getId());
			}
			// Il RestTemplate lancia eccezione sui 4xx/5xx (finiscono nel catch), quindi qui la risposta è
			// sempre 2xx: l'esecuzione è conclusa con successo -> COMPLETED (non PENDING, che era un bug).
			status = AppConstants.STATUS_COMPLETED;
			responseCode = response.getStatusCode().value();
			responseBody = response.getBody();
		} catch (org.springframework.web.client.RestClientResponseException ex) {
			// Il servizio ha risposto con un errore (4xx/5xx): il MOTIVO sta nel body, che spesso contiene
			// il dettaglio per-file/per-record. Senza salvarlo, nello storico resterebbe solo "500 Internal
			// Server Error" e non ci sarebbe modo di capire cosa correggere.
			status = AppConstants.STATUS_FAILED;
			responseCode = ex.getStatusCode().value();
			responseBody = ex.getResponseBodyAsString();
			errorMessage = ex.getStatusCode().value() + " " + ex.getStatusText();
			logger.error("Batch subscription {}: chiamata fallita con status {} - body: {}", subscription.getId(),
					responseCode, abbrevia(responseBody, 2000));
		} catch (Exception ex) {
			// Errore senza risposta HTTP (timeout, host irraggiungibile, ecc.): si salva il tipo oltre al
			// messaggio, perche' getMessage() da solo puo' essere null (es. NullPointerException).
			status = AppConstants.STATUS_FAILED;
			errorMessage = ex.getClass().getSimpleName()
					+ (ex.getMessage() != null ? ": " + ex.getMessage() : "");
			logger.error("Batch subscription {}: esecuzione fallita: {}", subscription.getId(), errorMessage, ex);
		}

		// 3) Transazione breve: salva l'esito e riprogramma la sottoscrizione (atomici insieme).
		final String fStatus = status;
		final Integer fResponseCode = responseCode;
		final String fResponseBody = responseBody;
		final String fErrorMessage = errorMessage;
		final boolean fPresoInCarico = presoInCarico;
		transactionTemplate.executeWithoutResult(txStatus -> {
			LocalDateTime now = LocalDateTime.now();
			// Con 202 l'esecuzione resta PENDING: la chiudera' il servizio. Si aggiorna solo la
			// riprogrammazione della sottoscrizione, che non dipende dall'esito.
			if (!fPresoInCarico) {
				execution.setStatus(fStatus);
				execution.setResponseCode(fResponseCode);
				execution.setResponseBody(fResponseBody);
				execution.setErrorMessage(fErrorMessage);
				execution.setEndedAt(now);
			} else {
				execution.setResponseCode(202);
			}
			executionRepository.save(execution);

			subscription.setLastRunAt(now);
			subscription.setNextRunAt(calculateNextRun(subscription));
			subscriptionRepository.save(subscription);
		});

		// 4) Concatenamento. SOLO nel ramo sincrono: con il 202 l'esecuzione e' ancora in corso e la
		// chiuderà il servizio chiamando /batch-execution/{id}/finish — è li' che scatta la catena
		// (BatchExecutionService.finish). Lanciare qui il seguito significherebbe farlo partire mentre il
		// lavoro precedente sta ancora elaborando.
		if (!presoInCarico) {
			try {
				chainService.esecuzioneConclusa(subscription, status);
			} catch (Exception e) {
				// Il seguito e' un servizio in piu': se non parte, l'esecuzione appena conclusa resta
				// valida e il suo esito registrato.
				logger.error("Catena non avviata dopo la subscription {}: {}", subscription.getId(), e.getMessage(), e);
			}
		}
	}

	private ResponseEntity<String> callRestBatch(BatchExecution execution, BatchSubscription subscription,
			String jwtToken) {

		BatchDefinition definition = subscription.getBatchDefinition();

		String resolvedUrl = resolveUrl(definition.getEndpointUrl(), subscription.getParamsJson());

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(jwtToken);
		headers.add("idExecution", execution.getId() + "");

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
