package it.be.batch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import it.be.batch.dto.Dtos.LoginResponse;
import it.be.batch.dto.Dtos.TestCredentialsResponse;
import it.be.batch.repo.BatchDefinitionRepository;
import it.be.batch.repo.BatchExecutionRepository;
import it.be.batch.repo.BatchSubscriptionRepository;
import it.be.batch.repo.IntermediarioRefRepository;

/**
 * La prova delle credenziali di una schedulazione dice a chi la fa che cosa non va, senza rovesciargli addosso la
 * risposta di be-base.
 *
 * <p>
 * Fino al 02-10-2026 l'esito era il messaggio dell'eccezione, cioe' metodo, indirizzo e corpo intero della
 * risposta. Ora le eccezioni delle chiamate fra servizi dicono solo lo stato ({@code GestoreErroriHttp}), e questa
 * pagina aggiunge da se' le due cose che servono a chi sta provando: il motivo scritto da be-base, quando c'e', e
 * <b>l'indirizzo chiamato</b>. Una risposta di errore diversa dal 401 vuol dire quasi sempre che l'indirizzo del
 * login e' configurato male ({@code url.bebase.login}): e' gia' successo in produzione, e senza l'indirizzo
 * nell'esito non c'e' modo di accorgersene da qui.
 * </p>
 */
class ProvaCredenzialiTest {

	private static final String LOGIN = "http://be-base:8081/be-base/login/doLoginBatch";

	private RestTemplate restTemplate;
	private BatchSubscriptionService servizio;

	@BeforeEach
	void prepara() {
		restTemplate = mock(RestTemplate.class);
		servizio = new BatchSubscriptionService(mock(BatchSubscriptionRepository.class),
				mock(BatchDefinitionRepository.class), mock(BatchExecutionRepository.class), mock(CredentialCipher.class),
				mock(IntermediarioRefRepository.class), restTemplate, mock(BatchScheduler.class));
		ReflectionTestUtils.setField(servizio, "urlBeBaseLoginService", LOGIN);
	}

	@Test
	@DisplayName("Login riuscito: credenziali valide")
	void valide() {
		when(restTemplate.postForEntity(eq(LOGIN), any(HttpEntity.class), eq(LoginResponse.class)))
				.thenReturn(ResponseEntity.ok(new LoginResponse("token-di-prova")));

		TestCredentialsResponse esito = servizio.testCredentials("batch", "Password-di-prova-1");

		assertTrue(esito.ok());
		assertEquals("Credenziali valide.", esito.message());
	}

	@Test
	@DisplayName("401: le credenziali sono sbagliate, e lo si dice cosi'")
	void sbagliate() {
		risponde(HttpStatus.UNAUTHORIZED, "");

		TestCredentialsResponse esito = servizio.testCredentials("batch", "Password-di-prova-1");

		assertFalse(esito.ok());
		assertEquals("Credenziali non valide (utenza inesistente, cessata o password errata).", esito.message());
	}

	@Test
	@DisplayName("404: lo stato e l'indirizzo chiamato, non il corpo della risposta")
	void indirizzoSbagliato() {
		risponde(HttpStatus.NOT_FOUND, "{\"timestamp\":\"2026-10-02T09:00:00\",\"status\":404,\"error\":\"Not Found\","
				+ "\"path\":\"/be-base/login/doLoginBatch\"}");

		TestCredentialsResponse esito = servizio.testCredentials("batch", "Password-di-prova-1");

		assertFalse(esito.ok());
		assertEquals("Verifica non riuscita: 404 Not Found (indirizzo chiamato: " + LOGIN + ")", esito.message());
	}

	@Test
	@DisplayName("Rifiuto con un motivo: il motivo scritto da be-base, lo stato e l'indirizzo")
	void rifiutoConMotivo() {
		risponde(HttpStatus.TOO_MANY_REQUESTS,
				"{\"code\":429,\"message\":\"Troppi tentativi: riprova fra 60 secondi\",\"data\":null,\"success\":false}");

		TestCredentialsResponse esito = servizio.testCredentials("batch", "Password-di-prova-1");

		assertEquals("Verifica non riuscita: Troppi tentativi: riprova fra 60 secondi (HTTP 429) (indirizzo chiamato: "
				+ LOGIN + ")", esito.message());
	}

	@Test
	@DisplayName("be-base non raggiungibile: il messaggio del guasto com'era, che l'indirizzo lo porta gia'")
	void nonRaggiungibile() {
		String guasto = "I/O error on POST request for \"" + LOGIN + "\": Connection refused";
		when(restTemplate.postForEntity(eq(LOGIN), any(HttpEntity.class), eq(LoginResponse.class)))
				.thenThrow(new ResourceAccessException(guasto));

		TestCredentialsResponse esito = servizio.testCredentials("batch", "Password-di-prova-1");

		assertEquals("Verifica non riuscita: " + guasto, esito.message());
	}

	/** be-base risponde con un errore: l'eccezione e' quella che solleva il RestTemplate del servizio. */
	private void risponde(HttpStatus stato, String corpo) {
		when(restTemplate.postForEntity(eq(LOGIN), any(HttpEntity.class), eq(LoginResponse.class)))
				.thenThrow(HttpClientErrorException.create(stato.value() + " " + stato.getReasonPhrase(), stato, "",
						new HttpHeaders(), corpo.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
	}
}
