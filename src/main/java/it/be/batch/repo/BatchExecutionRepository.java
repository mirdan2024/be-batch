package it.be.batch.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import it.be.batch.entity.BatchExecution;

public interface BatchExecutionRepository extends JpaRepository<BatchExecution, Long> {

    List<BatchExecution> findTop50ByBatchSubscriptionIdOrderByStartedAtDesc(Long subscriptionId);

    /**
     * Storico esecuzioni di una schedulazione, PAGINATO. Sostituisce il tetto fisso a 50 della modale:
     * su un lavoro che gira ogni giorno cinquanta esecuzioni sono meno di due mesi, e il resto non era
     * raggiungibile in nessun modo dall'interfaccia.
     */
    org.springframework.data.domain.Page<BatchExecution> findByBatchSubscriptionIdOrderByStartedAtDesc(
            Long subscriptionId, org.springframework.data.domain.Pageable pageable);

    // Cancellazione dello storico esecuzioni di una sottoscrizione: necessaria PRIMA di eliminare la
    // sottoscrizione (la FK batch_execution -> batch_subscription bloccherebbe il delete altrimenti).
    void deleteByBatchSubscriptionId(Long subscriptionId);

    // Esecuzioni ancora IN CORSO (stato PENDING e non concluse): usate per l'indicatore "in corso"
    // nella lista e per l'interruzione manuale.
    List<BatchExecution> findByStatusAndEndedAtIsNull(String status);

    List<BatchExecution> findByBatchSubscriptionIdAndStatusAndEndedAtIsNull(Long subscriptionId, String status);

    // Esecuzioni PENDING piu' vecchie della soglia: col flusso "202 + callback" un servizio che non
    // richiama /finish (irraggiungibile, crashato, URL sbagliato) le lascerebbe PENDING per sempre.
    // @Transactional sul metodo: una UPDATE via @Modifying pretende una transazione attiva e il
    // chiamante e' un metodo @Scheduled, che non ne apre nessuna (altrimenti: "No active transaction
    // for update or delete query"). Messa qui e non sullo scheduler cosi' vale per ogni chiamante.
    // Si guarda l'ULTIMO SEGNO DI VITA, non l'istante di avvio: con startedAt il filtro misurava la
    // DURATA e chiudeva come fallita qualsiasi elaborazione piu' lunga della soglia, anche mentre
    // scriveva la telecronaca (il caricamento delle liste societarie dura anche un giorno). COALESCE
    // per le righe precedenti alla colonna, che non hanno il battito.
    @Modifying
    @Transactional
    @Query("update BatchExecution e set e.status = :to, e.endedAt = :now, e.errorMessage = :msg "
            + "where e.status = :from and e.endedAt is null "
            + "and coalesce(e.ultimoAggiornamento, e.startedAt) < :limite")
    int closeStalePending(@Param("from") String from, @Param("to") String to, @Param("now") LocalDateTime now,
            @Param("limite") LocalDateTime limite, @Param("msg") String msg);

    // Chiude le esecuzioni rimaste "in corso" (from) senza mai concludersi (ended_at IS NULL): usato al
    // riavvio per marcarle FAILED. La condizione ended_at IS NULL evita di toccare righe già concluse.
    @Modifying
    @Transactional
    @Query("update BatchExecution e set e.status = :to, e.endedAt = :now, e.errorMessage = :msg "
            + "where e.status = :from and e.endedAt is null")
    int closeStaleExecutions(@Param("from") String from, @Param("to") String to,
            @Param("now") LocalDateTime now, @Param("msg") String msg);
}
