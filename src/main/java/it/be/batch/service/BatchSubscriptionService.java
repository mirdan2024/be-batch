package it.be.batch.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.be.batch.dto.Dtos.BatchExecutionResponse;
import it.be.batch.dto.Dtos.BatchSubscriptionRequest;
import it.be.batch.dto.Dtos.BatchSubscriptionResponse;
import it.be.batch.entity.BatchDefinition;
import it.be.batch.entity.BatchExecution;
import it.be.batch.entity.BatchSubscription;
import it.be.batch.entity.IntermediarioRef;
import it.be.batch.repo.BatchDefinitionRepository;
import it.be.batch.repo.BatchExecutionRepository;
import it.be.batch.repo.BatchSubscriptionRepository;
import it.be.batch.repo.IntermediarioRefRepository;

@Service
public class BatchSubscriptionService {

	private final BatchSubscriptionRepository subscriptionRepository;
	private final BatchDefinitionRepository definitionRepository;
	private final BatchExecutionRepository executionRepository;
	private final CredentialCipher credentialCipher;
	private final IntermediarioRefRepository intermediarioRefRepository;

	public BatchSubscriptionService(BatchSubscriptionRepository subscriptionRepository,
			BatchDefinitionRepository definitionRepository, BatchExecutionRepository executionRepository,
			CredentialCipher credentialCipher, IntermediarioRefRepository intermediarioRefRepository) {
		super();
		this.subscriptionRepository = subscriptionRepository;
		this.definitionRepository = definitionRepository;
		this.executionRepository = executionRepository;
		this.credentialCipher = credentialCipher;
		this.intermediarioRefRepository = intermediarioRefRepository;
	}

	public List<BatchSubscriptionResponse> findAll() {
		return subscriptionRepository.findAll().stream().map(this::toResponse).toList();
	}

	public List<BatchSubscriptionResponse> findByCustomerId(Long customerId) {
		return subscriptionRepository.findByIdIntermediario(customerId).stream().map(this::toResponse).toList();
	}

	public BatchSubscriptionResponse findById(Long id) {
		BatchSubscription entity = subscriptionRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));

		return toResponse(entity);
	}

	@Transactional
	public BatchSubscriptionResponse create(BatchSubscriptionRequest request) {

		BatchDefinition definition = definitionRepository.findById(request.batchDefinitionId())
				.orElseThrow(() -> new RuntimeException("Batch definition non trovata"));

		if (request.username() == null || request.username().isBlank()
				|| request.password() == null || request.password().isBlank()) {
			throw new RuntimeException("Username e password sono obbligatori per la sottoscrizione batch");
		}

		BatchSubscription entity = new BatchSubscription();
		entity.setIdIntermediario(request.idIntermediario());
		entity.setBatchDefinition(definition);
		entity.setCronExpression(blankToNull(request.cronExpression()));
		entity.setUsername(request.username());
		// La password è cifrata a riposo: il DB non la vede mai in chiaro.
		entity.setPasswordEnc(credentialCipher.encrypt(request.password()));
		entity.setTimezone(request.timezone() != null ? request.timezone() : "Europe/Rome");
		entity.setEnabled(request.enabled() == null || request.enabled());
		entity.setParamsJson(request.paramsJson());
		entity.setBodyJson(request.bodyJson());
		entity.setIdUtenteAdmin(request.idUtenteAdmin());
		entity.setDataCreazione(LocalDateTime.now());
		entity.setStartAt(parseStartAt(request.startAt()));
		entity.setNextRunAt(calculateNextRun(entity));

		return toResponse(subscriptionRepository.save(entity));
	}

	@Transactional
	public BatchSubscriptionResponse update(Long id, BatchSubscriptionRequest request) {

		BatchSubscription entity = subscriptionRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));

		BatchDefinition definition = definitionRepository.findById(request.batchDefinitionId())
				.orElseThrow(() -> new RuntimeException("Batch definition non trovata"));

		entity.setIdIntermediario(request.idIntermediario());
		entity.setBatchDefinition(definition);
		entity.setCronExpression(blankToNull(request.cronExpression()));
		entity.setTimezone(request.timezone() != null ? request.timezone() : "Europe/Rome");

		if (request.username() != null && !request.username().isBlank()) {
			entity.setUsername(request.username());
		}
		// Password vuota/null in update = invariata: così l'admin non deve reinserirla a ogni modifica
		// (e la API non la restituisce mai, quindi il client non ce l'ha per rimandarla).
		if (request.password() != null && !request.password().isBlank()) {
			entity.setPasswordEnc(credentialCipher.encrypt(request.password()));
		}

		if (request.enabled() != null) {
			entity.setEnabled(request.enabled());
		}

		entity.setParamsJson(request.paramsJson());
		entity.setBodyJson(request.bodyJson());
		entity.setStartAt(parseStartAt(request.startAt()));

		entity.setNextRunAt(calculateNextRun(entity));

		return toResponse(subscriptionRepository.save(entity));
	}

	@Transactional
	public void enable(Long id) {
		BatchSubscription entity = subscriptionRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));

		entity.setEnabled(true);
		entity.setDataCessazione(null);
		if (entity.getNextRunAt() == null) {
			entity.setNextRunAt(calculateNextRun(entity));
		}

		subscriptionRepository.save(entity);
	}

	@Transactional
	public void disable(Long id) {
		BatchSubscription entity = subscriptionRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));

		entity.setEnabled(false);
		entity.setDataCessazione(LocalDateTime.now());
		subscriptionRepository.save(entity);
	}

	// Eliminazione DEFINITIVA dal DB (non un soft-disable: per quello c'è disable()). Prima si cancella
	// lo storico esecuzioni, altrimenti la foreign key batch_execution -> batch_subscription impedirebbe
	// la rimozione.
	@Transactional
	public void delete(Long id) {
		if (!subscriptionRepository.existsById(id)) {
			throw new RuntimeException("Sottoscrizione batch non trovata: " + id);
		}
		executionRepository.deleteByBatchSubscriptionId(id);
		subscriptionRepository.deleteById(id);
	}

	// Storico esecuzioni della sottoscrizione: ultime 50, ordine decrescente per data di inizio.
	public List<BatchExecutionResponse> findExecutions(Long subscriptionId) {
		return executionRepository.findTop50ByBatchSubscriptionIdOrderByStartedAtDesc(subscriptionId)
				.stream().map(this::toExecutionResponse).toList();
	}

	private BatchExecutionResponse toExecutionResponse(BatchExecution e) {
		Long durationMs = (e.getStartedAt() != null && e.getEndedAt() != null)
				? Duration.between(e.getStartedAt(), e.getEndedAt()).toMillis()
				: null;
		return new BatchExecutionResponse(e.getId(), e.getStatus(), e.getStartedAt(), e.getEndedAt(), durationMs,
				e.getResponseCode(), e.getErrorMessage(), e.getResponseBody());
	}

	private LocalDateTime calculateNextRun(BatchSubscription subscription) {
		return CronScheduleUtil.nextRun(subscription.getCronExpression(), subscription.getTimezone(),
				subscription.getStartAt());
	}

	// Cron vuoto/blank -> null: sottoscrizione "manuale" (nessuna schedulazione automatica).
	private String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s.trim();
	}

	// startAt dal client come ISO locale "yyyy-MM-ddTHH:mm" (input datetime-local, secondi opzionali).
	// null/vuoto = nessuna decorrenza. Formato non valido -> errore esplicito.
	private LocalDateTime parseStartAt(String startAt) {
		if (startAt == null || startAt.isBlank()) {
			return null;
		}
		try {
			return LocalDateTime.parse(startAt);
		} catch (Exception e) {
			throw new RuntimeException("Data e ora di partenza non valide: " + startAt);
		}
	}

	// idIntermediario -> nominativo (null se assente/non trovato). N.B.: lookup per riga; il numero di
	// schedulazioni è contenuto, quindi va bene senza ottimizzazioni.
	private String nomeIntermediario(Long idIntermediario) {
		if (idIntermediario == null) {
			return null;
		}
		return intermediarioRefRepository.findById(idIntermediario).map(IntermediarioRef::getNominativo).orElse(null);
	}

	// NB: username incluso, password MAI (non c'è nel response record).
	private BatchSubscriptionResponse toResponse(BatchSubscription entity) {
		return new BatchSubscriptionResponse(entity.getId(), entity.getIdIntermediario(), entity.getBatchDefinition().getId(),
				entity.getBatchDefinition().getCode(), entity.getCronExpression(), entity.getUsername(),
				entity.getTimezone(), entity.isEnabled(), entity.getLastRunAt(), entity.getNextRunAt(),
				entity.getParamsJson(), entity.getBodyJson(), entity.getIdUtenteAdmin(), entity.getStartAt(),
				nomeIntermediario(entity.getIdIntermediario()));
	}
}