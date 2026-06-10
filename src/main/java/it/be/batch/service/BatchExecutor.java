package it.be.batch.service;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.be.batch.entity.BatchDefinition;
import it.be.batch.entity.BatchDefinition.BatchExecutionStatus;
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

	public BatchExecutor(RestTemplate restTemplate, ObjectMapper objectMapper,
			BatchExecutionRepository executionRepository, BatchSubscriptionRepository subscriptionRepository) {
		super();
		this.restTemplate = restTemplate;
		this.objectMapper = objectMapper;
		this.executionRepository = executionRepository;
		this.subscriptionRepository = subscriptionRepository;
	}

	@Transactional
	public void execute(BatchSubscription subscription) {

		BatchExecution execution = new BatchExecution();
		execution.setBatchSubscription(subscription);
		execution.setStatus(BatchExecutionStatus.RUNNING);
		execution.setStartedAt(LocalDateTime.now());

		executionRepository.save(execution);

		try {
			ResponseEntity<String> response = callRestBatch(subscription);

			execution.setStatus(BatchExecutionStatus.SUCCESS);
			execution.setResponseCode(response.getStatusCode().value());
			execution.setResponseBody(response.getBody());

		} catch (Exception ex) {
			execution.setStatus(BatchExecutionStatus.FAILED);
			execution.setErrorMessage(ex.getMessage());

		} finally {
			LocalDateTime now = LocalDateTime.now();

			execution.setEndedAt(now);

			subscription.setLastRunAt(now);
			subscription.setNextRunAt(calculateNextRun(subscription));

			executionRepository.save(execution);
			subscriptionRepository.save(subscription);
		}
	}

	private ResponseEntity<String> callRestBatch(BatchSubscription subscription) {

		BatchDefinition definition = subscription.getBatchDefinition();

		String resolvedUrl = resolveUrl(definition.getEndpointUrl(), subscription.getParamsJson());

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

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

	private LocalDateTime calculateNextRun(BatchSubscription subscription) {

		CronExpression cron = CronExpression.parse(subscription.getCronExpression());

		return cron.next(LocalDateTime.now());
	}
}
