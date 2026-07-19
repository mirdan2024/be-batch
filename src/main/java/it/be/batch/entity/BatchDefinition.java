package it.be.batch.entity;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import it.ai.client.constants.AppConstants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;



/**
 * 
 */
@Entity
@Table(name = "batch_definition")
public class BatchDefinition {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String code;

	private String description;

	@Column(name = "endpoint_url", nullable = false)
	private String endpointUrl;

	// Template del body JSON di default del servizio, catturato all'auto-registrazione
	// (RestServiceRegistryService). Nullable: un GET non ha body, e la creazione manuale non lo richiede;
	// la sottoscrizione può comunque fornire il proprio bodyJson, che è quello effettivamente inviato.
	@Column(name = "body_json", columnDefinition = "TEXT")
	private String bodyJson;

	@Column(name = "data_creazione", nullable = false)
	private LocalDateTime dataCreazione;

	// null = definizione attiva; valorizzata = disattivata (convenzione del workspace).
	@Column(name = "data_cessazione")
	private LocalDateTime dataCessazione;

	@Enumerated(EnumType.STRING)
	@Column(name = "http_method", nullable = false)
	private HttpMethodType httpMethod;


	private boolean enabled = true;

	public BatchDefinition() {
	}


	public LocalDateTime getDataCreazione() {
		return dataCreazione;
	}


	public void setDataCreazione(LocalDateTime dataCreazione) {
		this.dataCreazione = dataCreazione;
	}


	public LocalDateTime getDataCessazione() {
		return dataCessazione;
	}


	public void setDataCessazione(LocalDateTime dataCessazione) {
		this.dataCessazione = dataCessazione;
	}


	public String getBodyJson() {
		return bodyJson;
	}

	public void setBodyJson(String bodyJson) {
		this.bodyJson = bodyJson;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getEndpointUrl() {
		return endpointUrl;
	}

	public void setEndpointUrl(String endpointUrl) {
		this.endpointUrl = endpointUrl;
	}

	public HttpMethodType getHttpMethod() {
		return httpMethod;
	}

	public void setHttpMethod(HttpMethodType httpMethod) {
		this.httpMethod = httpMethod;
	}


	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public enum HttpMethodType {
		GET, POST
	}

}
