package it.be.batch.entity;

import java.time.LocalDateTime;

import it.be.batch.entity.BatchDefinition.BatchExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "batch_execution")
public class BatchExecution {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "batch_subscription_id", nullable = false)
	private BatchSubscription batchSubscription;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private BatchExecutionStatus status;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	@Column(name = "ended_at")
	private LocalDateTime endedAt;

	@Column(name = "response_code")
	private Integer responseCode;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	@Column(name = "response_body", columnDefinition = "LONGTEXT")
	private String responseBody;

	public BatchExecution() {
	}

	public Long getId() {
		return id;
	}

	public BatchSubscription getBatchSubscription() {
		return batchSubscription;
	}

	public void setBatchSubscription(BatchSubscription batchSubscription) {
		this.batchSubscription = batchSubscription;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public BatchExecutionStatus getStatus() {
		return status;
	}

	public void setStatus(BatchExecutionStatus status) {
		this.status = status;
	}

	public LocalDateTime getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(LocalDateTime startedAt) {
		this.startedAt = startedAt;
	}

	public LocalDateTime getEndedAt() {
		return endedAt;
	}

	public void setEndedAt(LocalDateTime endedAt) {
		this.endedAt = endedAt;
	}

	public Integer getResponseCode() {
		return responseCode;
	}

	public void setResponseCode(Integer responseCode) {
		this.responseCode = responseCode;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public String getResponseBody() {
		return responseBody;
	}

	public void setResponseBody(String responseBody) {
		this.responseBody = responseBody;
	}
}