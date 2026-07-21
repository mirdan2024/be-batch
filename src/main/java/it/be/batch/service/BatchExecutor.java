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

	public BatchExecutor(RestTemplate restTemplate, ObjectMapper objectMapper,
			BatchExecutionRepository executionRepository, BatchSubscriptionRepository subscriptionRepository,
			TransactionTemplate transactionTemplate) {
		super();
		this.restTemplate = restTemplate;
		this.objectMapper = objectMapper;
		this.executionRepository = executionRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.transactionTemplate = transactionTemplate;
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
			return executionRepository.save(e);
		});

		// 2) FUORI transazione: chiamata all'endpoint del batch.
		String status;
		Integer responseCode = null;
		String responseBody = null;
		String errorMessage = null;
		try {
			ResponseEntity<String> response = callRestBatch(execution, subscription, jwt);
			// Il RestTemplate lancia eccezione sui 4xx/5xx (finiscono nel catch), quindi qui la risposta è
			// sempre 2xx: l'esecuzione è conclusa con successo -> COMPLETED (non PENDING, che era un bug).
			status = AppConstants.STATUS_COMPLETED;
			responseCode = response.getStatusCode().value();
			responseBody = response.getBody();
		} catch (Exception ex) {
			status = AppConstants.STATUS_FAILED;
			errorMessage = ex.getMessage();
		}

		// 3) Transazione breve: salva l'esito e riprogramma la sottoscrizione (atomici insieme).
		final String fStatus = status;
		final Integer fResponseCode = responseCode;
		final String fResponseBody = responseBody;
		final String fErrorMessage = errorMessage;
		transactionTemplate.executeWithoutResult(txStatus -> {
			LocalDateTime now = LocalDateTime.now();
			execution.setStatus(fStatus);
			execution.setResponseCode(fResponseCode);
			execution.setResponseBody(fResponseBody);
			execution.setErrorMessage(fErrorMessage);
			execution.setEndedAt(now);
			executionRepository.save(execution);

			subscription.setLastRunAt(now);
			subscription.setNextRunAt(calculateNextRun(subscription));
			subscriptionRepository.save(subscription);
		});
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
