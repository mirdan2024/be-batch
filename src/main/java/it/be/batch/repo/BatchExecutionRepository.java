package it.be.batch.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import it.be.batch.entity.BatchExecution;

public interface BatchExecutionRepository extends JpaRepository<BatchExecution, Long> {
	
    List<BatchExecution> findTop50ByBatchSubscriptionIdOrderByStartedAtDesc(Long subscriptionId);
}
