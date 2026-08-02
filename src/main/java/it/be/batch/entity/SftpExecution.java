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

/**
 * Esecuzione di una schedulazione SFTP, con la "telecronaca" file per file.
 * <p>
 * A differenza delle esecuzioni batch (dove il log lo scrive il servizio remoto via HTTP), qui il
 * trasferimento gira dentro be-batch: il log lo scrive direttamente {@code SftpTransferService}.
 */
@Entity
@Table(name = "sftp_execution")
public class SftpExecution {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "sftp_schedule_id", nullable = false)
	private SftpSchedule sftpSchedule;

	/** PENDING | COMPLETED | FAILED | INTERROTTA */
	@Column(nullable = false)
	private String status;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	@Column(name = "ended_at")
	private LocalDateTime endedAt;

	@Column(name = "file_trasferiti")
	private Integer fileTrasferiti;

	@Column(name = "byte_trasferiti")
	private Long byteTrasferiti;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	@Column(name = "log", columnDefinition = "LONGTEXT")
	private String log;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public SftpSchedule getSftpSchedule() {
		return sftpSchedule;
	}

	public void setSftpSchedule(SftpSchedule sftpSchedule) {
		this.sftpSchedule = sftpSchedule;
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

	public LocalDateTime getEndedAt() {
		return endedAt;
	}

	public void setEndedAt(LocalDateTime endedAt) {
		this.endedAt = endedAt;
	}

	public Integer getFileTrasferiti() {
		return fileTrasferiti;
	}

	public void setFileTrasferiti(Integer fileTrasferiti) {
		this.fileTrasferiti = fileTrasferiti;
	}

	public Long getByteTrasferiti() {
		return byteTrasferiti;
	}

	public void setByteTrasferiti(Long byteTrasferiti) {
		this.byteTrasferiti = byteTrasferiti;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public String getLog() {
		return log;
	}

	public void setLog(String log) {
		this.log = log;
	}
}
