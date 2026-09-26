package it.be.batch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import it.be.batch.dto.Dtos.LoginResponse;
import it.be.batch.entity.BatchDefinition;
import it.be.batch.entity.BatchExecution;
import it.be.batch.entity.BatchSubscription;
import it.be.batch.repo.BatchExecutionRepository;
import it.be.batch.repo.BatchSubscriptionRepository;

/**
 * "Riprendi" nello storico esecuzioni: quando si puo' e che cosa parte.
 *
 * <p>
 * Si riprende solo l'ultima esecuzione della schedulazione, solo se e' fallita o interrotta, e solo per
 * i servizi che dichiarano come ripartire (resume_url). Il servizio ritrova il lavoro con l'esecuzione
 * che l'aveva avviato: se si riprende una ripresa, be-batch risale la catena.
 * </p>
 */
class RipresaEsecuzioneTest {

	private BatchSubscriptionRepository subscriptions;
	private BatchExecutionRepository executions;
	private BatchExecutor executor;
	private RestTemplate restTemplate;
	private BatchScheduler scheduler;
	private BatchSubscription schedulazione;

	@BeforeEach
	void prepara() {
		subscriptions = mock(BatchSubscriptionRepository.class);
		executions = mock(BatchExecutionRepository.class);
		executor = mock(BatchExecutor.class);
		restTemplate = mock(RestTemplate.class);
		CredentialCipher cipher = mock(CredentialCipher.class);
		when(cipher.decrypt(any())).thenReturn("segreta");
		when(restTemplate.postForEntity(eq("http://login"), any(), eq(LoginResponse.class)))
				.thenReturn(ResponseEntity.ok(new LoginResponse("jwt")));
		scheduler = new BatchScheduler(subscriptions, executor, restTemplate, cipher, executions);
		// La property che Spring inietterebbe: qui lo scheduler e' costruito a mano.
		org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "urlBeBaseLoginService", "http://login");

		BatchDefinition definizione = new BatchDefinition();
		definizione.setCode("bizcom-soc-import-full-come-delta");
		definizione.setEnabled(true);
		definizione.setResumeUrl("http://servizio/bizcom-import/riprendi");
		schedulazione = new BatchSubscription();
		schedulazione.setId(15L);
		schedulazione.setBatchDefinition(definizione);
		schedulazione.setUsername("batch");
		when(subscriptions.findById(15L)).thenReturn(Optional.of(schedulazione));
		when(executions.findByBatchSubscriptionIdAndStatusAndEndedAtIsNull(eq(15L), anyString())).thenReturn(List.of());
	}

	private BatchExecution esecuzione(long id, String stato, Long ripresaDi) {
		BatchExecution e = new BatchExecution();
		e.setId(id);
		e.setStatus(stato);
		e.setStartedAt(LocalDateTime.now());
		e.setIdRipresaDi(ripresaDi);
		when(executions.findById(id)).thenReturn(Optional.of(e));
		when(executions.findByIdAndBatchSubscriptionId(id, 15L)).thenReturn(Optional.of(e));
		return e;
	}

	private void ultima(BatchExecution e) {
		when(executions.findFirstByBatchSubscriptionIdOrderByStartedAtDescIdDesc(15L)).thenReturn(Optional.of(e));
	}

	@Test
	@DisplayName("La ripresa di una ripresa porta al servizio l'esecuzione che aveva avviato il lavoro")
	void risaleLaCatena() {
		esecuzione(220, "FAILED", null);
		esecuzione(250, "INTERROTTA", 220L);
		ultima(esecuzione(300, "FAILED", 250L));

		assertEquals("AVVIATA", scheduler.riprendiUnaTantum(15L, 300L));
		verify(executor, timeout(2000)).riprendi(schedulazione, "jwt", 300L, 220L);
	}

	@Test
	@DisplayName("Solo l'ultima esecuzione: dopo c'e' stato altro, riprenderla non ha senso")
	void soloLUltima() {
		esecuzione(220, "FAILED", null);
		ultima(esecuzione(230, "COMPLETED", null));

		assertEquals("ESECUZIONE_NON_ULTIMA", scheduler.riprendiUnaTantum(15L, 220L));
		verify(executor, never()).riprendi(any(), any(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("Solo esecuzioni fallite o interrotte")
	void soloFalliteOInterrotte() {
		ultima(esecuzione(230, "COMPLETED", null));
		assertEquals("STATO_NON_RIPRENDIBILE", scheduler.riprendiUnaTantum(15L, 230L));
	}

	@Test
	@DisplayName("Mai due elaborazioni insieme sulla stessa schedulazione")
	void nonSeGiaInCorso() {
		ultima(esecuzione(220, "FAILED", null));
		when(executions.findByBatchSubscriptionIdAndStatusAndEndedAtIsNull(eq(15L), anyString()))
				.thenReturn(List.of(new BatchExecution()));

		assertEquals("IN_CORSO", scheduler.riprendiUnaTantum(15L, 220L));
	}

	@Test
	@DisplayName("Senza resume_url il servizio non sa ripartire: niente ripresa")
	void servizioSenzaRipresa() {
		schedulazione.getBatchDefinition().setResumeUrl(null);
		ultima(esecuzione(220, "FAILED", null));

		assertEquals("NON_RIPRENDIBILE", scheduler.riprendiUnaTantum(15L, 220L));
	}

	@Test
	@DisplayName("Un'esecuzione di un'altra schedulazione non si riprende da qui")
	void esecuzioneDiAltri() {
		when(executions.findByIdAndBatchSubscriptionId(999L, 15L)).thenReturn(Optional.empty());
		assertEquals("ESECUZIONE_NON_TROVATA", scheduler.riprendiUnaTantum(15L, 999L));
	}
}
