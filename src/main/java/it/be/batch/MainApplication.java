package it.be.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.servlet.context.ServletComponentScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// NB: niente @EnableCaching. be-batch non usa il caching (nessun @Cacheable) e non ha
// spring-boot-starter-cache: in Spring Boot 4 @EnableCaching senza un CacheManager fa fallire l'avvio
// (in Boot 3 veniva autoconfigurato un cache manager di default, non più).
@SpringBootApplication
@ServletComponentScan
@EnableScheduling
@ComponentScan(basePackages = { "it.be.batch", "it.common.base" })
public class MainApplication {
	public static void main(String[] args) {
		SpringApplication.run(MainApplication.class, args);
	}
}
