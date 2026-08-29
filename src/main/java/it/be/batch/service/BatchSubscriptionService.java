package it.be.batch.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import it.ai.client.constants.AppConstants;
import it.be.batch.dto.Dtos.BatchExecutionResponse;
import it.be.batch.dto.Dtos.BatchSubscriptionRequest;
import it.be.batch.dto.Dtos.BatchSubscriptionResponse;
import it.be.batch.dto.Dtos.CalendarioResponse;
import it.be.batch.dto.Dtos.OccorrenzaCalendario;
import it.be.batch.dto.Dtos.LoginResponse;
import it.be.batch.dto.Dtos.TestCredentialsResponse;
import it.be.batch.dto.LoginPojo;
import it.be.batch.entity.BatchDefinition;
import it.be.batch.entity.BatchExecution;
import it.be.batch.entity.BatchSubscription;
import it.be.batch.entity.IntermediarioRef;
import it.be.batch.repo.BatchDefinitionRepository;
import it.be.batch.repo.BatchExecutionRepository;
import it.be.batch.repo.BatchSubscriptionRepository;
import it.be.batch.repo.IntermediarioRefRepository;

@Service
public class BatchSubscriptionService {

	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory
			.getLogger(BatchSubscriptionService.class);

	/** Esito di un'esecuzione fermata a mano (gli altri stati stanno in AppConstants). */
	public static final String STATUS_INTERROTTA = "INTERROTTA";

	private final BatchSubscriptionRepository subscriptionRepository;
	private final BatchDefinitionRepository definitionRepository;
	private final BatchExecutionRepository executionRepository;
	private final CredentialCipher credentialCipher;
	private final IntermediarioRefRepository intermediarioRefRepository;
	private final RestTemplate restTemplate;
	private final BatchScheduler batchScheduler;

	@Value("${url.bebase.login}")
	private String urlBeBaseLoginService;

	// Token per le chiamate service-to-service (usato per invocare lo stop_url del servizio a valle).
	@Value("${routing.internal-token:}")
	private String internalToken;

	/**
	 * Toglie gli spazi dal token interno. Un valore generato con {@code openssl rand -base64 32} si
	 * porta dietro un a capo, e un a capo dentro il valore di un header fa fallire la chiamata con
	 * un {@code invalid header value} che non nomina la property colpevole.
	 */
	@jakarta.annotation.PostConstruct
	void normalizzaTokenInterno() {
		if (internalToken != null) {
			internalToken = internalToken.trim();
		}
	}

	public BatchSubscriptionService(BatchSubscriptionRepository subscriptionRepository,
			BatchDefinitionRepository definitionRepository, BatchExecutionRepository executionRepository,
			CredentialCipher credentialCipher, IntermediarioRefRepository intermediarioRefRepository,
			@Qualifier("RestTimeout") RestTemplate restTemplate, @Lazy BatchScheduler batchScheduler) {
		super();
		this.subscriptionRepository = subscriptionRepository;
		this.definitionRepository = definitionRepository;
		this.executionRepository = executionRepository;
		this.credentialCipher = credentialCipher;
		this.intermediarioRefRepository = intermediarioRefRepository;
		this.restTemplate = restTemplate;
		this.batchScheduler = batchScheduler;
	}

	/**
	 * Prova le credenziali indicate facendo il login su be-base ({@code doLoginBatch}), lo stesso che usa
	 * lo scheduler al momento dell'esecuzione. La UI lo richiama PRIMA del salvataggio: cosi' una password
	 * errata (o riempita dall'autofill del browser) si scopre subito e non al primo cron.
	 * Non solleva: l'esito negativo e' un dato di ritorno, non un errore HTTP.
	 */
	public TestCredentialsResponse testCredentials(String username, String password) {
		if (username == null || username.isBlank() || password == null || password.isBlank()) {
			return new TestCredentialsResponse(false, "Username e password sono obbligatori per la verifica.");
		}
		return eseguiLoginDiProva(username.trim(), password);
	}

	/**
	 * Prova le credenziali GIA' SALVATE sulla sottoscrizione (decifra e tenta il login). Utile per
	 * diagnosticare una schedulazione esistente senza doverne reinserire la password.
	 */
	public TestCredentialsResponse testStoredCredentials(Long id) {
		BatchSubscription entity = subscriptionRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));
		String password;
		try {
			password = credentialCipher.decrypt(entity.getPasswordEnc());
		} catch (Exception e) {
			// Tipico se BATCH_CRED_SECRET/SALT sono cambiati dopo il salvataggio.
			return new TestCredentialsResponse(false,
					"Password memorizzata non decifrabile: reinserire le credenziali.");
		}
		if (password == null || password.isBlank()) {
			return new TestCredentialsResponse(false, "Nessuna password memorizzata sulla schedulazione.");
		}
		return eseguiLoginDiProva(entity.getUsername(), password);
	}

	private TestCredentialsResponse eseguiLoginDiProva(String username, String password) {
		LoginPojo loginPojo = new LoginPojo();
		loginPojo.setUsername(username);
		loginPojo.setPassword(password);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		try {
			// NB: non loggare il body — contiene la password in chiaro.
			ResponseEntity<LoginResponse> resp = restTemplate.postForEntity(urlBeBaseLoginService,
					new HttpEntity<>(loginPojo, headers), LoginResponse.class);
			LoginResponse body = resp.getBody();
			if (body != null && body.jwt() != null && !body.jwt().isBlank()) {
				return new TestCredentialsResponse(true, "Credenziali valide.");
			}
			return new TestCredentialsResponse(false, "Login riuscito ma senza token: verificare be-base.");
		} catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
			return new TestCredentialsResponse(false, "Credenziali non valide (utenza inesistente, cessata o password errata).");
		} catch (Exception e) {
			return new TestCredentialsResponse(false, "Verifica non riuscita: " + e.getMessage());
		}
	}

	public List<BatchSubscriptionResponse> findAll() {
		return subscriptionRepository.findAllByOrderByOrdineAscIdAsc().stream().map(this::toResponse).toList();
	}

	/** Quante occorrenze al massimo per singola schedulazione: un cron al secondo ne farebbe 86.400 al giorno. */
	private static final int MAX_OCCORRENZE_PER_SCHEDULAZIONE = 500;

	/**
	 * Il calendario delle partenze previste in una finestra.
	 *
	 * <p>
	 * Serve a pianificare: prima di aggiungere una schedulazione si guarda cosa c'e' gia' in quella
	 * fascia oraria. Per questo comprende anche le schedulazioni <b>bloccate</b> — sono spente adesso,
	 * ma riaccenderle e' un attimo e tornerebbero a occupare lo slot.
	 * </p>
	 *
	 * <p>
	 * Le occorrenze le calcola {@link CronScheduleUtil#occorrenze}, cioe' lo stesso motore con cui lo
	 * scheduler decide le partenze vere: un calendario costruito con un altro parser mostrerebbe orari
	 * che non succedono.
	 * </p>
	 */
	public CalendarioResponse calendario(LocalDateTime da, LocalDateTime a, Long idIntermediario) {
		List<OccorrenzaCalendario> occorrenze = new ArrayList<>();
		List<String> avvisi = new ArrayList<>();
		int senzaCron = 0;

		List<BatchSubscription> sottoscrizioni = (idIntermediario != null)
				? subscriptionRepository.findByIdIntermediarioOrderByOrdineAscIdAsc(idIntermediario)
				: subscriptionRepository.findAllByOrderByOrdineAscIdAsc();

		for (BatchSubscription s : sottoscrizioni) {
			// Le schedulazioni cessate non esistono piu' per nessuno: non sono "bloccate", sono tolte.
			if (s.getDataCessazione() != null) {
				continue;
			}
			String codice = (s.getBatchDefinition() != null) ? s.getBatchDefinition().getCode() : null;
			if (s.getCronExpression() == null || s.getCronExpression().isBlank()) {
				// Manuale: nessuna partenza automatica, quindi nessuno slot occupato. Si conta perche'
				// chi guarda il calendario deve sapere che esistono lavori che non compaiono qui.
				senzaCron++;
				continue;
			}
			List<LocalDateTime> quando = CronScheduleUtil.occorrenze(s.getCronExpression(), s.getTimezone(),
					s.getStartAt(), da, a, MAX_OCCORRENZE_PER_SCHEDULAZIONE);
			if (quando.isEmpty() && !cronValido(s.getCronExpression())) {
				avvisi.add("Espressione cron non valida su \"" + codice + "\" (id " + s.getId()
						+ "): la schedulazione non compare sul calendario");
				continue;
			}
			if (quando.size() >= MAX_OCCORRENZE_PER_SCHEDULAZIONE) {
				avvisi.add("\"" + codice + "\" (id " + s.getId() + ") supera le "
						+ MAX_OCCORRENZE_PER_SCHEDULAZIONE + " partenze nel periodo: ne sono mostrate solo le prime");
			}
			for (LocalDateTime q : quando) {
				occorrenze.add(new OccorrenzaCalendario(q, s.getId(), codice, null,
						nomeIntermediario(s.getIdIntermediario()), s.isEnabled(), s.getCronExpression(),
						s.getTimezone()));
			}
		}
		occorrenze.sort(java.util.Comparator.comparing(OccorrenzaCalendario::quando));
		return new CalendarioResponse(da, a, occorrenze, avvisi, senzaCron);
	}

	private boolean cronValido(String cron) {
		try {
			org.springframework.scheduling.support.CronExpression.parse(cron);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public List<BatchSubscriptionResponse> findByCustomerId(Long customerId) {
		return subscriptionRepository.findByIdIntermediarioOrderByOrdineAscIdAsc(customerId).stream()
				.map(this::toResponse).toList();
	}

	/**
	 * Scambia di posto due schedulazioni: e' quello che fanno le frecce dell'elenco.
	 * <p>
	 * La riga con cui scambiare la sceglie la pagina e la manda: e' quella che si VEDE sopra o sotto,
	 * che con un filtro attivo non e' detto sia la successiva in assoluto. Deciderlo qui, sull'ordine
	 * completo, farebbe sparire la riga dall'elenco filtrato senza che si sia mossa dove ci si aspetta.
	 * <p>
	 * Le righe mai riordinate possono avere {@code ordine} nullo: si ripiega sull'id, che e' il valore
	 * con cui la colonna e' stata inizializzata.
	 */
	@Transactional
	public void scambiaOrdine(Long id, Long idAltro) {
		BatchSubscription a = subscriptionRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));
		BatchSubscription b = subscriptionRepository.findById(idAltro)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch da scambiare non trovata"));

		int ordineA = (a.getOrdine() != null) ? a.getOrdine() : a.getId().intValue();
		int ordineB = (b.getOrdine() != null) ? b.getOrdine() : b.getId().intValue();
		if (ordineA == ordineB) {
			// Puo' succedere solo fra righe mai riordinate con id diversi: si forza uno scarto, altrimenti
			// lo scambio non cambierebbe niente e la freccia sembrerebbe rotta.
			ordineB = ordineA + 1;
		}
		a.setOrdine(ordineB);
		b.setOrdine(ordineA);
		subscriptionRepository.save(a);
		subscriptionRepository.save(b);
		logger.info("Schedulazioni {} e {} scambiate di posto ({} <-> {})", id, idAltro, ordineA, ordineB);
	}

	public BatchSubscriptionResponse findById(Long id) {
		BatchSubscription entity = subscriptionRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));

		return toResponse(entity);
	}

	@Transactional
	public BatchSubscriptionResponse create(BatchSubscriptionRequest request) {

		BatchDefinition definition = definitionRepository.findById(request.batchDefinitionId())
				.orElseThrow(() -> new RuntimeException("Batch definition non trovata"));

		if (request.username() == null || request.username().isBlank()
				|| request.password() == null || request.password().isBlank()) {
			throw new RuntimeException("Username e password sono obbligatori per la sottoscrizione batch");
		}

		BatchSubscription entity = new BatchSubscription();
		entity.setIdIntermediario(request.idIntermediario());
		entity.setBatchDefinition(definition);
		entity.setCronExpression(blankToNull(request.cronExpression()));
		entity.setUsername(request.username());
		// La password è cifrata a riposo: il DB non la vede mai in chiaro.
		entity.setPasswordEnc(credentialCipher.encrypt(request.password()));
		entity.setTimezone(request.timezone() != null ? request.timezone() : "Europe/Rome");
		entity.setEnabled(request.enabled() == null || request.enabled());
		entity.setParamsJson(request.paramsJson());
		entity.setBodyJson(request.bodyJson());
		entity.setIdUtenteAdmin(request.idUtenteAdmin());
		entity.setDataCreazione(LocalDateTime.now());
		entity.setStartAt(parseStartAt(request.startAt()));
		entity.setJobSuccessivo(normalizzaJobSuccessivo(request.jobSuccessivo()));
		entity.setNextRunAt(calculateNextRun(entity));
		// In fondo all'elenco: da li' chi l'ha creata la sposta dove serve. Inserirla in mezzo con un
		// criterio automatico sarebbe una scelta al posto suo, e sbagliata quasi sempre.
		Integer max = subscriptionRepository.maxOrdine();
		entity.setOrdine((max == null ? 0 : max) + 1);

		return toResponse(subscriptionRepository.save(entity));
	}

	@Transactional
	public BatchSubscriptionResponse update(Long id, BatchSubscriptionRequest request) {

		BatchSubscription entity = subscriptionRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));

		BatchDefinition definition = definitionRepository.findById(request.batchDefinitionId())
				.orElseThrow(() -> new RuntimeException("Batch definition non trovata"));

		entity.setIdIntermediario(request.idIntermediario());
		entity.setBatchDefinition(definition);
		entity.setCronExpression(blankToNull(request.cronExpression()));
		entity.setTimezone(request.timezone() != null ? request.timezone() : "Europe/Rome");

		if (request.username() != null && !request.username().isBlank()) {
			entity.setUsername(request.username());
		}
		// Password vuota/null in update = invariata: così l'admin non deve reinserirla a ogni modifica
		// (e la API non la restituisce mai, quindi il client non ce l'ha per rimandarla).
		if (request.password() != null && !request.password().isBlank()) {
			entity.setPasswordEnc(credentialCipher.encrypt(request.password()));
		}

		if (request.enabled() != null) {
			entity.setEnabled(request.enabled());
		}

		entity.setParamsJson(request.paramsJson());
		entity.setBodyJson(request.bodyJson());
		entity.setStartAt(parseStartAt(request.startAt()));
		entity.setJobSuccessivo(normalizzaJobSuccessivo(request.jobSuccessivo()));

		entity.setNextRunAt(calculateNextRun(entity));

		return toResponse(subscriptionRepository.save(entity));
	}

	@Transactional
	public void enable(Long id) {
		BatchSubscription entity = subscriptionRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));

		entity.setEnabled(true);
		entity.setDataCessazione(null);
		// SI RICALCOLA SEMPRE, non solo quando e' nulla. Riattivando una schedulazione ferma da
		// settimane, la data vecchia restava nel passato: lo scheduler non guarda il cron, guarda solo
		// next_run_at, quindi si ritrovava una sottoscrizione "da eseguire da tre settimane" che
		// partiva al primo giro come recupero inatteso — e nel frattempo la pagina mostrava una
		// "prossima esecuzione" gia' passata, che non voleva dire niente.
		// nextRun() non produce mai una data passata: con startAt scaduta o assente parte da adesso.
		// Con cron vuoto torna null, ed e' giusto: quella sottoscrizione e' manuale.
		entity.setNextRunAt(calculateNextRun(entity));

		subscriptionRepository.save(entity);
	}

	@Transactional
	public void disable(Long id) {
		BatchSubscription entity = subscriptionRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));

		entity.setEnabled(false);
		entity.setDataCessazione(LocalDateTime.now());
		subscriptionRepository.save(entity);
	}

	// Eliminazione DEFINITIVA dal DB (non un soft-disable: per quello c'è disable()). Prima si cancella
	// lo storico esecuzioni, altrimenti la foreign key batch_execution -> batch_subscription impedirebbe
	// la rimozione.
	@Transactional
	public void delete(Long id) {
		if (!subscriptionRepository.existsById(id)) {
			throw new RuntimeException("Sottoscrizione batch non trovata: " + id);
		}
		executionRepository.deleteByBatchSubscriptionId(id);
		subscriptionRepository.deleteById(id);
	}

	// Storico esecuzioni della sottoscrizione: ultime 50, ordine decrescente per data di inizio.
	/**
	 * Storico esecuzioni di una schedulazione, paginato (pagina 1-based, come nel resto dell'app).
	 * <p>
	 * Prima erano le ultime 50 e basta: su un lavoro giornaliero sono meno di due mesi, e cio' che stava
	 * piu' indietro non era raggiungibile da nessuna parte nell'interfaccia.
	 */
	public it.be.batch.dto.Dtos.PaginaResponse<BatchExecutionResponse> findExecutions(Long subscriptionId, int page,
			int size) {
		int p = Math.max(0, page - 1);
		int s = Math.min(Math.max(1, size), 200);
		org.springframework.data.domain.Page<it.be.batch.entity.BatchExecution> pagina = executionRepository
				.findByBatchSubscriptionIdOrderByStartedAtDesc(subscriptionId,
						org.springframework.data.domain.PageRequest.of(p, s));
		return new it.be.batch.dto.Dtos.PaginaResponse<>(pagina.getContent().stream().map(this::toExecutionResponse)
				.toList(), pagina.getTotalElements(), page, s);
	}

	private BatchExecutionResponse toExecutionResponse(BatchExecution e) {
		Long durationMs = (e.getStartedAt() != null && e.getEndedAt() != null)
				? Duration.between(e.getStartedAt(), e.getEndedAt()).toMillis()
				: null;
		return new BatchExecutionResponse(e.getId(), e.getStatus(), e.getStartedAt(), e.getEndedAt(), durationMs,
				e.getResponseCode(), e.getErrorMessage(), e.getResponseBody(), e.getLog());
	}

	private LocalDateTime calculateNextRun(BatchSubscription subscription) {
		return CronScheduleUtil.nextRun(subscription.getCronExpression(), subscription.getTimezone(),
				subscription.getStartAt());
	}

	// Cron vuoto/blank -> null: sottoscrizione "manuale" (nessuna schedulazione automatica).
	private String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s.trim();
	}

	// startAt dal client come ISO locale "yyyy-MM-ddTHH:mm" (input datetime-local, secondi opzionali).
	// null/vuoto = nessuna decorrenza. Formato non valido -> errore esplicito.
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

	// idIntermediario -> nominativo (null se assente/non trovato). N.B.: lookup per riga; il numero di
	// schedulazioni è contenuto, quindi va bene senza ottimizzazioni.
	private String nomeIntermediario(Long idIntermediario) {
		if (idIntermediario == null) {
			return null;
		}
		return intermediarioRefRepository.findById(idIntermediario).map(IntermediarioRef::getNominativo).orElse(null);
	}

	// NB: username incluso, password MAI (non c'è nel response record).
	/**
	 * Codice del lavoro successivo: vuoto equivale a "nessun seguito". Senza questa normalizzazione una
	 * stringa vuota arrivata dal form verrebbe salvata come tale e la catena cercherebbe ogni volta una
	 * definition con codice "", loggando un avviso a ogni esecuzione.
	 */
	private static String normalizzaJobSuccessivo(String valore) {
		return (valore == null || valore.isBlank()) ? null : valore.trim();
	}

	private BatchSubscriptionResponse toResponse(BatchSubscription entity) {
		return toResponse(entity, esecuzioniInCorso());
	}

	private BatchSubscriptionResponse toResponse(BatchSubscription entity, Map<Long, LocalDateTime> inCorso) {
		LocalDateTime da = inCorso.get(entity.getId());
		return new BatchSubscriptionResponse(entity.getId(), entity.getIdIntermediario(), entity.getBatchDefinition().getId(),
				entity.getBatchDefinition().getCode(), entity.getCronExpression(), entity.getUsername(),
				entity.getTimezone(), entity.isEnabled(), entity.getLastRunAt(), entity.getNextRunAt(),
				entity.getParamsJson(), entity.getBodyJson(), entity.getIdUtenteAdmin(), entity.getStartAt(),
				nomeIntermediario(entity.getIdIntermediario()), da != null, da, entity.getJobSuccessivo());
	}

	/**
	 * Sottoscrizioni con un'esecuzione ancora IN CORSO (batch_execution PENDING e non conclusa),
	 * con l'istante di inizio. Una sola query per l'intera lista: nessun N+1.
	 */
	private Map<Long, LocalDateTime> esecuzioniInCorso() {
		Map<Long, LocalDateTime> m = new HashMap<>();
		for (BatchExecution e : executionRepository.findByStatusAndEndedAtIsNull(AppConstants.STATUS_PENDING)) {
			if (e.getBatchSubscription() != null) {
				m.merge(e.getBatchSubscription().getId(), e.getStartedAt(),
						(a, b) -> (a == null || (b != null && b.isBefore(a))) ? b : a);
			}
		}
		return m;
	}

	/**
	 * Interrompe l'elaborazione in corso: chiude le esecuzioni PENDING marcandole INTERROTTA e
	 * riprogramma la sottoscrizione.
	 * <p>
	 * ATTENZIONE — limite reale: il batch consiste in una chiamata HTTP a un altro servizio. Qui si
	 * interrompe l'ATTESA di be-batch (e si sblocca la schedulazione), ma il lavoro gia' avviato sul
	 * servizio a valle prosegue per conto suo: non e' arrestabile da qui.
	 */
	@Transactional
	public Map<String, Object> interrompi(Long subscriptionId) {
		BatchSubscription entity = subscriptionRepository.findById(subscriptionId)
				.orElseThrow(() -> new RuntimeException("Sottoscrizione batch non trovata"));

		boolean interrottoThread = batchScheduler.interrompiEsecuzione(subscriptionId);

		// ANELLO DECISIVO: ferma il lavoro sul SERVIZIO REALE. Interrompere il thread di be-batch chiude
		// solo l'attesa: l'elaborazione a valle proseguirebbe. Se la definizione ha uno stop_url
		// (convenzione /batch-control/{job}/stop) lo si invoca: il servizio alza il flag e si ferma al
		// primo controllo utile (interruzione cooperativa).
		String esitoStop = chiamaStopServizio(entity);

		List<BatchExecution> inCorso = executionRepository
				.findByBatchSubscriptionIdAndStatusAndEndedAtIsNull(subscriptionId, AppConstants.STATUS_PENDING);
		LocalDateTime now = LocalDateTime.now();
		for (BatchExecution e : inCorso) {
			e.setStatus(STATUS_INTERROTTA);
			e.setEndedAt(now);
			e.setErrorMessage("Interrotta manualmente dall'amministratore");
			executionRepository.save(e);
		}
		if (!inCorso.isEmpty()) {
			entity.setLastRunAt(now);
			entity.setNextRunAt(calculateNextRun(entity));
			subscriptionRepository.save(entity);
		}

		Map<String, Object> out = new HashMap<>();
		out.put("success", true);
		out.put("esecuzioniChiuse", inCorso.size());
		out.put("threadInterrotto", interrottoThread);
		out.put("stopServizio", esitoStop);
		out.put("message", inCorso.isEmpty() ? "Nessuna elaborazione in corso da interrompere"
				: "Elaborazione interrotta (" + esitoStop + ")");
		return out;
	}

	/**
	 * Invoca lo stop-url della definizione, se presente: e' il solo modo per fermare il lavoro sul
	 * servizio a valle (che lo implementa come cancellazione cooperativa). Non solleva: l'esito e'
	 * descrittivo e finisce nella risposta all'operatore.
	 */
	private String chiamaStopServizio(BatchSubscription entity) {
		BatchDefinition def = entity.getBatchDefinition();
		String url = (def == null) ? null : def.getStopUrl();
		if (url == null || url.isBlank()) {
			logger.warn("Interruzione subscription {}: stop_url assente, il servizio a valle prosegue", entity.getId());
			return "il servizio chiamato non espone un'interruzione: il lavoro gia' avviato prosegue";
		}
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			// Token di servizio: gli endpoint /batch-control sono chiamate interne fra microservizi.
			if (internalToken != null && !internalToken.isBlank()) {
				headers.set("X-INTERNAL-TOKEN", internalToken);
			}
			restTemplate.exchange(url, org.springframework.http.HttpMethod.POST, new HttpEntity<>(headers),
					String.class);
			logger.warn("Interruzione subscription {}: stop inviato al servizio ({})", entity.getId(), url);
			return "stop inviato al servizio chiamato";
		} catch (Exception e) {
			logger.error("Interruzione subscription {}: stop al servizio fallito ({}): {}", entity.getId(), url,
					e.getMessage());
			return "stop al servizio chiamato non riuscito: " + e.getMessage();
		}
	}
}