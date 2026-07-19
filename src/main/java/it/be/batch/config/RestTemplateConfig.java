package it.be.batch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

	// Timeout di CONNESSIONE: 30s. Se il servizio è irraggiungibile la chiamata fallisce in fretta
	// (-> FAILED) invece di restare "in esecuzione" per minuti. Override con value.connect.timeout.
	@Value("${value.connect.timeout:30000}")
	private int valueConnectTimeout;

	// Timeout di LETTURA: 10 min di default, per non tagliare i job che rispondono lentamente. Se i tuoi
	// servizi rispondono in fretta, abbassalo (es. 60000 = 1 min) con value.read.timeout.
	@Value("${value.read.timeout:600000}")
	private int valueReadTimeout;

	@Bean("RestTimeout") // Il bean si chiama UFFICIALMENTE "RestTimeout"
	public RestTemplate restTemplate() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

		// Timeout di connessione (in millisecondi)
		factory.setConnectTimeout(valueConnectTimeout); // 10 minuti

		// Timeout di lettura (in millisecondi)
		factory.setReadTimeout(valueReadTimeout); // 10 minuti

		return new RestTemplate(factory);
	}
}
