package it.be.batch.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import it.be.batch.entity.BatchSubscription;
import it.be.batch.repo.BatchSubscriptionRepository;

@Component
public class BatchScheduler {

	private final BatchSubscriptionRepository subscriptionRepository;
	private final BatchExecutor batchExecutor;

	public BatchScheduler(BatchSubscriptionRepository subscriptionRepository, BatchExecutor batchExecutor) {
		super();
		this.subscriptionRepository = subscriptionRepository;
		this.batchExecutor = batchExecutor;
	}

	@Scheduled(fixedDelayString = "${batch.scheduler.fixed-delay-ms}")
	public void dispatch() {

		LocalDateTime now = LocalDateTime.now();

		List<BatchSubscription> dueBatches = subscriptionRepository.findByEnabledTrueAndNextRunAtLessThanEqual(now);

		for (BatchSubscription subscription : dueBatches) {
			batchExecutor.execute(subscription);
		}
	}
}
