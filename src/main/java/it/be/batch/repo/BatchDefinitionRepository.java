package it.be.batch.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import it.be.batch.entity.BatchDefinition;

public interface BatchDefinitionRepository extends JpaRepository<BatchDefinition, Long> {

	boolean existsByCode(String code);

	Optional<BatchDefinition> findByCode(String code);

	@Query("""
			    SELECT b
			    FROM BatchDefinition b
			    WHERE b.enabled = true
			      AND b.dataCreazione <= CURRENT_TIMESTAMP
			      AND (
			            b.dataCessazione IS NULL
			            OR b.dataCessazione >= CURRENT_TIMESTAMP
			      )
			""")
	List<BatchDefinition> findActiveDefinitions();
}
