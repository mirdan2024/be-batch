package it.be.batch.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.be.batch.entity.BatchExecution;

public interface BatchExecutionRepository extends JpaRepository<BatchExecution, Long> {

    List<BatchExecution> findTop50ByBatchSubscriptionIdOrderByStartedAtDesc(Long subscriptionId);

    // Cancellazione dello storico esecuzioni di una sottoscrizione: necessaria PRIMA di eliminare la
    // sottoscrizione (la FK batch_execution -> batch_subscription bloccherebbe il delete altrimenti).
    void deleteByBatchSubscriptionId(Long subscriptionId);

    // Chiude le esecuzioni rimaste "in corso" (from) senza mai concludersi (ended_at IS NULL): usato al
    // riavvio per marcarle FAILED. La condizione ended_at IS NULL evita di toccare righe già concluse.
    @Modifying
    @Query("update BatchExecution e set e.status = :to, e.endedAt = :now, e.errorMessage = :msg "
            + "where e.status = :from and e.endedAt is null")
    int closeStaleExecutions(@Param("from") String from, @Param("to") String to,
            @Param("now") LocalDateTime now, @Param("msg") String msg);
}
