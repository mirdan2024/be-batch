package it.be.batch.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "batch_subscription")
public class BatchSubscription {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "customer_id", nullable = false)
	private Long customerId;

	@Column(name = "customer_name")
	private String customerName;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "batch_definition_id", nullable = false)
	private BatchDefinition batchDefinition;

	@Column(name = "cron_expression", nullable = false)
	private String cronExpression;

	private String timezone = "Europe/Rome";

	private boolean enabled = true;

	@Column(name = "last_run_at")
	private LocalDateTime lastRunAt;

	@Column(name = "next_run_at")
	private LocalDateTime nextRunAt;

	@Column(name = "params_json", columnDefinition = "TEXT")
	private String paramsJson;

	@Column(name = "body_json", columnDefinition = "TEXT")
	private String bodyJson;

	public BatchSubscription() {
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getCustomerId() {
		return customerId;
	}

	public void setCustomerId(Long customerId) {
		this.customerId = customerId;
	}

	public String getCustomerName() {
		return customerName;
	}

	public void setCustomerName(String customerName) {
		this.customerName = customerName;
	}

	public BatchDefinition getBatchDefinition() {
		return batchDefinition;
	}

	public void setBatchDefinition(BatchDefinition batchDefinition) {
		this.batchDefinition = batchDefinition;
	}

	public String getCronExpression() {
		return cronExpression;
	}

	public void setCronExpression(String cronExpression) {
		this.cronExpression = cronExpression;
	}

	public String getTimezone() {
		return timezone;
	}

	public void setTimezone(String timezone) {
		this.timezone = timezone;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public LocalDateTime getLastRunAt() {
		return lastRunAt;
	}

	public void setLastRunAt(LocalDateTime lastRunAt) {
		this.lastRunAt = lastRunAt;
	}

	public LocalDateTime getNextRunAt() {
		return nextRunAt;
	}

	public void setNextRunAt(LocalDateTime nextRunAt) {
		this.nextRunAt = nextRunAt;
	}

	public String getParamsJson() {
		return paramsJson;
	}

	public void setParamsJson(String paramsJson) {
		this.paramsJson = paramsJson;
	}

	public String getBodyJson() {
		return bodyJson;
	}

	public void setBodyJson(String bodyJson) {
		this.bodyJson = bodyJson;
	}
}