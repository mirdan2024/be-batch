package it.be.batch.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import it.ai.client.constants.AppConstants;
import it.be.batch.repo.BatchExecutionRepository;

/**
 * All'avvio marca FAILED le esecuzioni rimaste PENDING senza conclusione (ended_at NULL): sono orfane —
 * la JVM precedente è morta mentre erano in corso e non potranno mai completarsi. Le PENDING realmente
 * "in corso" appartengono sempre a questa JVM appena avviata (che non ne ha ancora create), quindi non
 * c'è il rischio di chiudere un'esecuzione davvero attiva. Le righe già concluse (ended_at valorizzato)
 * non vengono toccate.
 */
@Component
public class BatchStartupRecovery {

	private static final Logger logger = LoggerFactory.getLogger(BatchStartupRecovery.class);

	private final BatchExecutionRepository executionRepository;

	public BatchStartupRecovery(BatchExecutionRepository executionRepository) {
		this.executionRepository = executionRepository;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void recoverStaleExecutions() {
		int n = executionRepository.closeStaleExecutions(AppConstants.STATUS_PENDING, AppConstants.STATUS_FAILED,
				LocalDateTime.now(), "Esecuzione interrotta dal riavvio dell'applicazione (mai conclusa)");
		if (n > 0) {
			logger.warn("Recupero all'avvio: {} esecuzioni rimaste PENDING senza conclusione marcate FAILED", n);
		}
	}
}
