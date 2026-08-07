package it.be.batch.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.ai.client.constants.AppConstants;
import it.be.batch.dto.Dtos.SftpExecutionResponse;
import it.be.batch.dto.Dtos.SftpScheduleRequest;
import it.be.batch.dto.Dtos.SftpScheduleResponse;
import it.be.batch.entity.IntermediarioRef;
import it.be.batch.entity.SftpExecution;
import it.be.batch.entity.SftpSchedule;
import it.be.batch.repo.IntermediarioRefRepository;
import it.be.batch.repo.SftpExecutionRepository;
import it.be.batch.repo.SftpScheduleRepository;
import it.common.base.batch.BatchJobRegistry;

/**
 * CRUD delle schedulazioni SFTP e lettura del loro storico: gemello di
 * {@link BatchSubscriptionService} per la pagina "Schedulazioni SFTP".
 */
@Service
public class SftpScheduleService {

	private static final Logger logger = LoggerFactory.getLogger(SftpScheduleService.class);

	private final SftpScheduleRepository scheduleRepository;
	private final SftpExecutionRepository executionRepository;
	private final IntermediarioRefRepository intermediarioRefRepository;
	private final CredentialCipher credentialCipher;
	private final SftpScheduler sftpScheduler;
	private final BatchJobRegistry batchJobRegistry;

	public SftpScheduleService(SftpScheduleRepository scheduleRepository, SftpExecutionRepository executionRepository,
			IntermediarioRefRepository intermediarioRefRepository, CredentialCipher credentialCipher,
			SftpScheduler sftpScheduler, BatchJobRegistry batchJobRegistry) {
		super();
		this.scheduleRepository = scheduleRepository;
		this.executionRepository = executionRepository;
		this.intermediarioRefRepository = intermediarioRefRepository;
		this.credentialCipher = credentialCipher;
		this.sftpScheduler = sftpScheduler;
		this.batchJobRegistry = batchJobRegistry;
	}

	public List<SftpScheduleResponse> findAll() {
		Map<Long, LocalDateTime> inCorso = esecuzioniInCorso();
		return scheduleRepository.findAllByOrderByOrdineAscIdAsc().stream().map(s -> toResponse(s, inCorso)).toList();
	}

	public List<SftpScheduleResponse> findByCustomerId(Long idIntermediario) {
		Map<Long, LocalDateTime> inCorso = esecuzioniInCorso();
		return scheduleRepository.findByIdIntermediarioOrderByOrdineAscIdAsc(idIntermediario).stream()
				.map(s -> toResponse(s, inCorso))
				.toList();
	}

	public SftpScheduleResponse findById(Long id) {
		return toResponse(carica(id), esecuzioniInCorso());
	}

	@Transactional
	public SftpScheduleResponse create(SftpScheduleRequest request) {
		valida(request, true);

		SftpSchedule e = new SftpSchedule();
		applica(e, request);
		e.setSftpPasswordEnc(credentialCipher.encrypt(request.sftpPassword()));
		e.setEnabled(request.enabled() == null || request.enabled());
		e.setDataCreazione(LocalDateTime.now());
		e.setNextRunAt(prossimaEsecuzione(e));
		// In fondo all'elenco: da li' chi l'ha creata la sposta dove serve.
		Integer max = scheduleRepository.maxOrdine();
		e.setOrdine((max == null ? 0 : max) + 1);

		return toResponse(scheduleRepository.save(e), esecuzioniInCorso());
	}

	/**
	 * Scambia di posto due schedulazioni: e' quello che fanno le frecce dell'elenco. La riga con cui
	 * scambiare la manda la pagina — e' quella che si VEDE sopra o sotto, che con un filtro attivo non
	 * coincide con la successiva in assoluto. Stessa logica delle schedulazioni batch.
	 */
	@Transactional
	public void scambiaOrdine(Long id, Long idAltro) {
		SftpSchedule a = carica(id);
		SftpSchedule b = carica(idAltro);

		int ordineA = (a.getOrdine() != null) ? a.getOrdine() : a.getId().intValue();
		int ordineB = (b.getOrdine() != null) ? b.getOrdine() : b.getId().intValue();
		if (ordineA == ordineB) {
			// Possibile solo fra righe mai riordinate: senza scarto lo scambio non muoverebbe niente.
			ordineB = ordineA + 1;
		}
		a.setOrdine(ordineB);
		b.setOrdine(ordineA);
		scheduleRepository.save(a);
		scheduleRepository.save(b);
	}

	@Transactional
	public SftpScheduleResponse update(Long id, SftpScheduleRequest request) {
		valida(request, false);

		SftpSchedule e = carica(id);
		applica(e, request);
		// Password vuota/null in update = invariata: non viene mai restituita in lettura, quindi il client
		// non ce l'ha per rimandarla.
		if (request.sftpPassword() != null && !request.sftpPassword().isBlank()) {
			e.setSftpPasswordEnc(credentialCipher.encrypt(request.sftpPassword()));
		}
		if (request.enabled() != null) {
			e.setEnabled(request.enabled());
		}
		e.setNextRunAt(prossimaEsecuzione(e));

		return toResponse(scheduleRepository.save(e), esecuzioniInCorso());
	}

	@Transactional
	public void enable(Long id) {
		SftpSchedule e = carica(id);
		e.setEnabled(true);
		e.setDataCessazione(null);
		if (e.getNextRunAt() == null) {
			e.setNextRunAt(prossimaEsecuzione(e));
		}
		scheduleRepository.save(e);
	}

	@Transactional
	public void disable(Long id) {
		SftpSchedule e = carica(id);
		e.setEnabled(false);
		e.setDataCessazione(LocalDateTime.now());
		scheduleRepository.save(e);
	}

	// Eliminazione DEFINITIVA: prima lo storico, altrimenti la foreign key sftp_execution ->
	// sftp_schedule impedirebbe la rimozione.
	@Transactional
	public void delete(Long id) {
		if (!scheduleRepository.existsById(id)) {
			throw new RuntimeException("Schedulazione SFTP non trovata: " + id);
		}
		executionRepository.deleteBySftpScheduleId(id);
		scheduleRepository.deleteById(id);
	}

	/**
	 * Storico trasferimenti, decrescente per data di inizio e paginato (pagina 1-based). Stessa forma
	 * dello storico delle schedulazioni batch.
	 */
	public it.be.batch.dto.Dtos.PaginaResponse<SftpExecutionResponse> findExecutions(Long scheduleId, int page,
			int size) {
		int p = Math.max(0, page - 1);
		int s = Math.min(Math.max(1, size), 200);
		org.springframework.data.domain.Page<it.be.batch.entity.SftpExecution> pagina = executionRepository
				.findBySftpScheduleIdOrderByStartedAtDesc(scheduleId,
						org.springframework.data.domain.PageRequest.of(p, s));
		return new it.be.batch.dto.Dtos.PaginaResponse<>(
				pagina.getContent().stream().map(this::toExecutionResponse).toList(), pagina.getTotalElements(), page,
				s);
	}

	/**
	 * Interrompe il trasferimento in corso.
	 * <p>
	 * ANELLO DECISIVO: l'interruzione e' COOPERATIVA. Si alza il flag di stop del job e il
	 * trasferimento esce dal ciclo appena finisce il file corrente, chiudendo da se' la propria riga
	 * come INTERROTTA con i contatori giusti. Non si uccide il thread: durante un upload lascerebbe sul
	 * server remoto un file troncato.
	 * <p>
	 * Se invece NON c'e' nessun trasferimento attivo in questo processo (tipico dopo un riavvio), le
	 * eventuali righe rimaste PENDING sono orfane e vengono chiuse qui.
	 */
	@Transactional
	public Map<String, Object> interrompi(Long scheduleId) {
		SftpSchedule s = carica(scheduleId);

		batchJobRegistry.get(SftpTransferService.jobName(scheduleId)).requestStop();
		boolean attivo = sftpScheduler.inCorsoDaFermare(scheduleId);

		int chiuse = 0;
		if (!attivo) {
			List<SftpExecution> orfane = executionRepository.findBySftpScheduleIdAndStatusAndEndedAtIsNull(scheduleId,
					AppConstants.STATUS_PENDING);
			LocalDateTime now = LocalDateTime.now();
			for (SftpExecution e : orfane) {
				e.setStatus(SftpTransferService.STATUS_INTERROTTA);
				e.setEndedAt(now);
				e.setErrorMessage("Esecuzione chiusa manualmente: nessun trasferimento attivo sul servizio");
				executionRepository.save(e);
			}
			chiuse = orfane.size();
			if (chiuse > 0) {
				s.setLastRunAt(now);
				s.setNextRunAt(prossimaEsecuzione(s));
				scheduleRepository.save(s);
			}
		}
		logger.warn("Schedulazione SFTP {}: stop richiesto (trasferimento attivo={}, righe orfane chiuse={})",
				scheduleId, attivo, chiuse);

		Map<String, Object> out = new HashMap<>();
		out.put("success", true);
		out.put("inEsecuzione", attivo);
		out.put("esecuzioniChiuse", chiuse);
		out.put("message", attivo
				? "Interruzione richiesta: il trasferimento si ferma al termine del file in corso"
				: (chiuse > 0 ? "Nessun trasferimento attivo: chiuse " + chiuse + " esecuzioni rimaste aperte"
						: "Nessun trasferimento in corso da interrompere"));
		return out;
	}

	// ------------------------------------------------------------------------------------------------
	// Interno
	// ------------------------------------------------------------------------------------------------

	private SftpSchedule carica(Long id) {
		return scheduleRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Schedulazione SFTP non trovata"));
	}

	private void valida(SftpScheduleRequest r, boolean creazione) {
		if (r.nome() == null || r.nome().isBlank()) {
			throw new RuntimeException("Il nome della schedulazione è obbligatorio");
		}
		if (r.idIntermediario() == null) {
			throw new RuntimeException("L'intermediario è obbligatorio");
		}
		if (!SftpSchedule.DIR_SFTP_TO_STORAGE.equals(r.direzione())
				&& !SftpSchedule.DIR_STORAGE_TO_SFTP.equals(r.direzione())) {
			throw new RuntimeException("Direzione non valida: " + r.direzione());
		}
		if (r.sftpHost() == null || r.sftpHost().isBlank() || r.sftpUsername() == null || r.sftpUsername().isBlank()) {
			throw new RuntimeException("Host e username SFTP sono obbligatori");
		}
		if (creazione && (r.sftpPassword() == null || r.sftpPassword().isBlank())) {
			throw new RuntimeException("La password SFTP è obbligatoria");
		}
		if (r.sftpPath() == null || r.sftpPath().isBlank()) {
			throw new RuntimeException("Il percorso remoto è obbligatorio");
		}
		if (r.storageIntermediario() == null || r.storageIntermediario().isBlank() || r.storageType() == null
				|| r.storageType().isBlank() || r.storageFolder() == null || r.storageFolder().isBlank()) {
			throw new RuntimeException("Intermediario, tipo e cartella dello storage sono obbligatori");
		}
		String post = r.postTransfer();
		if (post != null && !post.isBlank() && !SftpSchedule.POST_LASCIA.equals(post)
				&& !SftpSchedule.POST_CANCELLA.equals(post) && !SftpSchedule.POST_SPOSTA.equals(post)) {
			throw new RuntimeException("Politica post-trasferimento non valida: " + post);
		}
		if (SftpSchedule.POST_SPOSTA.equals(post)
				&& (r.postTransferFolder() == null || r.postTransferFolder().isBlank())) {
			throw new RuntimeException("Con la politica SPOSTA è obbligatoria la cartella di destinazione");
		}
	}

	private void applica(SftpSchedule e, SftpScheduleRequest r) {
		e.setNome(r.nome().trim());
		e.setIdIntermediario(r.idIntermediario());
		e.setDirezione(r.direzione());
		e.setSftpHost(r.sftpHost().trim());
		e.setSftpPort((r.sftpPort() == null || r.sftpPort() <= 0) ? 22 : r.sftpPort());
		e.setSftpUsername(r.sftpUsername().trim());
		e.setSftpPath(r.sftpPath().trim());
		e.setFilePattern(blankToNull(r.filePattern()));
		e.setStorageIntermediario(r.storageIntermediario().trim());
		e.setStorageType(r.storageType().trim());
		e.setStorageFolder(r.storageFolder().trim());
		e.setPostTransfer(blankToNull(r.postTransfer()) == null ? SftpSchedule.POST_LASCIA : r.postTransfer());
		e.setPostTransferFolder(blankToNull(r.postTransferFolder()));
		e.setCronExpression(blankToNull(r.cronExpression()));
		e.setTimezone(blankToNull(r.timezone()) == null ? "Europe/Rome" : r.timezone());
		e.setIdUtenteAdmin(r.idUtenteAdmin());
		e.setStartAt(parseStartAt(r.startAt()));
	}

	private LocalDateTime prossimaEsecuzione(SftpSchedule s) {
		return CronScheduleUtil.nextRun(s.getCronExpression(), s.getTimezone(), s.getStartAt());
	}

	// Cron vuoto/blank -> null: schedulazione "manuale" (solo Esegui ora).
	private String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s.trim();
	}

	// startAt dal client come ISO locale "yyyy-MM-ddTHH:mm" (input datetime-local).
	private LocalDateTime parseStartAt(String startAt) {
		if (startAt == null || startAt.isBlank()) {
			return null;
		}
		try {
			return LocalDateTime.parse(startAt);
		} catch (Exception e) {
			throw new RuntimeException("Data e ora di partenza non valide: " + startAt);
		}
	}

	private String nomeIntermediario(Long idIntermediario) {
		if (idIntermediario == null) {
			return null;
		}
		return intermediarioRefRepository.findById(idIntermediario).map(IntermediarioRef::getNominativo).orElse(null);
	}

	/** Schedulazioni con un trasferimento ancora in corso: una sola query per l'intera lista. */
	private Map<Long, LocalDateTime> esecuzioniInCorso() {
		Map<Long, LocalDateTime> m = new HashMap<>();
		for (SftpExecution e : executionRepository.findByStatusAndEndedAtIsNull(AppConstants.STATUS_PENDING)) {
			if (e.getSftpSchedule() != null) {
				m.merge(e.getSftpSchedule().getId(), e.getStartedAt(),
						(a, b) -> (a == null || (b != null && b.isBefore(a))) ? b : a);
			}
		}
		return m;
	}

	// NB: nessun campo password nella risposta.
	private SftpScheduleResponse toResponse(SftpSchedule e, Map<Long, LocalDateTime> inCorso) {
		LocalDateTime da = inCorso.get(e.getId());
		return new SftpScheduleResponse(e.getId(), e.getNome(), e.getIdIntermediario(),
				nomeIntermediario(e.getIdIntermediario()), e.getDirezione(), e.getSftpHost(), e.getSftpPort(),
				e.getSftpUsername(), e.getSftpPath(), e.getFilePattern(), e.getStorageIntermediario(),
				e.getStorageType(), e.getStorageFolder(), e.getPostTransfer(), e.getPostTransferFolder(),
				e.getCronExpression(), e.getTimezone(), e.isEnabled(), e.getLastRunAt(), e.getNextRunAt(),
				e.getStartAt(), e.getIdUtenteAdmin(), da != null, da);
	}

	private SftpExecutionResponse toExecutionResponse(SftpExecution e) {
		Long durationMs = (e.getStartedAt() != null && e.getEndedAt() != null)
				? Duration.between(e.getStartedAt(), e.getEndedAt()).toMillis()
				: null;
		return new SftpExecutionResponse(e.getId(), e.getStatus(), e.getStartedAt(), e.getEndedAt(), durationMs,
				e.getFileTrasferiti(), e.getByteTrasferiti(), e.getErrorMessage(), e.getLog());
	}
}
