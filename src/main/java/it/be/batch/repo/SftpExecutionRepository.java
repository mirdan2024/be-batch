package it.be.batch.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import it.be.batch.entity.SftpExecution;

public interface SftpExecutionRepository extends JpaRepository<SftpExecution, Long> {

	List<SftpExecution> findTop50BySftpScheduleIdOrderByStartedAtDesc(Long sftpScheduleId);

	/**
	 * Storico trasferimenti di una schedulazione, PAGINATO. Come per le schedulazioni batch: il tetto
	 * fisso a 50 rendeva irraggiungibile tutto cio' che stava piu' indietro.
	 */
	org.springframework.data.domain.Page<SftpExecution> findBySftpScheduleIdOrderByStartedAtDesc(Long sftpScheduleId,
			org.springframework.data.domain.Pageable pageable);

	List<SftpExecution> findByStatusAndEndedAtIsNull(String status);

	List<SftpExecution> findBySftpScheduleIdAndStatusAndEndedAtIsNull(Long sftpScheduleId, String status);

	// Cancellazione dello storico: necessaria PRIMA di eliminare la schedulazione (la FK
	// sftp_execution -> sftp_schedule bloccherebbe il delete altrimenti).
	void deleteBySftpScheduleId(Long sftpScheduleId);

	/**
	 * Rete di sicurezza: chiude le esecuzioni rimaste appese (riavvio del servizio a meta'
	 * trasferimento), altrimenti la clessidra girerebbe all'infinito.
	 */
	@Modifying
	@Transactional
	@Query("update SftpExecution e set e.status = :statoFinale, e.endedAt = :adesso, e.errorMessage = :messaggio "
			+ "where e.status = :statoPendente and e.endedAt is null and e.startedAt < :limite")
	int closeStalePending(@Param("statoPendente") String statoPendente, @Param("statoFinale") String statoFinale,
			@Param("adesso") LocalDateTime adesso, @Param("limite") LocalDateTime limite,
			@Param("messaggio") String messaggio);
}
