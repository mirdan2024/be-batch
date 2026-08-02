package it.be.batch.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.be.batch.dto.Dtos.BatchExecutionRequest;
import it.be.batch.entity.BatchExecution;
import it.be.batch.repo.BatchExecutionRepository;

@Service
public class BatchExecutionService {

	private final BatchExecutionRepository repository;


	public BatchExecutionService(BatchExecutionRepository repository) {
		super();
		this.repository = repository;
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
		repository.findById(id).ifPresent(e -> {
			e.setStatus((status == null || status.isBlank()) ? "COMPLETED" : status.trim().toUpperCase());
			e.setEndedAt(java.time.LocalDateTime.now());
			if (message != null && !message.isBlank()) {
				e.setErrorMessage(message);
			}
			if (responseBody != null && !responseBody.isBlank()) {
				e.setResponseBody(responseBody);
			}
			repository.save(e);
		});
		appendLog(id, "Esecuzione chiusa dal servizio con stato " + status
				+ (message != null && !message.isBlank() ? " - " + message : ""));
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
