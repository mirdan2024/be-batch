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

	/**
	 * Elenco per la pagina, nell'ordine deciso a mano con le frecce. L'id in coda e' il criterio di
	 * riserva: senza, due righe con lo stesso {@code ordine} — possibile solo per righe mai riordinate —
	 * si scambierebbero di posto a ogni apertura della pagina.
	 */
	List<BatchSubscription> findAllByOrderByOrdineAscIdAsc();

	List<BatchSubscription> findByIdIntermediarioOrderByOrdineAscIdAsc(Long idIntermediario);

	/** Ultima posizione occupata: le sottoscrizioni nuove nascono in fondo. */
	@Query("select coalesce(max(s.ordine), 0) from BatchSubscription s")
	Integer maxOrdine();

	@Query("""
			    select s
			    from BatchSubscription s
			    join fetch s.batchDefinition
			    where s.enabled = true
			    and s.nextRunAt <= :now
			""")
	List<BatchSubscription> findDueSubscriptions(@Param("now") LocalDateTime now);

	/**
	 * Sottoscrizioni che la CATENA puo' lanciare per una definition, dato il suo codice.
	 * <p>
	 * <b>Le schedulazioni BLOCCATE sono comprese</b>, ed e' voluto. "Bloccata" ferma il cron, non i lanci
	 * espliciti: il pulsante "Esegui ora" gira gia' su una schedulazione bloccata ({@code eseguiUnaTantum}
	 * controlla solo la definition), e la catena e' un lancio esplicito quanto quello — configurato in
	 * anticipo sul campo "job successivo" invece che premuto sul momento. E' anzi la configurazione
	 * naturale per un lavoro che deve girare SOLO in coda a un altro: bloccato, cosi' non parte da solo,
	 * e avviato dalla catena quando il lavoro che lo precede chiude bene.
	 * <p>
	 * Restano fuori due casi, che non sono "in pausa" ma "non esiste piu'": la sottoscrizione ELIMINATA
	 * ({@code dataCessazione} valorizzata) e la definition DISATTIVATA, che vale per tutti gli
	 * intermediari ed e' lo stesso filtro che applica il dispatch automatico.
	 */
	@Query("""
			    select s
			    from BatchSubscription s
			    join fetch s.batchDefinition d
			    where s.dataCessazione is null
			    and d.enabled = true
			    and d.code = :code
			""")
	List<BatchSubscription> findAttiveByDefinitionCode(@Param("code") String code);

}
