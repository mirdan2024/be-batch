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

	/**
	 * Sottoscrizioni attive di una definition, per codice: sono quelle che parte la catena quando un
	 * lavoro dichiara un {@code job_successivo}. Si escludono le disattivate (bloccate a mano) e quelle
	 * di una definition disattivata, come fa il dispatch: la catena non deve poter far girare qualcosa
	 * che qualcuno ha deliberatamente fermato.
	 */
	@Query("""
			    select s
			    from BatchSubscription s
			    join fetch s.batchDefinition d
			    where s.enabled = true
			    and s.dataCessazione is null
			    and d.enabled = true
			    and d.code = :code
			""")
	List<BatchSubscription> findAttiveByDefinitionCode(@Param("code") String code);

}
