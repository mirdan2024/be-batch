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
	 * <p>
	 * Direttamente in SQL, in coda a quello che c'e' in quel momento: rileggere la riga e risalvarla per
	 * intero riscriveva anche il codice HTTP, lo stato e la fine con i valori letti un attimo prima, e fra
	 * due scritture vicine vinceva l'ultima (vedi BatchExecutionRepository).
	 */
	@org.springframework.transaction.annotation.Transactional
	public void appendLog(Long id, String message) {
		aggiungiRigaLog(id, message);
	}

	/**
	 * Il corpo di {@link #appendLog}, senza passare dal proxy: {@link #finish} lo chiama dall'interno,
	 * gia' nella propria transazione (appendLog e' REQUIRED, quindi ci si univa comunque a quella).
	 */
	private void aggiungiRigaLog(Long id, String message) {
		if (message == null || message.isBlank()) {
			return;
		}
		java.time.LocalDateTime adesso = java.time.LocalDateTime.now(java.time.ZoneId.systemDefault());
		String riga = adesso.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "  "
				+ message;
		// Battito del servizio: e' su questo che la rete di sicurezza decide se l'esecuzione e' morta.
		// Finche' arrivano righe di telecronaca, sta lavorando — per quanto a lungo duri.
		repository.aggiungiRigaLog(id, riga, System.lineSeparator(), adesso);
	}

	/**
	 * Chiude l'esecuzione con lo stato dichiarato dal SERVIZIO che ha elaborato (COMPLETED / FAILED /
	 * INTERROTTA). Sostituisce l'esito dedotto da be-batch in base alla risposta HTTP, che su
	 * elaborazioni lunghe finiva in read timeout pur essendo il servizio a posto.
	 */
	@org.springframework.transaction.annotation.Transactional
	public void finish(Long id, String status, String message, String responseBody) {
		final String statoFinale = (status == null || status.isBlank()) ? "COMPLETED" : status.trim().toUpperCase();
		// Solo stato, fine, messaggio e corpo: il codice HTTP e la telecronaca non si toccano.
		repository.chiudi(id, statoFinale, java.time.LocalDateTime.now(java.time.ZoneId.systemDefault()),
				(message != null && !message.isBlank()) ? message : null,
				(responseBody != null && !responseBody.isBlank()) ? responseBody : null);
		// Letta DENTRO la transazione: la relazione e' LAZY e fuori non sarebbe piu' raggiungibile.
		it.be.batch.entity.BatchSubscription subscription = repository.findById(id)
				.map(BatchExecution::getBatchSubscription).orElse(null);

		aggiungiRigaLog(id, "Esecuzione chiusa dal servizio con stato " + status
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
		// Stesse colonne di prima, senza risalvare la riga intera (telecronaca compresa).
		if (repository.aggiornaEsito(id, request.response(), request.status(), request.response_code()) == 0) {
			throw new BatchException("Esecuzione batch non trovata: " + id);
		}
	}

}
