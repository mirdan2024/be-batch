package it.be.batch.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import it.be.batch.entity.IntermediarioRef;

// Sola lettura: usato per risolvere idIntermediario -> nominativo nelle response delle schedulazioni.
public interface IntermediarioRefRepository extends JpaRepository<IntermediarioRef, Long> {
}
