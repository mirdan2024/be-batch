package it.be.batch.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import it.be.batch.entity.BatchDefinition;

public interface BatchDefinitionRepository extends JpaRepository<BatchDefinition, Long> {
	
    boolean existsByCode(String code);
}
