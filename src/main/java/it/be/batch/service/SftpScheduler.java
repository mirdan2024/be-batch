package it.be.batch.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import it.ai.client.constants.AppConstants;
import it.be.batch.entity.SftpSchedule;
import it.be.batch.repo.SftpExecutionRepository;
import it.be.batch.repo.SftpScheduleRepository;

/**
 * Dispatch delle schedulazioni SFTP: stessa meccanica di {@link BatchScheduler} (cron scaduto ->
 * esecuzione, "Esegui ora" manuale, chiusura delle esecuzioni piantate), ma il lavoro non e' una
 * chiamata HTTP a un altro servizio: il trasferimento gira qui dentro, in un thread dedicato.
 */
@Component
public class SftpScheduler {

	private static final Logger logger = LoggerFactory.getLogger(SftpScheduler.class);

	// Trasferimenti in corso, per poterli interrompere dalla UI. Vale sia per gli schedulati sia per i
	// manuali: girano tutti sullo stesso pool, quindi sono tutti cancellabili.
	private final ConcurrentHashMap<Long, Future<?>> inCorso = new ConcurrentHashMap<>();

	// Pool dedicato: serve un Future vero da poter cancellare. Thread daemon, cosi' non trattengono la
	// JVM allo spegnimento.
	private final ExecutorService transferExecutor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "SftpTransfer-");
		t.setDaemon(true);
		return t;
	});

	@Value("${sftp.execution.stale-timeout-hours:6}")
	private int staleTimeoutHours;

	private final SftpScheduleRepository scheduleRepository;
	private final SftpExecutionRepository executionRepository;
	private final SftpTransferService transferService;

	public SftpScheduler(SftpScheduleRepository scheduleRepository, SftpExecutionRepository executionRepository,
			SftpTransferService transferService) {
		super();
		this.scheduleRepository = scheduleRepository;
		this.executionRepository = executionRepository;
		this.transferService = transferService;
	}

	@Scheduled(fixedDelayString = "${sftp.scheduler.fixed-delay-ms:30000}")
	public void dispatch() {
		LocalDateTime now = LocalDateTime.now();
		List<SftpSchedule> scadute = scheduleRepository.findByEnabledTrueAndNextRunAtLessThanEqual(now);
		for (SftpSchedule s : scadute) {
			if (s.getDataCessazione() != null) {
				continue;
			}
			avvia(s.getId());
		}
	}

	/**
	 * Rete di sicurezza: se be-batch viene riavviato a meta' trasferimento la riga resterebbe PENDING
	 * per sempre e la clessidra girerebbe all'infinito. Qui si chiudono quelle troppo vecchie.
	 */
	@Scheduled(fixedDelayString = "${sftp.execution.stale-check-ms:600000}")
	public void chiudiEsecuzioniPiantate() {
		LocalDateTime limite = LocalDateTime.now().minusHours(staleTimeoutHours);
		int chiuse = executionRepository.closeStalePending(AppConstants.STATUS_PENDING, AppConstants.STATUS_FAILED,
				LocalDateTime.now(), limite, "Nessun aggiornamento da oltre " + staleTimeoutHours
						+ " ore: esecuzione chiusa d'ufficio (probabile riavvio del servizio).");
		if (chiuse > 0) {
			logger.warn("Chiuse {} esecuzioni SFTP rimaste PENDING oltre {} ore", chiuse, staleTimeoutHours);
		}
	}

	/**
	 * Esecuzione UNA TANTUM richiesta dalla UI: parte subito, anche fuori orario e anche se la
	 * schedulazione e' bloccata (il click e' una volonta' esplicita; il blocco ferma solo il cron).
	 * Asincrona: l'esito si legge nello storico.
	 *
	 * @return AVVIATA | NON_TROVATA | GIA_IN_ESECUZIONE
	 */
	public String eseguiUnaTantum(Long scheduleId) {
		SftpSchedule s = scheduleRepository.findById(scheduleId).orElse(null);
		if (s == null) {
			return "NON_TROVATA";
		}
		if (inCorso.containsKey(scheduleId)) {
			return "GIA_IN_ESECUZIONE";
		}
		avvia(scheduleId);
		return "AVVIATA";
	}

	private void avvia(Long scheduleId) {
		// putIfAbsent con un segnaposto non basta: si registra il Future subito dopo il submit, e il task
		// si rimuove da solo alla fine. Un doppio avvio e' comunque bloccato da BatchJobControl.begin().
		if (inCorso.containsKey(scheduleId)) {
			logger.info("Trasferimento SFTP {} gia' in corso: avvio ignorato", scheduleId);
			return;
		}
		Future<?> f = transferExecutor.submit(() -> {
			try {
				transferService.esegui(scheduleId);
			} catch (Exception e) {
				logger.error("Trasferimento SFTP {} terminato con errore: {}", scheduleId, e.getMessage(), e);
			} finally {
				inCorso.remove(scheduleId);
			}
		});
		inCorso.put(scheduleId, f);
	}

	/**
	 * True se per questa schedulazione c'e' un trasferimento realmente in corso in questo processo.
	 * <p>
	 * NON si cancella il thread: l'interruzione e' SOLO cooperativa (il flag del job, controllato da
	 * {@link SftpTransferService} tra un file e l'altro). Uccidere il thread durante una {@code put}
	 * lascerebbe sul server remoto un file TRONCATO, che un sistema a valle potrebbe prendere per
	 * buono: molto peggio di aspettare la fine del file in corso (limitata comunque dal read timeout).
	 * Il task si toglie da solo dal registro quando termina.
	 */
	public boolean inCorsoDaFermare(Long scheduleId) {
		return inCorso.containsKey(scheduleId);
	}

}
