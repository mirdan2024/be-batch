package it.be.batch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.be.batch.dto.Dtos.BatchExecutionRequest;
import it.be.batch.entity.BatchDefinition;
import it.be.batch.entity.BatchExecution;
import it.be.batch.entity.BatchSubscription;
import it.be.batch.repo.BatchExecutionRepository;
import it.be.batch.repo.BatchSubscriptionRepository;

/**
 * Scritture sull'esecuzione batch: mai la riga intera.
 *
 * <p>
 * Esecutore (esito HTTP), servizio a valle (telecronaca e chiusura) e amministratore (Stop) scrivono sulla
 * stessa riga anche insieme. Rileggerla e risalvarla per intero faceva vincere l'ultimo: nello storico il
 * codice HTTP spariva (esecuzioni 468, 470 e 472 del caricamento liste, colonna HTTP a "-") e una chiusura
 * poteva riportare indietro la telecronaca. Qui ciascuno scrive solo le colonne che gli spettano.
 * </p>
 */
class ScrittureEsecuzioneTest {

	private BatchExecutionRepository esecuzioni;
	private BatchChainService catena;
	private BatchExecutionService servizio;

	@BeforeEach
	void prepara() {
		esecuzioni = mock(BatchExecutionRepository.class);
		catena = mock(BatchChainService.class);
		servizio = new BatchExecutionService(esecuzioni, catena);
	}

	@Test
	@DisplayName("Telecronaca: una riga in coda in SQL, col battito; la riga dell'esecuzione non si risalva")
	void rigaDiTelecronaca() {
		servizio.appendLog(7L, "soc_generale: confronto 100000/6000000 (1%)");

		ArgumentCaptor<String> riga = ArgumentCaptor.forClass(String.class);
		verify(esecuzioni).aggiungiRigaLog(eq(7L), riga.capture(), eq(System.lineSeparator()),
				any(LocalDateTime.class));
		assertTrue(riga.getValue().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}  soc_generale: confronto .*"),
				riga.getValue());
		verify(esecuzioni, never()).save(any());
		verify(esecuzioni, never()).findById(anyLong());
	}

	@Test
	@DisplayName("Riga vuota: niente da scrivere")
	void rigaVuota() {
		servizio.appendLog(7L, "  ");
		verify(esecuzioni, never()).aggiungiRigaLog(anyLong(), anyString(), anyString(), any());
	}

	@Test
	@DisplayName("Chiusura dal servizio: stato e fine mirati, telecronaca in coda, poi la catena")
	void chiusuraDalServizio() {
		BatchSubscription schedulazione = new BatchSubscription();
		BatchExecution riga = new BatchExecution();
		riga.setBatchSubscription(schedulazione);
		when(esecuzioni.findById(7L)).thenReturn(Optional.of(riga));

		servizio.finish(7L, "completed", "File caricati 6/6", " ");

		InOrder ordine = inOrder(esecuzioni, catena);
		// Corpo vuoto = non si tocca quello che c'e' (null nella query).
		ordine.verify(esecuzioni).chiudi(eq(7L), eq("COMPLETED"), any(LocalDateTime.class), eq("File caricati 6/6"),
				isNull());
		ordine.verify(esecuzioni).aggiungiRigaLog(eq(7L), anyString(), anyString(), any(LocalDateTime.class));
		ordine.verify(catena).esecuzioneConclusa(schedulazione, "COMPLETED");
		verify(esecuzioni, never()).save(any());
	}

	@Test
	@DisplayName("Aggiornamento dell'esito: le tre colonne di prima; esecuzione inesistente = errore come prima")
	void aggiornamentoEsito() {
		when(esecuzioni.aggiornaEsito(7L, "ok", "COMPLETED", 200)).thenReturn(1);
		servizio.update(7L, new BatchExecutionRequest(7L, "COMPLETED", "ok", 200));
		verify(esecuzioni).aggiornaEsito(7L, "ok", "COMPLETED", 200);
		verify(esecuzioni, never()).save(any());

		assertThrows(RuntimeException.class, () -> servizio.update(8L, new BatchExecutionRequest(8L, "COMPLETED", "ok", 200)));
	}

	/** Esecutore con una schedulazione minima e la risposta HTTP indicata. */
	private BatchExecutor esecutore(ResponseEntity<String> risposta) {
		RestTemplate rest = mock(RestTemplate.class);
		when(rest.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
				.thenReturn(risposta);
		when(esecuzioni.save(any(BatchExecution.class))).thenAnswer(i -> {
			BatchExecution e = i.getArgument(0);
			e.setId(500L);
			return e;
		});
		return new BatchExecutor(rest, new ObjectMapper(), esecuzioni, mock(BatchSubscriptionRepository.class),
				new TransactionTemplate(mock(PlatformTransactionManager.class)), catena);
	}

	private static BatchSubscription schedulazione() {
		BatchDefinition definizione = new BatchDefinition();
		definizione.setEndpointUrl("http://servizio/run");
		definizione.setHttpMethod(BatchDefinition.HttpMethodType.POST);
		BatchSubscription s = new BatchSubscription();
		s.setId(12L);
		s.setBatchDefinition(definizione);
		return s;
	}

	@Test
	@DisplayName("202: l'esecutore scrive solo il codice; stato, fine e telecronaca restano al servizio")
	void presoInCarico() {
		esecutore(new ResponseEntity<>("", HttpStatus.ACCEPTED)).execute(schedulazione(), "jwt");

		verify(esecuzioni).registraCodice(500L, 202);
		// Un solo save: la creazione della riga. Dopo, mai piu' la riga intera.
		verify(esecuzioni, times(1)).save(any(BatchExecution.class));
		verify(esecuzioni, never()).registraEsito(anyLong(), any(), any(), any(), any(), any());
		verify(catena, never()).esecuzioneConclusa(any(), any());
	}

	@Test
	@DisplayName("Risposta sincrona: esito mirato (codice, corpo, stato, fine) e poi la catena")
	void rispostaSincrona() {
		esecutore(new ResponseEntity<>("fatto", HttpStatus.OK)).execute(schedulazione(), "jwt");

		verify(esecuzioni).registraEsito(eq(500L), eq(200), eq("fatto"), eq("COMPLETED"), isNull(),
				any(LocalDateTime.class));
		verify(esecuzioni, times(1)).save(any(BatchExecution.class));
		verify(catena).esecuzioneConclusa(any(BatchSubscription.class), eq("COMPLETED"));
		assertEquals(1, org.mockito.Mockito.mockingDetails(catena).getInvocations().size());
	}
}
