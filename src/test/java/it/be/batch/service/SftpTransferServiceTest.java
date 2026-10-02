package it.be.batch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.sun.net.httpserver.HttpServer;

import it.be.batch.entity.SftpExecution;
import it.be.batch.entity.SftpSchedule;
import it.be.batch.repo.SftpExecutionRepository;
import it.be.batch.repo.SftpScheduleRepository;
import it.common.base.batch.BatchJobControl;
import it.common.base.batch.BatchJobRegistry;

/**
 * Trasferimenti SFTP: le parti che si possono provare senza un server SFTP vero.
 *
 * <p>
 * Filtro sui nomi con i segnaposto di data, copia col tetto ed estrazione degli archivi ZIP. Lo storage
 * e' un piccolo server HTTP locale che annota le richieste ricevute: cosi' si vede che cosa arriverebbe
 * davvero a be-storage (nome del file, cartella, dimensione) e che cosa finisce nella telecronaca.
 * </p>
 */
class SftpTransferServiceTest {

	private static final LocalDateTime ADESSO = LocalDateTime.of(2026, 8, 2, 7, 5, 9);

	@TempDir
	Path cartella;

	private HttpServer storage;
	private final List<String> richieste = Collections.synchronizedList(new ArrayList<>());
	private SftpExecution esecuzione;
	private BatchJobControl controllo;
	private SftpTransferService servizio;

	@BeforeEach
	void prepara() throws IOException {
		storage = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		storage.createContext("/wr-storage/write-stream", scambio -> {
			int lunghezza = scambio.getRequestBody().readAllBytes().length;
			String query = scambio.getRequestURI().getQuery();
			richieste.add(query + " byte=" + lunghezza + " token="
					+ scambio.getRequestHeaders().getFirst("X-INTERNAL-TOKEN"));
			boolean rifiuta = query.contains("rifiuta");
			byte[] corpo = (rifiuta ? "disco pieno" : "ok").getBytes(StandardCharsets.UTF_8);
			scambio.sendResponseHeaders(rifiuta ? 500 : 200, corpo.length);
			try (OutputStream out = scambio.getResponseBody()) {
				out.write(corpo);
			}
		});
		storage.start();

		esecuzione = new SftpExecution();
		esecuzione.setLog("");
		SftpExecutionRepository esecuzioni = mock(SftpExecutionRepository.class);
		when(esecuzioni.findById(anyLong())).thenReturn(Optional.of(esecuzione));
		when(esecuzioni.save(any(SftpExecution.class))).thenAnswer(i -> i.getArgument(0));

		controllo = new BatchJobControl("sftp-schedule-3");
		BatchJobRegistry registro = mock(BatchJobRegistry.class);
		when(registro.get(anyString())).thenReturn(controllo);

		servizio = new SftpTransferService(mock(SftpScheduleRepository.class), esecuzioni, mock(CredentialCipher.class),
				registro, mock(RestTemplate.class));
		// Le property che Spring inietterebbe: qui il servizio e' costruito a mano.
		ReflectionTestUtils.setField(servizio, "storageUrl", "http://127.0.0.1:" + storage.getAddress().getPort());
		ReflectionTestUtils.setField(servizio, "internalToken", "token-di-prova");
		ReflectionTestUtils.setField(servizio, "connectTimeoutMs", 5000);
		ReflectionTestUtils.setField(servizio, "httpTimeoutMin", 1);
		ReflectionTestUtils.setField(servizio, "logMaxCaratteri", 200000);
		ReflectionTestUtils.setField(servizio, "zipMaxFileMb", 1);
	}

	@AfterEach
	void spegni() {
		storage.stop(0);
	}

	// ------------------------------------------------------------------------------------------------
	// Filtro sui nomi
	// ------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("Segnaposto di data: formati, scostamento in giorni, '%' spaiato e scostamento non numerico")
	void segnaposti() {
		assertEquals("LIS_20260802", SftpTransferService.risolviSegnaposti("LIS_%YYYYMMDD%", ADESSO));
		assertEquals("LIS_02-08-2026", SftpTransferService.risolviSegnaposti("LIS_%DD-MM-YYYY%", ADESSO));
		assertEquals("LIS_20260801", SftpTransferService.risolviSegnaposti("LIS_%YYYYMMDD|-1%", ADESSO));
		assertEquals("LIS_20260803", SftpTransferService.risolviSegnaposti("LIS_%YYYYMMDD|+1%", ADESSO));
		assertEquals("LIS_20260731_x", SftpTransferService.risolviSegnaposti("LIS_%YYYYMMDD| -2 %_x", ADESSO));
		assertEquals("ESTRAZIONE_202608*.csv", SftpTransferService.risolviSegnaposti("ESTRAZIONE_%YYYYMM%*.csv", ADESSO));
		assertEquals("A_26_070509_B_2026", SftpTransferService.risolviSegnaposti("A_%YY_HHMISS%_B_%YYYY%", ADESSO));
		// Token sconosciuto: copiato com'e'.
		assertEquals("X_2026Q", SftpTransferService.risolviSegnaposti("X_%YYYYQ%", ADESSO));
		// '%' spaiato: il resto del filtro resta com'e'.
		assertEquals("LIS_20260802_50%", SftpTransferService.risolviSegnaposti("LIS_%YYYYMMDD%_50%", ADESSO));
		assertEquals("50%", SftpTransferService.risolviSegnaposti("50%", ADESSO));
		// Scostamento non numerico: tutto il blocco e' formato (la barra resta letterale).
		assertEquals("LIS_2026|ieri", SftpTransferService.risolviSegnaposti("LIS_%YYYY|ieri%", ADESSO));
		assertEquals("", SftpTransferService.risolviSegnaposti("%%", ADESSO));
		assertEquals("senza segnaposto", SftpTransferService.risolviSegnaposti("senza segnaposto", ADESSO));
		assertNull(SftpTransferService.risolviSegnaposti(null, ADESSO));
	}

	@Test
	@DisplayName("Filtro: senza jolly e' un prefisso, con jolly vale su tutto il nome; maiuscole indifferenti")
	void filtro() {
		assertNull(SftpTransferService.compilaPattern(" "));
		Pattern prefisso = SftpTransferService.compilaPattern("LIS_");
		assertTrue(prefisso.matcher("lis_20260802.csv").matches());
		assertFalse(prefisso.matcher("ALTRO_LIS_20260802.csv").matches());
		Pattern jolly = SftpTransferService.compilaPattern("LIS_*_DEF.cs?");
		assertTrue(jolly.matcher("LIS_20260802_DEF.csv").matches());
		assertFalse(jolly.matcher("LIS_20260802_DEF.csv.tmp").matches());
		// I caratteri speciali delle espressioni regolari restano letterali.
		assertFalse(SftpTransferService.compilaPattern("a.b").matcher("aXb").matches());
	}

	// ------------------------------------------------------------------------------------------------
	// Copia col tetto
	// ------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("Copia col tetto: entro il limite copia tutto, oltre cancella il parziale e risponde -1")
	void copiaConTetto() throws IOException {
		byte[] dati = new byte[200_000];
		Arrays.fill(dati, (byte) 7);

		Path entro = cartella.resolve("entro.bin");
		assertEquals(200_000L, copia(dati, entro, 200_000L));
		assertEquals(200_000L, Files.size(entro));

		Path oltre = cartella.resolve("oltre.bin");
		assertEquals(-1L, copia(dati, oltre, 199_999L));
		assertFalse(Files.exists(oltre));

		// Tetto a zero = nessun controllo.
		Path libero = cartella.resolve("libero.bin");
		assertEquals(200_000L, copia(dati, libero, 0L));
		assertEquals(200_000L, Files.size(libero));
	}

	private static long copia(byte[] dati, Path destinazione, long limite) throws IOException {
		return SftpTransferService.copiaConTetto(new ByteArrayInputStream(dati), destinazione, limite);
	}

	// ------------------------------------------------------------------------------------------------
	// Estrazione degli archivi
	// ------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("ZIP: su storage vanno i file contenuti, senza cartelle; voci non valide e troppo grandi saltate")
	void estrazione() throws IOException {
		Path zip = cartella.resolve("LIS_20260802.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			voce(out, "dir/", null);
			voce(out, "dir/a.txt", new byte[5]);
			voce(out, "b.csv", new byte[0]);
			voce(out, "sopra/..", new byte[1]);
			voce(out, "x\\y\\c.txt", new byte[3]);
			voce(out, "grande.bin", new byte[2 * 1024 * 1024]);
			voce(out, "ultimo.txt", new byte[2000]);
		}
		SftpTransferService.Esito esito = new SftpTransferService.Esito();

		estrai(zip, "LIS_20260802.zip", esito, 2, 4);

		assertEquals(String.join("\n",
				"         estratto a.txt   5 B   a storage:/0/bizcom/liste/a.txt",
				"         estratto b.csv   0 B   a storage:/0/bizcom/liste/b.csv",
				"         SALTATA voce con nome non valido: sopra/..",
				"         estratto c.txt   3 B   a storage:/0/bizcom/liste/c.txt",
				"         SALTATA grande.bin: dichiarata 2,0 MB, oltre il limite di 1 MB",
				"         estratto ultimo.txt   2,0 KB   a storage:/0/bizcom/liste/ultimo.txt",
				"[2/4] OK      LIS_20260802.zip   4 file estratti (2,0 KB), 2 saltati in <t>   a storage:/0/bizcom/liste",
				"         l'archivio LIS_20260802.zip NON viene copiato su storage (scartato)"), telecronaca());
		assertEquals(List.of(
				"intermediario=0&type=bizcom&folder=liste&fileName=a.txt byte=5 token=token-di-prova",
				"intermediario=0&type=bizcom&folder=liste&fileName=b.csv byte=0 token=token-di-prova",
				"intermediario=0&type=bizcom&folder=liste&fileName=c.txt byte=3 token=token-di-prova",
				"intermediario=0&type=bizcom&folder=liste&fileName=ultimo.txt byte=2000 token=token-di-prova"),
				richieste);
		assertEquals("file=4 byte=2008 errori=0 primoErrore=null avvisi=1"
				+ " primoAvviso=LIS_20260802.zip/grande.bin: voce oltre 1 MB, saltata", riepilogo(esito));
		assertEquals(List.of("LIS_20260802.zip"), nomiIn(cartella));
	}

	@Test
	@DisplayName("ZIP con limite 0: nessun limite, le voci non vengono saltate")
	void estrazioneSenzaLimite() throws IOException {
		ReflectionTestUtils.setField(servizio, "zipMaxFileMb", 0);
		Path zip = cartella.resolve("LIS_20260803.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			voce(out, "a.txt", new byte[5]);
			voce(out, "grande.bin", new byte[2 * 1024 * 1024]);
		}
		SftpTransferService.Esito esito = new SftpTransferService.Esito();

		estrai(zip, "LIS_20260803.zip", esito, 1, 1);

		assertEquals(List.of(
				"intermediario=0&type=bizcom&folder=liste&fileName=a.txt byte=5 token=token-di-prova",
				"intermediario=0&type=bizcom&folder=liste&fileName=grande.bin byte=2097152 token=token-di-prova"),
				richieste);
		assertEquals("file=2 byte=2097157 errori=0 primoErrore=null avvisi=0 primoAvviso=null", riepilogo(esito));
	}

	@Test
	@DisplayName("ZIP senza file: avviso di archivio vuoto, nessuna scrittura su storage")
	void archivioVuoto() throws IOException {
		Path zip = cartella.resolve("vuoto.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			voce(out, "solo-cartella/", null);
		}
		SftpTransferService.Esito esito = new SftpTransferService.Esito();

		estrai(zip, "vuoto.zip", esito, 1, 1);

		assertEquals(String.join("\n",
				"[1/1] ATTENZIONE vuoto.zip   archivio VUOTO: nessun file estratto",
				"         l'archivio vuoto.zip NON viene copiato su storage (scartato)"), telecronaca());
		assertTrue(richieste.isEmpty());
		assertEquals("file=0 byte=0 errori=0 primoErrore=null avvisi=1 primoAvviso=vuoto.zip: archivio senza file utili",
				riepilogo(esito));
	}

	@Test
	@DisplayName("ZIP con stop richiesto: l'estrazione si ferma alla prima voce")
	void estrazioneFermata() throws IOException {
		Path zip = cartella.resolve("fermo.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			voce(out, "a.txt", new byte[5]);
			voce(out, "b.txt", new byte[5]);
		}
		SftpTransferService.Esito esito = new SftpTransferService.Esito();
		controllo.begin();
		controllo.requestStop();

		estrai(zip, "fermo.zip", esito, 1, 1);

		assertEquals(String.join("\n",
				"         STOP richiesto durante l'estrazione: 0 file estratti",
				"[1/1] ATTENZIONE fermo.zip   archivio VUOTO: nessun file estratto",
				"         l'archivio fermo.zip NON viene copiato su storage (scartato)"), telecronaca());
		assertTrue(richieste.isEmpty());
	}

	@Test
	@DisplayName("ZIP, storage che rifiuta una voce: l'errore risale col motivo e la copia estratta non resta su disco")
	void storageRifiuta() throws IOException {
		Path zip = cartella.resolve("ko.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			voce(out, "a.txt", new byte[5]);
			voce(out, "rifiuta.txt", new byte[5]);
			voce(out, "mai.txt", new byte[5]);
		}
		SftpTransferService.Esito esito = new SftpTransferService.Esito();

		IllegalStateException errore = assertThrows(IllegalStateException.class,
				() -> estrai(zip, "ko.zip", esito, 1, 1));

		assertEquals("Storage ha risposto 500 su rifiuta.txt — disco pieno", errore.getMessage());
		assertEquals("         estratto a.txt   5 B   a storage:/0/bizcom/liste/a.txt", telecronaca());
		assertEquals(2, richieste.size());
		assertEquals("file=1 byte=5 errori=0 primoErrore=null avvisi=0 primoAvviso=null", riepilogo(esito));
		assertEquals(List.of("ko.zip"), nomiIn(cartella));
	}

	// ------------------------------------------------------------------------------------------------
	// Attrezzi
	// ------------------------------------------------------------------------------------------------

	private static void voce(ZipOutputStream out, String nome, byte[] contenuto) throws IOException {
		out.putNextEntry(new ZipEntry(nome));
		if (contenuto != null) {
			out.write(contenuto);
		}
		out.closeEntry();
	}

	private static SftpSchedule schedulazione() {
		SftpSchedule s = new SftpSchedule();
		s.setId(3L);
		s.setStorageIntermediario("0");
		s.setStorageType("bizcom");
		s.setStorageFolder("liste");
		return s;
	}

	/** L'estrazione si prova da sola: il prelievo da SFTP che la precede vuole un server vero. */
	private void estrai(Path zip, String nomeZip, SftpTransferService.Esito esito, int progressivo, int totale)
			throws IOException {
		servizio.estraiZipSuStorage(schedulazione(), zip, nomeZip,
				new SftpTransferService.Passo(9L, esito, progressivo, totale, System.currentTimeMillis()));
	}

	/** La telecronaca senza la data in testa a ogni riga e senza le durate, che cambiano a ogni corsa. */
	private String telecronaca() {
		return esecuzione.getLog().lines().map(riga -> riga.replaceFirst("^\\[[0-9: -]{19}\\] ", ""))
				.map(riga -> riga.replaceAll(" in \\d+(,\\d+)? (ms|s)", " in <t>"))
				.collect(java.util.stream.Collectors.joining("\n"));
	}

	private static String riepilogo(SftpTransferService.Esito esito) {
		return "file=" + esito.file + " byte=" + esito.byteTotali + " errori=" + esito.errori + " primoErrore="
				+ esito.primoErrore + " avvisi=" + esito.avvisi + " primoAvviso=" + esito.primoAvviso;
	}

	private static List<String> nomiIn(Path dir) throws IOException {
		try (var elenco = Files.list(dir)) {
			return elenco.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}
}
