package it.be.batch.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import it.be.batch.entity.SftpSchedule;

public interface SftpScheduleRepository extends JpaRepository<SftpSchedule, Long> {

	List<SftpSchedule> findByIdIntermediario(Long idIntermediario);

	// Schedulazioni da eseguire adesso: attive e con next_run_at scaduto (stessa logica del dispatch
	// delle batch). Le manuali hanno next_run_at null e non vengono mai selezionate.
	List<SftpSchedule> findByEnabledTrueAndNextRunAtLessThanEqual(LocalDateTime now);
}
