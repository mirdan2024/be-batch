package it.be.batch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

	@Value("${value.connect.timeout:600000}")
	private int valueConnectTimeout;

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
