package it.be.batch.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import it.ai.client.constants.AppConstants;
import it.be.batch.dto.Dtos.SftpTestResponse;
import it.be.batch.entity.SftpExecution;
import it.be.batch.entity.SftpSchedule;
import it.be.batch.repo.SftpExecutionRepository;
import it.be.batch.repo.SftpScheduleRepository;
import it.common.base.batch.BatchJobControl;
import it.common.base.batch.BatchJobRegistry;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

/**
 * Esecuzione di un trasferimento SFTP schedulato, nelle due direzioni.
 * <ul>
 *   <li>{@code SFTP_TO_STORAGE}: elenca la cartella remota, scarica i file che rispettano il pattern e
 *       li scrive su be-storage in {@code <intermediario>/<type>/<folder>};</li>
 *   <li>{@code STORAGE_TO_SFTP}: elenca la cartella di storage e carica i file sul server SFTP.</li>
 * </ul>
 * <p>
 * A differenza dei batch (che chiamano un servizio remoto e ne attendono l'esito), qui l'elaborazione
 * gira DENTRO be-batch: la telecronaca la scrive direttamente questo servizio su
 * {@code sftp_execution.log}, file per file, cosi' l'operatore vede l'avanzamento in tempo reale.
 * <p>
 * L'interruzione e' COOPERATIVA come per gli altri job schedulabili: il controllo passa da
 * {@link BatchJobControl} (registro condiviso di commonBase, job {@code sftp-schedule-<id>}) e viene
 * verificato prima di ogni file, quindi lo stop chiude il file in corso e si ferma senza lasciare
 * trasferimenti a meta'.
 */
@Service
public class SftpTransferService {

	private static final Logger logger = LoggerFactory.getLogger(SftpTransferService.class);

	public static final String STATUS_INTERROTTA = "INTERROTTA";

	private static final DateTimeFormatter FMT_LOG = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter FMT_PREFISSO = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	/** Nome del job nel registro condiviso: uno per schedulazione, cosi' se ne ferma una sola. */
	public static String jobName(Long scheduleId) {
		return "sftp-schedule-" + scheduleId;
	}

	private final SftpScheduleRepository scheduleRepository;
	private final SftpExecutionRepository executionRepository;
	private final CredentialCipher credentialCipher;
	private final BatchJobRegistry batchJobRegistry;
	private final RestTemplate restTemplate;

	@Value("${api.storage.service.url}")
	private String storageUrl;

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

	// Timeout della connessione SSH e di lettura del canale, in millisecondi.
	@Value("${sftp.connect.timeout.ms:20000}")
	private int connectTimeoutMs;

	@Value("${sftp.read.timeout.ms:120000}")
	private int readTimeoutMs;

	/**
	 * File known_hosts con cui verificare l'identita' del server. Se vuoto la verifica e' DISATTIVATA
	 * (si accetta qualunque chiave): comodo in test, ma espone a man-in-the-middle. In produzione
	 * valorizzare SFTP_KNOWN_HOSTS con un file montato nel pod.
	 */
	@Value("${sftp.known-hosts:}")
	private String knownHostsFile;

	/**
	 * Tetto alla dimensione della telecronaca. Oltre, si scartano le righe piu' vecchie: il dettaglio
	 * per-file (2-3 righe a file) su cartelle da migliaia di file renderebbe altrimenti ogni UPDATE
	 * piu' pesante della precedente.
	 */
	@Value("${sftp.log.max-caratteri:200000}")
	private int logMaxCaratteri;

	/**
	 * Estrazione automatica degli archivi ZIP prelevati da SFTP: su storage finiscono i file contenuti,
	 * non l'archivio. Si puo' spegnere per trasferire gli zip cosi' come sono.
	 */
	@Value("${sftp.zip.estrai:true}")
	private boolean estraiZip;

	/**
	 * Tetto per singolo file estratto: difesa contro gli archivi "zip bomb". Ora il limite riguarda lo
	 * SPAZIO SU DISCO (l'estrazione e' in streaming, non in memoria), quindi puo' essere generoso.
	 * 0 = nessun limite.
	 */
	@Value("${sftp.zip.max-file-mb:4096}")
	private int zipMaxFileMb;

	/**
	 * Cartella di lavoro per i file in transito. Con archivi da GB serve spazio: puntarla a un volume
	 * capiente invece che alla temp di sistema. Vuoto = temp di sistema.
	 */
	@Value("${sftp.temp.dir:}")
	private String tempDirConfigurata;

	/**
	 * Tetto di durata di una singola richiesta HTTP verso be-storage. Un file da qualche GB su linea
	 * lenta impiega molto: il timeout dei RestTemplate condivisi (minuti) lo taglierebbe a meta'.
	 */
	@Value("${sftp.http.timeout.min:120}")
	private int httpTimeoutMin;

	/**
	 * Client HTTP dedicato ai TRASFERIMENTI DI FILE. Non si usa RestTemplate perche' con
	 * {@code SimpleClientHttpRequestFactory} il corpo della richiesta viene bufferizzato in memoria:
	 * su file da GB significa OutOfMemory. {@code HttpClient} del JDK con
	 * {@code BodyPublishers.ofFile} / {@code BodyHandlers.ofFile} scrive e legge direttamente da disco.
	 * Thread-safe, creato una volta sola.
	 */
	private volatile java.net.http.HttpClient httpFile;

	private java.net.http.HttpClient httpFile() {
		if (httpFile == null) {
			synchronized (this) {
				if (httpFile == null) {
					httpFile = java.net.http.HttpClient.newBuilder()
							.connectTimeout(java.time.Duration.ofMillis(connectTimeoutMs))
							.followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
							.build();
				}
			}
		}
		return httpFile;
	}

	public SftpTransferService(SftpScheduleRepository scheduleRepository, SftpExecutionRepository executionRepository,
			CredentialCipher credentialCipher, BatchJobRegistry batchJobRegistry,
			@Qualifier("RestTimeout") RestTemplate restTemplate) {
		super();
		this.scheduleRepository = scheduleRepository;
		this.executionRepository = executionRepository;
		this.credentialCipher = credentialCipher;
		this.batchJobRegistry = batchJobRegistry;
		this.restTemplate = restTemplate;
	}

	// ------------------------------------------------------------------------------------------------
	// Esecuzione
	// ------------------------------------------------------------------------------------------------

	/**
	 * Esegue il trasferimento di una schedulazione. SINCRONO: va invocato da un thread dedicato
	 * (lo scheduler o il pool delle esecuzioni manuali), non dal thread della richiesta HTTP.
	 *
	 * @return l'id dell'esecuzione creata, oppure {@code null} se il job era gia' in corso.
	 */
	public Long esegui(Long scheduleId) {
		SftpSchedule schedule = scheduleRepository.findById(scheduleId).orElse(null);
		if (schedule == null) {
			logger.warn("Trasferimento SFTP: schedulazione {} non trovata", scheduleId);
			return null;
		}

		BatchJobControl ctl = batchJobRegistry.get(jobName(scheduleId));
		if (!ctl.begin()) {
			// Gia' in esecuzione: non si sovrappongono due trasferimenti sulla stessa cartella.
			logger.warn("Trasferimento SFTP {}: gia' in esecuzione, avvio ignorato", scheduleId);
			return null;
		}

		SftpExecution execution = new SftpExecution();
		execution.setSftpSchedule(schedule);
		execution.setStatus(AppConstants.STATUS_PENDING);
		execution.setStartedAt(LocalDateTime.now());
		execution.setFileTrasferiti(0);
		execution.setByteTrasferiti(0L);
		execution.setLog("");
		execution = executionRepository.save(execution);
		Long idExecution = execution.getId();

		long inizioCorsa = System.currentTimeMillis();
		log(idExecution, "Avvio trasferimento '" + schedule.getNome() + "' — direzione "
				+ (SftpSchedule.DIR_STORAGE_TO_SFTP.equals(schedule.getDirezione()) ? "STORAGE -> SFTP"
						: "SFTP -> STORAGE"));
		// La politica configurata va detta in chiaro all'inizio: e' la prima cosa da controllare quando
		// "il file non e' stato cancellato" (spesso la schedulazione e' rimasta su LASCIA).
		log(idExecution, "Politica sul file di origine: " + descrivePolitica(schedule));

		Esito esito = new Esito();
		try {
			if (SftpSchedule.DIR_STORAGE_TO_SFTP.equals(schedule.getDirezione())) {
				storageVersoSftp(schedule, idExecution, ctl, esito);
			} else {
				sftpVersoStorage(schedule, idExecution, ctl, esito);
			}

			String stato = ctl.isStopRequested() ? STATUS_INTERROTTA
					: (esito.errori > 0 ? AppConstants.STATUS_FAILED : AppConstants.STATUS_COMPLETED);
			String messaggio = null;
			if (esito.errori > 0) {
				messaggio = esito.errori + " file non trasferiti: " + esito.primoErrore;
			} else if (esito.avvisi > 0) {
				// Trasferimento riuscito ma politica post-trasferimento fallita: l'esecuzione resta
				// COMPLETED (i file sono arrivati), pero' il motivo va scritto, altrimenti la colonna
				// "Motivo" resta vuota e sembra tutto a posto mentre i file di origine sono ancora li'.
				messaggio = esito.avvisi + " file trasferiti ma non archiviati: " + esito.primoAvviso;
			}
			if (ctl.isStopRequested()) {
				messaggio = "Trasferimento interrotto dall'amministratore";
			}
			chiudi(idExecution, stato, messaggio, esito);
			log(idExecution, "Fine: " + esito.file + " file trasferiti, " + formatByte(esito.byteTotali) + ", "
					+ esito.errori + " errori, " + esito.avvisi + " avvisi, durata "
					+ formatDurata(System.currentTimeMillis() - inizioCorsa) + " — esito " + stato);
		} catch (Exception e) {
			logger.error("Trasferimento SFTP {} fallito: {}", scheduleId, e.getMessage(), e);
			log(idExecution, "ERRORE: " + e.getClass().getSimpleName() + " — " + e.getMessage());
			// Se nel frattempo e' stato chiesto lo stop, l'errore e' una conseguenza dell'interruzione:
			// registrarlo come FAILED farebbe sembrare rotto un job che invece e' stato fermato a mano.
			if (ctl.isStopRequested()) {
				chiudi(idExecution, STATUS_INTERROTTA, "Trasferimento interrotto dall'amministratore", esito);
			} else {
				chiudi(idExecution, AppConstants.STATUS_FAILED, messaggioErrore(e), esito);
			}
		} finally {
			ctl.end();
			aggiornaProssimaEsecuzione(scheduleId);
		}
		return idExecution;
	}

	/** Contatori dell'esecuzione (mutabili, passati alle due direzioni). */
	private static class Esito {
		int file;
		int errori;
		long byteTotali;
		String primoErrore;
		/**
		 * Avvisi: il file E' stato trasferito ma la politica post-trasferimento non ha funzionato
		 * (cancellazione rifiutata, archiviazione fallita). Non e' un fallimento del trasferimento, ma
		 * deve arrivare all'operatore: senza, il file resta sull'origine e al giro dopo viene ripreso.
		 */
		int avvisi;
		String primoAvviso;

		void errore(String messaggio) {
			errori++;
			if (primoErrore == null) {
				primoErrore = messaggio;
			}
		}

		void avviso(String messaggio) {
			avvisi++;
			if (primoAvviso == null) {
				primoAvviso = messaggio;
			}
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Direzione 1: SFTP -> storage
	// ------------------------------------------------------------------------------------------------

	private void sftpVersoStorage(SftpSchedule s, Long idExecution, BatchJobControl ctl, Esito esito)
			throws IOException {
		String filtroRisolto = patternDelGiorno(s);
		Pattern filtro = compilaPattern(filtroRisolto);
		logFiltro(idExecution, s, filtroRisolto);
		Path tempDir = creaCartellaTemporanea("sftp-down-");

		try (SSHClient ssh = connetti(s.getSftpHost(), s.getSftpPort(), s.getSftpUsername(),
				credentialCipher.decrypt(s.getSftpPasswordEnc()));
				SFTPClient sftp = ssh.newSFTPClient()) {

			log(idExecution, "Connesso a " + s.getSftpHost() + ":" + s.getSftpPort());
			log(idExecution, "Cartella di ORIGINE      : " + cartellaSftp(s));
			log(idExecution, "Cartella di DESTINAZIONE : " + cartellaStorage(s));

			List<RemoteResourceInfo> remoti = sftp.ls(s.getSftpPath());
			List<RemoteResourceInfo> daPrendere = new ArrayList<>();
			for (RemoteResourceInfo r : remoti) {
				if (r.isRegularFile() && (filtro == null || filtro.matcher(r.getName()).matches())) {
					daPrendere.add(r);
				}
			}
			log(idExecution, "File da trasferire: " + daPrendere.size() + " (su " + remoti.size()
					+ " elementi in cartella)");

			int progressivo = 0;
			for (RemoteResourceInfo r : daPrendere) {
				if (ctl.isStopRequested()) {
					log(idExecution, "STOP richiesto: trasferimento interrotto dopo " + esito.file + " file");
					return;
				}
				String nome = r.getName();
				String origine = percorsoRemoto(s.getSftpPath(), nome);
				progressivo++;
				Path locale = tempDir.resolve(nomeTemporaneo(nome));
				long inizio = System.currentTimeMillis();
				// Riga di INIZIO: con l'orario in testa dice a che ora e' partito quel singolo file. Su
				// file grandi e' l'unico modo di distinguere "fermo" da "sta ancora copiando".
				log(idExecution, "[" + progressivo + "/" + daPrendere.size() + "] INIZIO  " + nome
						+ "   da " + cartellaSftp(s) + "/" + nome);
				try {
					// get(String, String): API stabile di sshj, scarica sul filesystem locale.
					sftp.get(origine, locale.toString());

					if (estraiZip && isZip(nome)) {
						// Lo ZIP e' solo un contenitore di trasporto: su storage vanno i file che contiene,
						// non l'archivio. Lo zip scaricato resta nella cartella temporanea e viene buttato
						// nel finally; sul server remoto vale la politica post-trasferimento configurata.
						estraiZipSuStorage(s, locale, nome, idExecution, esito, progressivo, daPrendere.size(),
								inizio);
					} else {
						// Il file resta su disco: si carica in streaming, senza leggerlo in memoria.
						// Con un file da qualche GB un readAllBytes farebbe cadere il servizio (e byte[]
						// non puo' comunque superare i 2 GB).
						long dimensione = Files.size(locale);
						if (dimensione == 0) {
							// Non e' un errore (capita con i file segnaposto e con gli export a zero record),
							// ma va detto: altrimenti a valle si cerca un contenuto che non c'e' mai stato.
							log(idExecution, "         ATTENZIONE: il file di origine e' VUOTO (0 byte)");
						}

						caricaSuStorage(s, nome, locale);

						esito.file++;
						esito.byteTotali += dimensione;
						log(idExecution, "[" + progressivo + "/" + daPrendere.size() + "] OK      " + nome + "   "
								+ formatByte(dimensione) + " in "
								+ formatDurata(System.currentTimeMillis() - inizio)
								+ "   a " + cartellaStorage(s) + "/" + nome);
					}

					archiviaRemoto(sftp, s, nome, idExecution, esito);
				} catch (Exception e) {
					esito.errore(nome + ": " + messaggioErrore(e));
					log(idExecution, "[" + progressivo + "/" + daPrendere.size() + "] KO      " + nome + "   "
							+ origine + " — " + messaggioErrore(e));
				} finally {
					Files.deleteIfExists(locale);
				}
			}
		} finally {
			pulisci(tempDir);
		}
	}

	/**
	 * Cartella di lavoro per i file in transito. Con archivi da GB la temp di sistema puo' non avere
	 * spazio a sufficienza (nei container e' spesso piccola): {@code sftp.temp.dir} permette di
	 * puntarla a un volume capiente. Lo spazio disponibile finisce nei log, cosi' un "No space left on
	 * device" non arriva senza preavviso.
	 */
	private Path creaCartellaTemporanea(String prefisso) throws IOException {
		Path base = null;
		if (tempDirConfigurata != null && !tempDirConfigurata.isBlank()) {
			base = Path.of(tempDirConfigurata.trim());
			Files.createDirectories(base);
		}
		Path dir = (base == null) ? Files.createTempDirectory(prefisso) : Files.createTempDirectory(base, prefisso);
		try {
			long liberi = Files.getFileStore(dir).getUsableSpace();
			logger.info("Cartella di transito {} — spazio disponibile {}", dir, formatByte(liberi));
		} catch (Exception e) {
			logger.debug("Spazio disponibile non determinabile per {}: {}", dir, e.getMessage());
		}
		return dir;
	}

	/** Vero se il nome file e' un archivio ZIP (il solo formato gestito). */
	private static boolean isZip(String nome) {
		return nome != null && nome.toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
	}

	/**
	 * Estrae un archivio ZIP scaricato e scrive su storage <b>i file contenuti</b>, uno per uno.
	 * L'archivio NON viene copiato su storage: e' un contenitore di trasporto e finisce buttato con la
	 * cartella temporanea.
	 * <p>
	 * Difese necessarie trattando archivi di provenienza esterna:
	 * <ul>
	 *   <li><b>zip-slip</b>: dell'entry si tiene solo il nome del file, mai il percorso interno. Un
	 *       archivio confezionato con voci tipo {@code ../../etc/x} non puo' far scrivere fuori dalla
	 *       cartella di destinazione;</li>
	 *   <li><b>zip bomb</b>: si rifiuta ogni voce che superi {@code sftp.zip.max-file-mb} da
	 *       decompressa, invece di riempire la memoria;</li>
	 *   <li><b>stop</b>: il flag di interruzione si controlla anche fra una voce e l'altra, altrimenti
	 *       un archivio con migliaia di file ignorerebbe la richiesta di fermarsi.</li>
	 * </ul>
	 * Le cartelle interne all'archivio vengono ignorate: i file finiscono tutti nella cartella di
	 * storage configurata, che e' dove il servizio a valle li cerca.
	 */
	private void estraiZipSuStorage(SftpSchedule s, Path zipLocale, String nomeZip, Long idExecution, Esito esito,
			int progressivo, int totale, long inizio) throws IOException {

		String prefissoRiga = "[" + progressivo + "/" + totale + "] ";
		BatchJobControl ctl = batchJobRegistry.get(jobName(s.getId()));
		long limiteByte = (long) zipMaxFileMb * 1024L * 1024L;
		int estratti = 0;
		int saltati = 0;
		long byteEstratti = 0;

		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipLocale.toFile())) {
			java.util.Enumeration<? extends java.util.zip.ZipEntry> voci = zip.entries();
			while (voci.hasMoreElements()) {
				java.util.zip.ZipEntry voce = voci.nextElement();
				if (ctl.isStopRequested()) {
					log(idExecution, "         STOP richiesto durante l'estrazione: " + estratti + " file estratti");
					break;
				}
				if (voce.isDirectory()) {
					continue;
				}
				String nomeInterno = soloNomeFile(voce.getName());
				if (nomeInterno.isEmpty()) {
					saltati++;
					log(idExecution, "         SALTATA voce con nome non valido: " + voce.getName());
					continue;
				}
				// getSize() = -1 quando l'archivio non dichiara la dimensione: in quel caso il controllo
				// si fa comunque sui byte effettivamente letti.
				if (voce.getSize() > limiteByte) {
					saltati++;
					esito.avviso(nomeZip + "/" + nomeInterno + ": voce oltre " + zipMaxFileMb + " MB, saltata");
					log(idExecution, "         SALTATA " + nomeInterno + ": dichiarata "
							+ formatByte(voce.getSize()) + ", oltre il limite di " + zipMaxFileMb + " MB");
					continue;
				}

				// La voce viene scritta su disco e poi caricata in streaming: mai in memoria, cosi'
				// l'estrazione regge archivi da GB con dentro file altrettanto grandi.
				Path vocePath = zipLocale.getParent().resolve(nomeTemporaneo("zip_" + estratti + "_" + nomeInterno));
				long dimensione;
				try {
					try (java.io.InputStream in = zip.getInputStream(voce)) {
						dimensione = copiaConTetto(in, vocePath, limiteByte);
					}
					if (dimensione < 0) {
						saltati++;
						esito.avviso(nomeZip + "/" + nomeInterno + ": oltre " + zipMaxFileMb
								+ " MB una volta decompressa");
						log(idExecution, "         SALTATA " + nomeInterno + ": supera " + zipMaxFileMb
								+ " MB una volta decompressa");
						continue;
					}

					caricaSuStorage(s, nomeInterno, vocePath);
				} finally {
					// La copia estratta serve solo al caricamento: si libera subito lo spazio, altrimenti
					// un archivio da GB ne occuperebbe il doppio fino a fine elaborazione.
					Files.deleteIfExists(vocePath);
				}
				estratti++;
				byteEstratti += dimensione;
				esito.file++;
				esito.byteTotali += dimensione;
				log(idExecution, "         estratto " + nomeInterno + "   " + formatByte(dimensione)
						+ "   a " + cartellaStorage(s) + "/" + nomeInterno);
			}
		}

		if (estratti == 0 && saltati == 0) {
			esito.avviso(nomeZip + ": archivio senza file utili");
			log(idExecution, prefissoRiga + "ATTENZIONE " + nomeZip + "   archivio VUOTO: nessun file estratto");
		} else {
			log(idExecution, prefissoRiga + "OK      " + nomeZip + "   " + estratti + " file estratti ("
					+ formatByte(byteEstratti) + ")" + (saltati > 0 ? ", " + saltati + " saltati" : "")
					+ " in " + formatDurata(System.currentTimeMillis() - inizio)
					+ "   a " + cartellaStorage(s));
		}
		log(idExecution, "         l'archivio " + nomeZip + " NON viene copiato su storage (scartato)");
	}

	/**
	 * Copia lo stream su file fermandosi se supera il tetto. Ritorna i byte copiati, oppure {@code -1}
	 * se il limite e' stato superato (in quel caso il file parziale viene cancellato).
	 * <p>
	 * Si conta sui byte EFFETTIVAMENTE letti e non sulla dimensione dichiarata nell'archivio: un file
	 * confezionato ad arte puo' dichiarare 1 KB e decomprimersi in gigabyte. La copia va su disco e
	 * mai in memoria, altrimenti una singola voce grande basterebbe a far cadere il servizio.
	 *
	 * @param limiteByte tetto in byte; {@code <= 0} disattiva il controllo
	 */
	private static long copiaConTetto(java.io.InputStream in, Path destinazione, long limiteByte) throws IOException {
		byte[] buf = new byte[64 * 1024];
		long totale = 0;
		try (java.io.OutputStream out = Files.newOutputStream(destinazione)) {
			int letti;
			while ((letti = in.read(buf)) > 0) {
				totale += letti;
				if (limiteByte > 0 && totale > limiteByte) {
					out.close();
					Files.deleteIfExists(destinazione);
					return -1;
				}
				out.write(buf, 0, letti);
			}
		}
		return totale;
	}

	/**
	 * Ultimo segmento del percorso interno all'archivio, senza cartelle: e' la difesa contro lo
	 * zip-slip. Si accettano sia {@code /} sia {@code \} come separatori, perche' gli zip creati su
	 * Windows usano il secondo.
	 */
	private static String soloNomeFile(String percorsoInterno) {
		if (percorsoInterno == null) {
			return "";
		}
		String n = percorsoInterno.replace('\\', '/');
		int taglio = n.lastIndexOf('/');
		if (taglio >= 0) {
			n = n.substring(taglio + 1);
		}
		n = n.trim();
		// Un nome che sia solo "." o ".." non e' un file: si scarta.
		return (n.equals(".") || n.equals("..")) ? "" : n;
	}

	/** Politica post-trasferimento sul file REMOTO (origine della direzione SFTP -> storage). */
	private void archiviaRemoto(SFTPClient sftp, SftpSchedule s, String nome, Long idExecution, Esito esito) {
		String politica = s.getPostTransfer();
		try {
			if (SftpSchedule.POST_CANCELLA.equals(politica)) {
				String path = percorsoRemoto(s.getSftpPath(), nome);
				sftp.rm(path);
				// Controllo di avvenuta cancellazione: alcuni server accettano la rm e non fanno nulla
				// (permessi sulla cartella, file bloccato da chi lo sta ancora scrivendo). Senza questa
				// verifica il log direbbe "cancellata" mentre il file e' ancora li', e al giro dopo
				// verrebbe ritrasferito.
				if (esisteSuSftp(sftp, path)) {
					esito.avviso(nome + ": cancellazione non effettiva (il file e' ancora sul server)");
					log(idExecution, "         ATTENZIONE: il file risulta ANCORA PRESENTE dopo la cancellazione: "
							+ cartellaSftp(s) + "/" + nome + " — verificare i permessi di scrittura sulla cartella");
				} else {
					log(idExecution, "         origine cancellata: " + cartellaSftp(s) + "/" + nome);
				}
			} else if (SftpSchedule.POST_LASCIA.equals(politica)) {
				// Riga esplicita anche quando non si fa nulla: senza, "nessuna riga" era indistinguibile
				// da "la cancellazione e' fallita in silenzio".
				log(idExecution, "         origine lasciata sul server (politica LASCIA)");
			} else if (SftpSchedule.POST_SPOSTA.equals(politica)) {
				String cartella = percorsoRemoto(s.getSftpPath(), cartellaArchivio(s));
				sftp.mkdirs(cartella);
				// Prefisso data/ora: senza, il secondo invio dello stesso nome file troverebbe l'omonimo
				// gia' archiviato e la rename fallirebbe.
				String nuovoNome = prefissoOra() + "_" + nome;
				String destinazione = percorsoRemoto(cartella, nuovoNome);
				sftp.rename(percorsoRemoto(s.getSftpPath(), nome), destinazione);
				// Percorso di destinazione ricostruito da cartellaSftp (gia' normalizzata): concatenare la
				// radice con `destinazione` grezza darebbe "sftp://host:22upload/..." se sftp_path e'
				// scritto senza slash iniziale.
				log(idExecution, "         origine archiviata: " + cartellaSftp(s) + "/" + nome + "   ->   "
						+ cartellaSftp(s) + "/" + cartellaArchivio(s) + "/" + nuovoNome);
			}
		} catch (Exception e) {
			// Il file E' stato trasferito: l'archiviazione mancata e' un avviso, non un fallimento — ma
			// deve arrivare all'operatore anche senza aprire il log (finisce nel "Motivo" dell'esecuzione).
			esito.avviso(nome + ": " + politica + " non riuscita — " + messaggioErrore(e));
			log(idExecution, "         ATTENZIONE: politica " + politica + " non riuscita su "
					+ cartellaSftp(s) + "/" + nome + " — " + messaggioErrore(e));
		}
	}

	/** True se il percorso remoto esiste ancora. Usato per confermare cancellazioni e spostamenti. */
	private boolean esisteSuSftp(SFTPClient sftp, String path) {
		try {
			sftp.stat(path);
			return true;
		} catch (Exception e) {
			// L'eccezione tipica e' "No such file": il file non c'e' piu', che e' l'esito atteso.
			return false;
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Direzione 2: storage -> SFTP
	// ------------------------------------------------------------------------------------------------

	private void storageVersoSftp(SftpSchedule s, Long idExecution, BatchJobControl ctl, Esito esito)
			throws IOException {
		String filtroRisolto = patternDelGiorno(s);
		Pattern filtro = compilaPattern(filtroRisolto);
		logFiltro(idExecution, s, filtroRisolto);
		Path tempDir = creaCartellaTemporanea("sftp-up-");

		List<String> daInviare = new ArrayList<>();
		int totaleInCartella = 0;
		for (String nome : elencaStorage(s)) {
			totaleInCartella++;
			if (filtro == null || filtro.matcher(nome).matches()) {
				daInviare.add(nome);
			}
		}
		log(idExecution, "Cartella di ORIGINE      : " + cartellaStorage(s));
		log(idExecution, "File da trasferire: " + daInviare.size() + " (su " + totaleInCartella
				+ " elementi in cartella)");

		try (SSHClient ssh = connetti(s.getSftpHost(), s.getSftpPort(), s.getSftpUsername(),
				credentialCipher.decrypt(s.getSftpPasswordEnc()));
				SFTPClient sftp = ssh.newSFTPClient()) {

			log(idExecution, "Connesso a " + s.getSftpHost() + ":" + s.getSftpPort());
			log(idExecution, "Cartella di DESTINAZIONE : " + cartellaSftp(s));
			sftp.mkdirs(s.getSftpPath());

			int progressivo = 0;
			for (String nome : daInviare) {
				if (ctl.isStopRequested()) {
					log(idExecution, "STOP richiesto: trasferimento interrotto dopo " + esito.file + " file");
					return;
				}
				progressivo++;
				Path locale = tempDir.resolve(nomeTemporaneo(nome));
				long inizio = System.currentTimeMillis();
				log(idExecution, "[" + progressivo + "/" + daInviare.size() + "] INIZIO  " + nome
						+ "   da " + cartellaStorage(s) + "/" + nome);
				try {
					long dimensione = scaricaDaStorage(s, nome, locale);
					sftp.put(locale.toString(), percorsoRemoto(s.getSftpPath(), nome));

					esito.file++;
					esito.byteTotali += dimensione;
					log(idExecution, "[" + progressivo + "/" + daInviare.size() + "] OK      " + nome + "   "
							+ formatByte(dimensione) + " in " + formatDurata(System.currentTimeMillis() - inizio)
							+ "   a " + cartellaSftp(s) + "/" + nome);

					archiviaStorage(s, nome, idExecution, esito);
				} catch (Exception e) {
					esito.errore(nome + ": " + messaggioErrore(e));
					log(idExecution, "[" + progressivo + "/" + daInviare.size() + "] KO      " + nome + "   "
							+ cartellaStorage(s) + "/" + nome + " — " + messaggioErrore(e));
				} finally {
					Files.deleteIfExists(locale);
				}
			}
		} finally {
			pulisci(tempDir);
		}
	}

	/** Politica post-trasferimento sul file di STORAGE (origine della direzione storage -> SFTP). */
	private void archiviaStorage(SftpSchedule s, String nome, Long idExecution, Esito esito) {
		String politica = s.getPostTransfer();
		try {
			if (SftpSchedule.POST_CANCELLA.equals(politica)) {
				String url = UriComponentsBuilder.fromUriString(storageUrl + "/wr-storage/delete")
						.queryParam("intermediario", s.getStorageIntermediario())
						.queryParam("type", s.getStorageType()).queryParam("folder", s.getStorageFolder())
						.queryParam("fileName", nome).toUriString();
				ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.POST,
						new HttpEntity<>(headerInterni()), Map.class);
				// be-storage risponde 200 anche quando non ha cancellato nulla (success=false o count=0):
				// senza guardare il corpo si direbbe "cancellata" per un file rimasto al suo posto.
				Map<String, Object> body = resp.getBody();
				Object count = (body == null) ? null : body.get("count");
				boolean fatto = body != null && Boolean.TRUE.equals(body.get("success"))
						&& count instanceof Number n && n.intValue() > 0;
				if (fatto) {
					log(idExecution, "         origine cancellata: " + cartellaStorage(s) + "/" + nome);
				} else {
					String dettaglio = (body == null) ? "nessuna risposta" : String.valueOf(body.get("message"));
					esito.avviso(nome + ": cancellazione dallo storage non effettuata (" + dettaglio + ")");
					log(idExecution, "         ATTENZIONE: cancellazione NON effettuata su " + cartellaStorage(s)
							+ "/" + nome + " — " + dettaglio);
				}
			} else if (SftpSchedule.POST_LASCIA.equals(politica)) {
				log(idExecution, "         origine lasciata nello storage (politica LASCIA)");
			} else if (SftpSchedule.POST_SPOSTA.equals(politica)) {
				String target = s.getStorageFolder() + "/" + cartellaArchivio(s);
				String nuovoNome = prefissoOra() + "_" + nome;
				String url = UriComponentsBuilder.fromUriString(storageUrl + "/wr-storage/move")
						.queryParam("intermediario", s.getStorageIntermediario())
						.queryParam("type", s.getStorageType()).queryParam("folder", s.getStorageFolder())
						.queryParam("fileName", nome).queryParam("targetFolder", target)
						.queryParam("targetFileName", nuovoNome).toUriString();
				restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(headerInterni()), Map.class);
				log(idExecution, "         origine archiviata: " + cartellaStorage(s) + "/" + nome + "   ->   storage:/"
						+ s.getStorageIntermediario() + "/" + s.getStorageType() + "/" + target + "/" + nuovoNome);
			}
		} catch (Exception e) {
			esito.avviso(nome + ": " + politica + " non riuscita — " + messaggioErrore(e));
			log(idExecution, "         ATTENZIONE: politica " + politica + " non riuscita su " + cartellaStorage(s)
					+ "/" + nome + " — " + messaggioErrore(e));
		}
	}

	// ------------------------------------------------------------------------------------------------
	// be-storage
	// ------------------------------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private List<String> elencaStorage(SftpSchedule s) {
		String url = UriComponentsBuilder.fromUriString(storageUrl + "/wr-storage/list")
				.queryParam("intermediario", s.getStorageIntermediario()).queryParam("type", s.getStorageType())
				.queryParam("folder", s.getStorageFolder()).toUriString();

		ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headerInterni()),
				Map.class);
		List<String> nomi = new ArrayList<>();
		Map<String, Object> body = resp.getBody();
		if (body != null && body.get("files") instanceof List<?> files) {
			for (Object o : files) {
				if (o instanceof Map<?, ?> m && m.get("fileName") != null) {
					nomi.add(String.valueOf(m.get("fileName")));
				}
			}
		}
		return nomi;
	}

	/**
	 * Scarica un file dallo storage SU DISCO, in streaming.
	 * <p>
	 * Non si usa {@code /wr-storage/read}: quello restituisce il contenuto dentro un JSON codificato
	 * base64, cioe' l'intero file in memoria piu' il 33% della codifica, su entrambi i lati. Con
	 * archivi da GB e' insostenibile.
	 *
	 * @return byte scaricati
	 */
	private long scaricaDaStorage(SftpSchedule s, String fileName, Path destinazione) throws IOException {
		String url = UriComponentsBuilder.fromUriString(storageUrl + "/wr-storage/download")
				.queryParam("intermediario", s.getStorageIntermediario()).queryParam("type", s.getStorageType())
				.queryParam("folder", s.getStorageFolder()).queryParam("fileName", fileName).toUriString();

		java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET()
				.timeout(java.time.Duration.ofMinutes(httpTimeoutMin));
		if (internalToken != null && !internalToken.isBlank()) {
			b.header("X-INTERNAL-TOKEN", internalToken);
		}
		try {
			java.net.http.HttpResponse<Path> resp = httpFile().send(b.build(),
					java.net.http.HttpResponse.BodyHandlers.ofFile(destinazione));
			if (resp.statusCode() == 404) {
				throw new IllegalStateException("File non presente su storage: " + fileName);
			}
			if (resp.statusCode() >= 300) {
				throw new IllegalStateException("Storage ha risposto " + resp.statusCode() + " su " + fileName);
			}
			return Files.exists(destinazione) ? Files.size(destinazione) : 0L;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Download interrotto: " + fileName, e);
		}
	}

	/**
	 * Carica un file su storage leggendolo DA DISCO, in streaming ({@code BodyPublishers.ofFile}):
	 * nessuna copia del contenuto in memoria, quindi la dimensione del file non e' un limite.
	 */
	private void caricaSuStorage(SftpSchedule s, String fileName, Path file) throws IOException {
		String url = UriComponentsBuilder.fromUriString(storageUrl + "/wr-storage/write-stream")
				.queryParam("intermediario", s.getStorageIntermediario()).queryParam("type", s.getStorageType())
				.queryParam("folder", s.getStorageFolder()).queryParam("fileName", fileName).toUriString();

		java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
				.header("Content-Type", "application/octet-stream")
				.timeout(java.time.Duration.ofMinutes(httpTimeoutMin))
				.POST(java.net.http.HttpRequest.BodyPublishers.ofFile(file));
		if (internalToken != null && !internalToken.isBlank()) {
			b.header("X-INTERNAL-TOKEN", internalToken);
		}
		try {
			java.net.http.HttpResponse<String> resp = httpFile().send(b.build(),
					java.net.http.HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() >= 300) {
				throw new IllegalStateException("Storage ha risposto " + resp.statusCode() + " su " + fileName
						+ (resp.body() == null || resp.body().isBlank() ? "" : " — " + abbrevia(resp.body(), 300)));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Caricamento interrotto: " + fileName, e);
		}
	}

	private HttpHeaders headerInterni() {
		HttpHeaders h = new HttpHeaders();
		if (internalToken != null && !internalToken.isBlank()) {
			h.set("X-INTERNAL-TOKEN", internalToken);
		}
		return h;
	}

	// --- Descrizioni delle cartelle per la telecronaca ------------------------------------------------
	// Servono a leggere il log senza dover aprire la configurazione: da dove viene e dove va ogni file,
	// per esteso. NB: nell'URI SFTP si mette l'utenza (e' un dato di configurazione, non un segreto);
	// la password NON compare mai da nessuna parte.

	/** Radice del server SFTP, es. {@code sftp://utente@host:22}. */
	private String radiceSftp(SftpSchedule s) {
		return "sftp://" + s.getSftpUsername() + "@" + s.getSftpHost() + ":" + s.getSftpPort();
	}

	/** Cartella remota completa, es. {@code sftp://utente@host:22/upload/liste}. */
	private String cartellaSftp(SftpSchedule s) {
		String p = (s.getSftpPath() == null) ? "" : s.getSftpPath().trim();
		while (p.endsWith("/")) {
			p = p.substring(0, p.length() - 1);
		}
		if (!p.isEmpty() && !p.startsWith("/")) {
			p = "/" + p;
		}
		return radiceSftp(s) + p;
	}

	/** Cartella di storage completa, es. {@code storage:/0/bizcom/liste}. */
	private String cartellaStorage(SftpSchedule s) {
		return "storage:/" + s.getStorageIntermediario() + "/" + s.getStorageType() + "/" + s.getStorageFolder();
	}

	private static String formatByte(long b) {
		if (b < 1024) {
			return b + " B";
		}
		if (b < 1024L * 1024) {
			return String.format(java.util.Locale.ITALIAN, "%.1f KB", b / 1024.0);
		}
		if (b < 1024L * 1024 * 1024) {
			return String.format(java.util.Locale.ITALIAN, "%.1f MB", b / 1024.0 / 1024);
		}
		return String.format(java.util.Locale.ITALIAN, "%.2f GB", b / 1024.0 / 1024 / 1024);
	}

	private static String formatDurata(long ms) {
		if (ms < 1000) {
			return ms + " ms";
		}
		if (ms < 60_000) {
			return String.format(java.util.Locale.ITALIAN, "%.1f s", ms / 1000.0);
		}
		return (ms / 60_000) + "m " + Math.round((ms % 60_000) / 1000.0) + "s";
	}

	// ------------------------------------------------------------------------------------------------
	// SSH
	// ------------------------------------------------------------------------------------------------

	/**
	 * Apre e autentica una connessione SSH. Il chiamante la chiude (try-with-resources).
	 * <p>
	 * Verifica dell'host key: se {@code sftp.known-hosts} e' valorizzato si usa quel file, altrimenti si
	 * accetta qualunque chiave (comodo in test, ma esposto a man-in-the-middle: in produzione
	 * valorizzare la property).
	 */
	private SSHClient connetti(String host, Integer porta, String utente, String password) throws IOException {
		SSHClient ssh = new SSHClient();
		ssh.setConnectTimeout(connectTimeoutMs);
		ssh.setTimeout(readTimeoutMs);
		if (knownHostsFile != null && !knownHostsFile.isBlank()) {
			ssh.loadKnownHosts(new java.io.File(knownHostsFile));
		} else {
			ssh.addHostKeyVerifier(new PromiscuousVerifier());
		}
		try {
			ssh.connect(host, (porta == null) ? 22 : porta);
			// NB: mai loggare la password.
			ssh.authPassword(utente, password == null ? "" : password);
		} catch (IOException e) {
			try {
				ssh.close();
			} catch (IOException ignored) {
				// gia' in errore: la chiusura non aggiunge informazione
			}
			throw e;
		}
		return ssh;
	}

	/**
	 * Prova host/porta/utente/password e, se indicata, la cartella: la UI lo richiama PRIMA del
	 * salvataggio, cosi' un errore si scopre subito e non al primo cron.
	 * <p>
	 * Se e' indicato anche il filtro sui nomi, riporta <b>quanti file corrisponderebbero oggi</b> col
	 * segnaposto gia' risolto: e' la verifica che conta davvero prima di mettere in produzione un
	 * pattern tipo {@code LIS_%YYYYMMDD%}.
	 * Non solleva: l'esito negativo e' un dato di ritorno.
	 */
	public SftpTestResponse testConnessione(String host, Integer porta, String utente, String password, String path,
			String filePattern) {
		if (host == null || host.isBlank() || utente == null || utente.isBlank() || password == null
				|| password.isBlank()) {
			return new SftpTestResponse(false, "Host, username e password sono obbligatori per la verifica.");
		}
		try (SSHClient ssh = connetti(host.trim(), porta, utente.trim(), password);
				SFTPClient sftp = ssh.newSFTPClient()) {
			if (path == null || path.isBlank()) {
				return new SftpTestResponse(true, "Connessione riuscita.");
			}

			List<RemoteResourceInfo> elenco = sftp.ls(path.trim());
			StringBuilder msg = new StringBuilder("Connessione riuscita. Cartella '").append(path.trim()).append("': ")
					.append(elenco.size()).append(" elementi.");

			if (filePattern != null && !filePattern.isBlank()) {
				String risolto = risolviSegnaposti(filePattern.trim(), LocalDateTime.now());
				Pattern filtro = compilaPattern(risolto);
				long quanti = elenco.stream()
						.filter(r -> r.isRegularFile() && (filtro == null || filtro.matcher(r.getName()).matches()))
						.count();
				msg.append(" Con il filtro '").append(risolto).append("' oggi corrisponderebbero ").append(quanti)
						.append(quanti == 1 ? " file." : " file.");
			}
			return new SftpTestResponse(true, msg.toString());
		} catch (net.schmizz.sshj.userauth.UserAuthException e) {
			return new SftpTestResponse(false, "Autenticazione non riuscita: utenza o password errate.");
		} catch (Exception e) {
			return new SftpTestResponse(false, "Verifica non riuscita: " + messaggioErrore(e));
		}
	}

	/**
	 * Prova le credenziali GIA' salvate su una schedulazione (decifra e si collega), senza doverle
	 * reinserire.
	 */
	public SftpTestResponse testConnessioneSalvata(Long scheduleId) {
		SftpSchedule s = scheduleRepository.findById(scheduleId)
				.orElseThrow(() -> new RuntimeException("Schedulazione SFTP non trovata"));
		String password;
		try {
			password = credentialCipher.decrypt(s.getSftpPasswordEnc());
		} catch (Exception e) {
			// Tipico se BATCH_CRED_SECRET/SALT sono cambiati dopo il salvataggio.
			return new SftpTestResponse(false, "Password memorizzata non decifrabile: reinserire le credenziali.");
		}
		return testConnessione(s.getSftpHost(), s.getSftpPort(), s.getSftpUsername(), password, s.getSftpPath(),
				s.getFilePattern());
	}

	// ------------------------------------------------------------------------------------------------
	// Telecronaca e chiusura
	// ------------------------------------------------------------------------------------------------

	/**
	 * Aggiunge una riga alla telecronaca dell'esecuzione, con data e ora in testa.
	 * Best-effort: un problema di scrittura del log non deve far fallire il trasferimento.
	 */
	private void log(Long idExecution, String messaggio) {
		try {
			SftpExecution e = executionRepository.findById(idExecution).orElse(null);
			if (e == null) {
				return;
			}
			String precedente = (e.getLog() == null) ? "" : e.getLog();
			String riga = "[" + LocalDateTime.now().format(FMT_LOG) + "] " + messaggio;
			e.setLog(precedente.isEmpty() ? riga : potaSeTroppoLungo(precedente) + System.lineSeparator() + riga);
			executionRepository.save(e);
		} catch (Exception ex) {
			logger.warn("Telecronaca SFTP non scritta per esecuzione {}: {}", idExecution, ex.getMessage());
		}
	}

	/**
	 * Tiene la telecronaca entro una dimensione ragionevole scartando le righe PIU' VECCHIE.
	 * <p>
	 * Serve perche' ogni riga rilegge e riscrive l'intero campo: con qualche migliaio di file il log
	 * crescerebbe a dismisura e ogni UPDATE diventerebbe piu' pesante della precedente. Si tagliano le
	 * righe iniziali e non le finali perche' la coda e' quella che interessa (dove si e' arrivati,
	 * l'errore, il riepilogo di chiusura).
	 */
	private String potaSeTroppoLungo(String log) {
		if (log.length() <= logMaxCaratteri) {
			return log;
		}
		int da = log.length() - (logMaxCaratteri * 3 / 4);
		int aCapo = log.indexOf('\n', da);
		String coda = log.substring((aCapo >= 0) ? aCapo + 1 : da);
		return "[...] righe iniziali omesse: telecronaca oltre " + logMaxCaratteri + " caratteri"
				+ System.lineSeparator() + coda;
	}

	private void chiudi(Long idExecution, String stato, String errore, Esito esito) {
		try {
			SftpExecution e = executionRepository.findById(idExecution).orElse(null);
			if (e == null) {
				return;
			}
			e.setStatus(stato);
			e.setEndedAt(LocalDateTime.now());
			e.setFileTrasferiti(esito.file);
			e.setByteTrasferiti(esito.byteTotali);
			e.setErrorMessage(abbrevia(errore, 4000));
			executionRepository.save(e);
		} catch (Exception ex) {
			logger.error("Chiusura esecuzione SFTP {} non riuscita: {}", idExecution, ex.getMessage());
		}
	}

	// Dopo ogni corsa la schedulazione riparte dalla prossima occorrenza del cron (le manuali restano
	// senza next_run_at).
	private void aggiornaProssimaEsecuzione(Long scheduleId) {
		try {
			SftpSchedule s = scheduleRepository.findById(scheduleId).orElse(null);
			if (s == null) {
				return;
			}
			s.setLastRunAt(LocalDateTime.now());
			s.setNextRunAt(CronScheduleUtil.nextRun(s.getCronExpression(), s.getTimezone(), s.getStartAt()));
			scheduleRepository.save(s);
		} catch (Exception ex) {
			logger.error("Aggiornamento next_run_at della schedulazione SFTP {} non riuscito: {}", scheduleId,
					ex.getMessage());
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Utilita'
	// ------------------------------------------------------------------------------------------------

	/**
	 * Traduce il filtro sui nomi file in un'espressione regolare. Vuoto/null = nessun filtro.
	 * <p>
	 * Due modi, scelti automaticamente:
	 * <ul>
	 *   <li><b>senza jolly</b> (il caso normale): il filtro e' un <b>PREFISSO</b>, cioe' "il nome deve
	 *       cominciare per...". {@code LIS_} prende {@code LIS_20260802.csv} e scarta
	 *       {@code ALTRO_20260802.csv}. E' quello che serve nel 99% dei casi e non obbliga a ricordare
	 *       la sintassi glob;</li>
	 *   <li><b>con jolly</b> {@code *} o {@code ?}: glob classico su TUTTO il nome
	 *       ({@code *.csv}, {@code LIS_*_DEF.csv}).</li>
	 * </ul>
	 * Confronto senza distinzione fra maiuscole e minuscole.
	 */
	static Pattern compilaPattern(String glob) {
		if (glob == null || glob.isBlank()) {
			return null;
		}
		String f = glob.trim();
		boolean conJolly = f.indexOf('*') >= 0 || f.indexOf('?') >= 0;
		StringBuilder sb = new StringBuilder();
		for (char c : f.toCharArray()) {
			switch (c) {
			case '*' -> sb.append(".*");
			case '?' -> sb.append('.');
			default -> sb.append(Pattern.quote(String.valueOf(c)));
			}
		}
		if (!conJolly) {
			// "inizia per": il resto del nome (data, progressivo, estensione) e' libero.
			sb.append(".*");
		}
		return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
	}

	/**
	 * Sostituisce nel filtro i <b>segnaposto di data</b> {@code %FORMATO%} con la data/ora del momento
	 * in cui il trasferimento parte.
	 * <p>
	 * Esempi (eseguendo il 2 agosto 2026):
	 * <pre>
	 *   LIS_%YYYYMMDD%        -> LIS_20260802
	 *   LIS_%DD-MM-YYYY%      -> LIS_02-08-2026
	 *   LIS_%YYYYMMDD|-1%     -> LIS_20260801      (|-1 = giorno precedente)
	 *   ESTRAZIONE_%YYYYMM%*.csv
	 * </pre>
	 * Token riconosciuti dentro i {@code %...%}: {@code YYYY} anno, {@code YY} anno a 2 cifre,
	 * {@code MM} mese, {@code DD} giorno, {@code HH} ora, {@code MI} minuti, {@code SS} secondi.
	 * Tutto il resto (trattini, underscore, punti) resta letterale.
	 * <p>
	 * Dopo il formato si puo' indicare uno scostamento in GIORNI dopo una barra verticale:
	 * {@code |-1} ieri, {@code |+1} domani. Serve ai job notturni che elaborano il file del giorno
	 * prima. Si usa {@code |} e non il segno da solo perche' il {@code -} e' anche un separatore di
	 * data valido ({@code DD-MM-YYYY}).
	 *
	 * @param adesso momento di riferimento, gia' nel fuso orario della schedulazione
	 */
	static String risolviSegnaposti(String pattern, LocalDateTime adesso) {
		if (pattern == null || pattern.indexOf('%') < 0) {
			return pattern;
		}
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < pattern.length()) {
			int apre = pattern.indexOf('%', i);
			if (apre < 0) {
				out.append(pattern, i, pattern.length());
				break;
			}
			int chiude = pattern.indexOf('%', apre + 1);
			if (chiude < 0) {
				// '%' spaiato: si lascia com'e', non si butta via il resto del filtro.
				out.append(pattern, i, pattern.length());
				break;
			}
			out.append(pattern, i, apre);

			String blocco = pattern.substring(apre + 1, chiude);
			String formato = blocco;
			LocalDateTime quando = adesso;
			int barra = blocco.lastIndexOf('|');
			if (barra >= 0) {
				String coda = blocco.substring(barra + 1).trim();
				try {
					quando = adesso.plusDays(Long.parseLong(coda.startsWith("+") ? coda.substring(1) : coda));
					formato = blocco.substring(0, barra);
				} catch (NumberFormatException e) {
					// scostamento non numerico: si tratta tutto come formato
					formato = blocco;
				}
			}
			out.append(formattaData(formato, quando));
			i = chiude + 1;
		}
		return out.toString();
	}

	// Sostituzione dei token di data. Fatta a mano invece che con DateTimeFormatter: cosi' i separatori
	// scritti dall'utente restano letterali senza dover essere quotati, e un token sconosciuto non fa
	// esplodere il trasferimento (viene semplicemente copiato).
	private static String formattaData(String formato, LocalDateTime q) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < formato.length()) {
			if (formato.startsWith("YYYY", i)) { sb.append(due4(q.getYear())); i += 4; }
			else if (formato.startsWith("YY", i)) { sb.append(due(q.getYear() % 100)); i += 2; }
			else if (formato.startsWith("MM", i)) { sb.append(due(q.getMonthValue())); i += 2; }
			else if (formato.startsWith("DD", i)) { sb.append(due(q.getDayOfMonth())); i += 2; }
			else if (formato.startsWith("HH", i)) { sb.append(due(q.getHour())); i += 2; }
			else if (formato.startsWith("MI", i)) { sb.append(due(q.getMinute())); i += 2; }
			else if (formato.startsWith("SS", i)) { sb.append(due(q.getSecond())); i += 2; }
			else { sb.append(formato.charAt(i)); i++; }
		}
		return sb.toString();
	}

	private static String due(int n) {
		return (n < 10) ? ("0" + n) : String.valueOf(n);
	}

	private static String due4(int n) {
		String s = String.valueOf(n);
		return "0".repeat(Math.max(0, 4 - s.length())) + s;
	}

	/**
	 * Scrive nella telecronaca il filtro EFFETTIVO usato in questa corsa. E' la riga che risolve la
	 * domanda piu' frequente ("perche' non ha preso niente?"): si vede subito in che cosa si e'
	 * trasformato {@code LIS_%YYYYMMDD%} il giorno in cui il job e' partito.
	 */
	private void logFiltro(Long idExecution, SftpSchedule s, String filtroRisolto) {
		if (filtroRisolto == null) {
			log(idExecution, "Filtro sui nomi: nessuno (tutti i file della cartella)");
			return;
		}
		boolean conJolly = filtroRisolto.indexOf('*') >= 0 || filtroRisolto.indexOf('?') >= 0;
		String regola = conJolly ? "corrispondenza con jolly" : "il nome deve iniziare per";
		String origine = filtroRisolto.equals(s.getFilePattern().trim()) ? ""
				: " (da '" + s.getFilePattern().trim() + "')";
		log(idExecution, "Filtro sui nomi: " + regola + " '" + filtroRisolto + "'" + origine);
	}

	/**
	 * Filtro effettivo di questa esecuzione: i segnaposto data vengono risolti sul momento in cui il
	 * trasferimento parte, valutato nel FUSO della schedulazione (un job "notturno" configurato su
	 * Europe/Rome non deve prendere la data del server se sta in un altro fuso).
	 */
	private String patternDelGiorno(SftpSchedule s) {
		if (s.getFilePattern() == null || s.getFilePattern().isBlank()) {
			return null;
		}
		return risolviSegnaposti(s.getFilePattern().trim(), LocalDateTime.now(CronScheduleUtil.zoneOf(s.getTimezone())));
	}

	/** Politica post-trasferimento in chiaro, per la riga di intestazione della telecronaca. */
	private String descrivePolitica(SftpSchedule s) {
		String p = s.getPostTransfer();
		if (SftpSchedule.POST_CANCELLA.equals(p)) {
			return "CANCELLA (dopo il trasferimento il file di origine viene cancellato)";
		}
		if (SftpSchedule.POST_SPOSTA.equals(p)) {
			return "SPOSTA (il file di origine viene archiviato nella sottocartella '" + cartellaArchivio(s) + "')";
		}
		return (p == null ? SftpSchedule.POST_LASCIA : p)
				+ " (il file di origine resta dov'e': verra' ritrasferito alla prossima esecuzione)";
	}

	private String cartellaArchivio(SftpSchedule s) {
		String f = s.getPostTransferFolder();
		return (f == null || f.isBlank()) ? "storico" : f.trim().replace('\\', '/').replace("..", "");
	}

	private String prefissoOra() {
		return LocalDateTime.now().format(FMT_PREFISSO);
	}

	// Concatenazione di percorsi remoti: sempre con '/', senza doppie barre.
	private String percorsoRemoto(String cartella, String nome) {
		String c = (cartella == null) ? "" : cartella.trim();
		while (c.endsWith("/")) {
			c = c.substring(0, c.length() - 1);
		}
		return c.isEmpty() ? nome : c + "/" + nome;
	}

	// Nome del file temporaneo locale: si scarta qualunque carattere di percorso, cosi' un nome remoto
	// ostile non puo' far scrivere fuori dalla directory temporanea.
	private String nomeTemporaneo(String nome) {
		return nome.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	private void pulisci(Path tempDir) {
		try (var s = Files.list(tempDir)) {
			for (Path p : s.toList()) {
				Files.deleteIfExists(p);
			}
		} catch (Exception ignored) {
			// cartella temporanea: se resta qualcosa lo ripulisce il sistema
		}
		try {
			Files.deleteIfExists(tempDir);
		} catch (Exception ignored) {
			// idem
		}
	}

	/**
	 * Messaggio d'errore leggibile. Per gli errori HTTP verso be-storage si aggiunge il CORPO della
	 * risposta: senza, resterebbe un "400 Bad Request" che non dice quale sia il problema (era il caso
	 * di "Required request body is missing").
	 */
	private String messaggioErrore(Exception e) {
		if (e instanceof org.springframework.web.client.RestClientResponseException re) {
			String corpo = re.getResponseBodyAsString();
			String testa = re.getStatusCode().value() + " " + re.getStatusText();
			return (corpo == null || corpo.isBlank()) ? testa : testa + " — " + abbrevia(corpo, 500);
		}
		String m = e.getMessage();
		return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
	}

	private String abbrevia(String s, int max) {
		if (s == null) {
			return null;
		}
		return (s.length() <= max) ? s : s.substring(0, max - 3) + "...";
	}
}
