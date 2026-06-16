package it.be.batch.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.be.batch.dto.Dtos.LoginResponse;
import it.be.batch.dto.LoginPojo;
import it.be.batch.entity.BatchSubscription;
import it.be.batch.repo.BatchSubscriptionRepository;

@Component
public class BatchScheduler {
	private static final Logger logger = LoggerFactory.getLogger(BatchScheduler.class);

	@Value("${url.bebase.service}")
	private String urlBeBaseLoginService;

	@Value("${batch.password}")
	private String batchPassword;

	private final BatchSubscriptionRepository subscriptionRepository;
	private final BatchExecutor batchExecutor;

	public BatchScheduler(BatchSubscriptionRepository subscriptionRepository, BatchExecutor batchExecutor) {
		super();
		this.subscriptionRepository = subscriptionRepository;
		this.batchExecutor = batchExecutor;
	}

	@Scheduled(fixedDelayString = "${batch.scheduler.fixed-delay-ms}")
	public void dispatch() {
		ObjectMapper objectMapper = new ObjectMapper();
		LocalDateTime now = LocalDateTime.now();

		List<BatchSubscription> dueBatches = subscriptionRepository.findByEnabledTrueAndNextRunAtLessThanEqual(now);
		RestTemplate restTemplate = new RestTemplate();
		for (BatchSubscription subscription : dueBatches) {

			String username = "batch." + subscription.getIdIntermediario();
			LoginResponse jwt = null;
			try {
				LoginPojo loginPojo = new LoginPojo();
				loginPojo.setUsername(username);
				loginPojo.setPassword(batchPassword);

				HttpHeaders headers = new HttpHeaders();
				headers.setContentType(MediaType.APPLICATION_JSON);

				HttpEntity<LoginPojo> entity = new HttpEntity<>(loginPojo, headers);
				logger.info("Login URL: {}", urlBeBaseLoginService);
				logger.info("Login body: {}", objectMapper.writeValueAsString(loginPojo));
				ResponseEntity<LoginResponse> loginJwt = restTemplate.postForEntity(urlBeBaseLoginService, entity,
						LoginResponse.class);

				jwt = loginJwt.getBody();
				batchExecutor.execute(subscription, jwt.jwt());
			} catch (RestClientException rce) {
				logger.info("Errore nella chiamata verso batch" + rce);
			} catch (JsonProcessingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}
	}
}
