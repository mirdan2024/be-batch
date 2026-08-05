package it.be.batch.service;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.be.batch.dto.Dtos.BatchExecutionRequest;
import it.be.batch.entity.BatchExecution;
import it.be.batch.repo.BatchExecutionRepository;

@Service
public class BatchExecutionService {

	private final BatchExecutionRepository repository;
	// Concatenamento: per i servizi che rispondono 202 e' QUI che l'esecuzione si chiude davvero, quindi
	// e' qui che va deciso se lanciare il lavoro successivo.
	private final BatchChainService chainService;

	public BatchExecutionService(BatchExecutionRepository repository, BatchChainService chainService) {
		super();
		this.repository = repository;
		this.chainService = chainService;
	}


	/**
	 * Aggiunge una riga alla telecronaca dell'esecuzione, con timestamp. Append: le righe precedenti
	 * restano, cosi' si legge tutto il percorso dell'elaborazione dall'inizio alla fine.
	 */
	@org.springframework.transaction.annotation.Transactional
	public void appendLog(Long id, String message) {
		if (message == null || message.isBlank()) {
			return;
		}
		repository.findById(id).ifPresent(e -> {
			String riga = java.time.LocalDateTime.now()
					.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "  " + message;
			String precedente = e.getLog();
			e.setLog((precedente == null || precedente.isBlank()) ? riga : precedente + System.lineSeparator() + riga);
			repository.save(e);
		});
	}

	/**
	 * Chiude l'esecuzione con lo stato dichiarato dal SERVIZIO che ha elaborato (COMPLETED / FAILED /
	 * INTERROTTA). Sostituisce l'esito dedotto da be-batch in base alla risposta HTTP, che su
	 * elaborazioni lunghe finiva in read timeout pur essendo il servizio a posto.
	 */
	@org.springframework.transaction.annotation.Transactional
	public void finish(Long id, String status, String message, String responseBody) {
		final String statoFinale = (status == null || status.isBlank()) ? "COMPLETED" : status.trim().toUpperCase();
		it.be.batch.entity.BatchSubscription subscription = repository.findById(id).map(e -> {
			e.setStatus(statoFinale);
			e.setEndedAt(java.time.LocalDateTime.now());
			if (message != null && !message.isBlank()) {
				e.setErrorMessage(message);
			}
			if (responseBody != null && !responseBody.isBlank()) {
				e.setResponseBody(responseBody);
			}
			repository.save(e);
			// Letto DENTRO la transazione: la relazione e' LAZY e fuori non sarebbe piu' raggiungibile.
			return e.getBatchSubscription();
		}).orElse(null);

		appendLog(id, "Esecuzione chiusa dal servizio con stato " + status
				+ (message != null && !message.isBlank() ? " - " + message : ""));

		// Concatenamento: e' questo il momento in cui il lavoro e' davvero finito (i servizi lunghi
		// rispondono 202 e chiudono qui). Non deve mai far fallire la chiusura dell'esecuzione, che e'
		// l'informazione importante.
		try {
			chainService.esecuzioneConclusa(subscription, statoFinale);
		} catch (Exception e) {
			LoggerFactory.getLogger(BatchExecutionService.class)
					.error("Catena non avviata dopo l'esecuzione {}: {}", id, e.getMessage(), e);
		}
	}

	@Transactional
	public void update(Long id, BatchExecutionRequest request) {

		BatchExecution entity = repository.findById(id)
				.orElseThrow(() -> new RuntimeException("Esecuzione batch non trovata: " + id));

		entity.setResponseBody(request.response());
		entity.setStatus(request.status());
		entity.setResponseCode(request.response_code());

		repository.save(entity);
	}

}
