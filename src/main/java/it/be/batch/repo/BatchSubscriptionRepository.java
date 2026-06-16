package it.be.batch.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.be.batch.entity.BatchSubscription;

public interface BatchSubscriptionRepository extends JpaRepository<BatchSubscription, Long> {

	List<BatchSubscription> findByEnabledTrueAndNextRunAtLessThanEqual(LocalDateTime now);

	List<BatchSubscription> findByIdIntermediario(Long idIntermediario);

	@Query("""
			    select s
			    from BatchSubscription s
			    join fetch s.batchDefinition
			    where s.enabled = true
			    and s.nextRunAt <= :now
			""")
	List<BatchSubscription> findDueSubscriptions(@Param("now") LocalDateTime now);

}
