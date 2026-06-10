package it.be.batch.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.be.batch.dto.Dtos.BatchSubscriptionRequest;
import it.be.batch.dto.Dtos.BatchSubscriptionResponse;
import it.be.batch.entity.BatchDefinition;
import it.be.batch.entity.BatchSubscription;
import it.be.batch.repo.BatchDefinitionRepository;
import it.be.batch.repo.BatchSubscriptionRepository;

@Service
public class BatchSubscriptionService {

    private final BatchSubscriptionRepository subscriptionRepository;
    private final BatchDefinitionRepository definitionRepository;
    
    

    public BatchSubscriptionService(BatchSubscriptionRepository subscriptionRepository,
			BatchDefinitionRepository definitionRepository) {
		super();
		this.subscriptionRepository = subscriptionRepository;
		this.definitionRepository = definitionRepository;
	}

	public List<BatchSubscriptionResponse> findAll() {
        return subscriptionRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<BatchSubscriptionResponse> findByCustomerId(Long customerId) {
        return subscriptionRepository.findByCustomerId(customerId)
                .stream()
                .map(this::toResponse)
                .toList();
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

        BatchSubscription entity = new BatchSubscription();
        entity.setCustomerId(request.customerId());
        entity.setBatchDefinition(definition);
        entity.setCronExpression(request.cronExpression());
        entity.setTimezone(request.timezone() != null ? request.timezone() : "Europe/Rome");
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setParamsJson(request.paramsJson());
        entity.setBodyJson(request.bodyJson());

        entity.setNextRunAt(calculateNextRun(entity));

        return toResponse(subscriptionRepository.save(entity));
    }

    @Transactional
    public BatchSubscriptionResponse update(Long id, BatchSubscriptionRequest request) {

        BatchSubscription entity = subscriptionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));

        BatchDefinition definition = definitionRepository.findById(request.batchDefinitionId())
                .orElseThrow(() -> new RuntimeException("Batch definition non trovata"));

        entity.setCustomerId(request.customerId());
        entity.setBatchDefinition(definition);
        entity.setCronExpression(request.cronExpression());
        entity.setTimezone(request.timezone() != null ? request.timezone() : "Europe/Rome");

        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }

        entity.setParamsJson(request.paramsJson());
        entity.setBodyJson(request.bodyJson());

        entity.setNextRunAt(calculateNextRun(entity));

        return toResponse(subscriptionRepository.save(entity));
    }

    @Transactional
    public void enable(Long id) {
        BatchSubscription entity = subscriptionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));

        entity.setEnabled(true);

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
        subscriptionRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        subscriptionRepository.deleteById(id);
    }

    private LocalDateTime calculateNextRun(BatchSubscription subscription) {
        CronExpression cron = CronExpression.parse(subscription.getCronExpression());
        return cron.next(LocalDateTime.now());
    }

    private BatchSubscriptionResponse toResponse(BatchSubscription entity) {
        return new BatchSubscriptionResponse(
                entity.getId(),
                entity.getCustomerId(),
                entity.getBatchDefinition().getId(),
                entity.getBatchDefinition().getCode(),
                entity.getCronExpression(),
                entity.getTimezone(),
                entity.isEnabled(),
                entity.getLastRunAt(),
                entity.getNextRunAt(),
                entity.getParamsJson(),
                entity.getBodyJson()
        );
    }
}