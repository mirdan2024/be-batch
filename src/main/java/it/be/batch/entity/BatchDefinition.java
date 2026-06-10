package it.be.batch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;



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

	@Enumerated(EnumType.STRING)
	@Column(name = "http_method", nullable = false)
	private HttpMethodType httpMethod;


	private boolean enabled = true;

	public BatchDefinition() {
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

	public enum BatchExecutionStatus {
		RUNNING, SUCCESS, FAILED
	}
}
