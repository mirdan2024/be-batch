package it.be.batch.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import it.be.batch.entity.SftpSchedule;

public interface SftpScheduleRepository extends JpaRepository<SftpSchedule, Long> {

	List<SftpSchedule> findByIdIntermediario(Long idIntermediario);

	/**
	 * Elenco per la pagina, nell'ordine deciso a mano con le frecce. L'id in coda e' il criterio di
	 * riserva: senza, due righe con lo stesso {@code ordine} si scambierebbero di posto a ogni apertura.
	 */
	List<SftpSchedule> findAllByOrderByOrdineAscIdAsc();

	List<SftpSchedule> findByIdIntermediarioOrderByOrdineAscIdAsc(Long idIntermediario);

	/** Ultima posizione occupata: le schedulazioni nuove nascono in fondo. */
	@org.springframework.data.jpa.repository.Query("select coalesce(max(s.ordine), 0) from SftpSchedule s")
	Integer maxOrdine();

	// Schedulazioni da eseguire adesso: attive e con next_run_at scaduto (stessa logica del dispatch
	// delle batch). Le manuali hanno next_run_at null e non vengono mai selezionate.
	List<SftpSchedule> findByEnabledTrueAndNextRunAtLessThanEqual(LocalDateTime now);
}
