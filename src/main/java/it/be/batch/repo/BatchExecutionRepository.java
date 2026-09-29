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

    // Ripresa ("Riprendi" nello storico): l'esecuzione deve appartenere alla schedulazione indicata, e
    // si riprende solo l'ultima — riprenderne una vecchia, quando dopo c'e' stato altro, non ha senso.
    java.util.Optional<BatchExecution> findByIdAndBatchSubscriptionId(Long id, Long subscriptionId);

    java.util.Optional<BatchExecution> findFirstByBatchSubscriptionIdOrderByStartedAtDescIdDesc(Long subscriptionId);

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

    // ---------------------------------------------------------------------------------------------
    // Scritture MIRATE sull'esecuzione. Alla stessa riga scrivono, anche insieme, l'esecutore (esito della
    // chiamata HTTP), il servizio a valle (telecronaca e chiusura) e l'amministratore (Stop). Leggere la
    // riga, cambiarla e risalvarla per intero faceva vincere l'ultimo: il 202 salvato dall'esecutore
    // spariva sotto una riga di telecronaca salvata un attimo dopo con la copia letta prima (HTTP "-"
    // nello storico), e una chiusura poteva riportare indietro la telecronaca. Ognuna di queste scrive
    // solo le colonne che le spettano.
    // ---------------------------------------------------------------------------------------------

    /** Una riga in coda alla telecronaca, con il battito (vedi closeStalePending). */
    @Modifying
    @Transactional
    @Query("update BatchExecution e set e.log = case when e.log is null or e.log = '' then :riga"
            + " else concat(e.log, :acapo, :riga) end, e.ultimoAggiornamento = :adesso where e.id = :id")
    int aggiungiRigaLog(@Param("id") Long id, @Param("riga") String riga, @Param("acapo") String acapo,
            @Param("adesso") LocalDateTime adesso);

    /** Servizio che ha preso in carico (202): solo il codice, stato e chiusura li dichiarera' lui. */
    @Modifying
    @Transactional
    @Query("update BatchExecution e set e.responseCode = :codice where e.id = :id")
    int registraCodice(@Param("id") Long id, @Param("codice") Integer codice);

    /**
     * Esito di una chiamata sincrona. Codice e corpo sempre; stato, errore e fine solo se l'esecuzione non
     * e' gia' stata chiusa dal servizio, che vince sull'esito dedotto dalla risposta HTTP. endedAt per
     * ULTIMO: MySQL valuta le assegnazioni in ordine, e le due precedenti devono vederlo com'era.
     */
    @Modifying
    @Transactional
    @Query("update BatchExecution e set e.responseCode = :codice, e.responseBody = :corpo,"
            + " e.status = case when e.endedAt is null then :stato else e.status end,"
            + " e.errorMessage = case when e.endedAt is null then :errore else e.errorMessage end,"
            + " e.endedAt = coalesce(e.endedAt, :fine) where e.id = :id")
    int registraEsito(@Param("id") Long id, @Param("codice") Integer codice, @Param("corpo") String corpo,
            @Param("stato") String stato, @Param("errore") String errore, @Param("fine") LocalDateTime fine);

    /** Chiusura dichiarata dal servizio (/finish): messaggio e corpo solo se ci sono. */
    @Modifying
    @Transactional
    @Query("update BatchExecution e set e.status = :stato, e.endedAt = :fine,"
            + " e.errorMessage = coalesce(:messaggio, e.errorMessage),"
            + " e.responseBody = coalesce(:corpo, e.responseBody) where e.id = :id")
    int chiudi(@Param("id") Long id, @Param("stato") String stato, @Param("fine") LocalDateTime fine,
            @Param("messaggio") String messaggio, @Param("corpo") String corpo);

    /** Stop dell'amministratore: solo se l'esecuzione e' ancora aperta (non si riscrive un esito gia' dato). */
    @Modifying
    @Transactional
    @Query("update BatchExecution e set e.status = :stato, e.endedAt = :fine, e.errorMessage = :messaggio"
            + " where e.id = :id and e.endedAt is null")
    int chiudiSeAperta(@Param("id") Long id, @Param("stato") String stato, @Param("fine") LocalDateTime fine,
            @Param("messaggio") String messaggio);

    /** Aggiornamento generico dell'esito (PUT /batch-executions/{id}): stato, codice e corpo. */
    @Modifying
    @Transactional
    @Query("update BatchExecution e set e.responseBody = :corpo, e.status = :stato, e.responseCode = :codice"
            + " where e.id = :id")
    int aggiornaEsito(@Param("id") Long id, @Param("corpo") String corpo, @Param("stato") String stato,
            @Param("codice") Integer codice);
}
