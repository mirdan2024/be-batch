package it.be.batch.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import it.be.batch.dto.Dtos.LoginResponse;
import it.be.batch.dto.LoginPojo;
import it.be.batch.entity.BatchSubscription;
import it.be.batch.repo.BatchSubscriptionRepository;

@Component
public class BatchScheduler {
	private static final Logger logger = LoggerFactory.getLogger(BatchScheduler.class);

	@Value("${url.bebase.service}")
	private String urlBeBaseLoginService;

	private final BatchSubscriptionRepository subscriptionRepository;
	private final BatchExecutor batchExecutor;
	// Bean con timeout di connessione/lettura: un login "appeso" non deve bloccare lo scheduler per
	// sempre. new RestTemplate() (senza timeout) attenderebbe indefinitamente.
	private final RestTemplate restTemplate;
	private final CredentialCipher credentialCipher;

	public BatchScheduler(BatchSubscriptionRepository subscriptionRepository, BatchExecutor batchExecutor,
			@Qualifier("RestTimeout") RestTemplate restTemplate, CredentialCipher credentialCipher) {
		super();
		this.subscriptionRepository = subscriptionRepository;
		this.batchExecutor = batchExecutor;
		this.restTemplate = restTemplate;
		this.credentialCipher = credentialCipher;
	}

	@Scheduled(fixedDelayString = "${batch.scheduler.fixed-delay-ms}")
	public void dispatch() {
		LocalDateTime now = LocalDateTime.now();

		List<BatchSubscription> dueBatches = subscriptionRepository.findByEnabledTrueAndNextRunAtLessThanEqual(now);
		for (BatchSubscription subscription : dueBatches) {
			// La definizione può essere stata disattivata dopo la sottoscrizione: in tal caso si salta,
			// senza autenticarsi né chiamare l'endpoint.
			if (subscription.getBatchDefinition() == null || !subscription.getBatchDefinition().isEnabled()) {
				logger.info("Batch subscription {} saltata: definizione assente o disattivata", subscription.getId());
				continue;
			}
			try {
				String jwt = login(subscription);
				if (jwt == null) {
					logger.warn("Login batch non riuscito per subscription {}: token assente", subscription.getId());
					continue;
				}
				batchExecutor.execute(subscription, jwt);
			} catch (RestClientException rce) {
				logger.error("Errore nella chiamata di login batch per subscription {}: {}", subscription.getId(),
						rce.getMessage());
			} catch (Exception e) {
				logger.error("Errore imprevisto nell'esecuzione batch per subscription {}: {}", subscription.getId(),
						e.getMessage(), e);
			}
		}
	}

	/**
	 * Esecuzione UNA TANTUM richiesta manualmente dalla UI: parte SUBITO, anche fuori orario di
	 * schedulazione e anche se la sottoscrizione è bloccata (il click è una volontà esplicita; il blocco
	 * ferma solo lo scheduler). Richiede però la definizione attiva, come il dispatch.
	 * ASINCRONA: la risposta HTTP torna subito con "AVVIATA"; login + chiamata girano in un thread a
	 * parte e l'esito si legge nello storico esecuzioni (batch_execution), come per i run schedulati.
	 * Ritorna: AVVIATA / NON_TROVATA / DEFINIZIONE_DISATTIVATA.
	 */
	public String eseguiUnaTantum(Long subscriptionId) {
		BatchSubscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
		if (subscription == null) {
			return "NON_TROVATA";
		}
		if (subscription.getBatchDefinition() == null || !subscription.getBatchDefinition().isEnabled()) {
			return "DEFINIZIONE_DISATTIVATA";
		}
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				String jwt = login(subscription);
				if (jwt == null) {
					logger.warn("Esecuzione una tantum: login fallito per subscription {} (token assente)",
							subscription.getId());
					return;
				}
				batchExecutor.execute(subscription, jwt);
			} catch (Exception e) {
				logger.error("Esecuzione una tantum fallita per subscription {}: {}", subscription.getId(),
						e.getMessage());
			}
		});
		return "AVVIATA";
	}

	// Autenticazione: login su be-base con le credenziali CONFIGURATE sulla sottoscrizione (username +
	// password cifrata, decifrata qui solo al momento dell'uso). Restituisce il JWT, o null se il
	// servizio non torna un token.
	private String login(BatchSubscription subscription) {
		LoginPojo loginPojo = new LoginPojo();
		loginPojo.setUsername(subscription.getUsername());
		loginPojo.setPassword(credentialCipher.decrypt(subscription.getPasswordEnc()));

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<LoginPojo> entity = new HttpEntity<>(loginPojo, headers);

		// NB: non loggare il body — contiene la password in chiaro.
		logger.info("Login batch per subscription {} (utenza {}) verso {}", subscription.getId(),
				subscription.getUsername(), urlBeBaseLoginService);
		ResponseEntity<LoginResponse> loginJwt = restTemplate.postForEntity(urlBeBaseLoginService, entity,
				LoginResponse.class);

		LoginResponse body = loginJwt.getBody();
		return (body != null) ? body.jwt() : null;
	}
}
