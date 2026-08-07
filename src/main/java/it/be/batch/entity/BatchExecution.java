package it.be.batch.entity;

import java.time.LocalDateTime;

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

	@Column(nullable = false)
	private String status;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	/**
	 * Ultimo segno di vita del servizio: lo aggiorna ogni riga di telecronaca.
	 * <p>
	 * E' quello che guarda la rete di sicurezza sulle esecuzioni piantate. Prima guardava
	 * {@code startedAt}, cioe' la DURATA: un'elaborazione piu' lunga della soglia veniva chiusa come
	 * fallita anche mentre lavorava — e il caricamento delle liste societarie, sui volumi reali, dura
	 * anche un giorno.
	 */
	@Column(name = "ultimo_aggiornamento")
	private LocalDateTime ultimoAggiornamento;

	@Column(name = "ended_at")
	private LocalDateTime endedAt;

	@Column(name = "response_code")
	private Integer responseCode;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	@Column(name = "response_body", columnDefinition = "LONGTEXT")
	private String responseBody;

	/** Telecronaca dell'elaborazione, scritta dal servizio chiamato riga per riga (vedi sql/11). */
	@Column(name = "log", columnDefinition = "LONGTEXT")
	private String log;

	public String getLog() {
		return log;
	}

	public void setLog(String log) {
		this.log = log;
	}

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


	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public LocalDateTime getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(LocalDateTime startedAt) {
		this.startedAt = startedAt;
	}

	public LocalDateTime getUltimoAggiornamento() {
		return ultimoAggiornamento;
	}

	public void setUltimoAggiornamento(LocalDateTime ultimoAggiornamento) {
		this.ultimoAggiornamento = ultimoAggiornamento;
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