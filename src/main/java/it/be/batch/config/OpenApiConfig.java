package it.be.batch.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
public class OpenApiConfig {

	@Value("${server.servlet.context-path:}")
	private String contextPath;

	@Bean
	public OpenAPI customOpenAPI() {
		return new OpenAPI().servers(List.of(new Server().url(contextPath)));
	}
}
