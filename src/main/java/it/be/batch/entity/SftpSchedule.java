package it.be.batch.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Schedulazione di un trasferimento SFTP (DDL in {@code sql/14_sftp_schema.sql}).
 * <p>
 * Funziona come una schedulazione batch (cron, decorrenza, blocco/sblocco, "esegui ora", storico con
 * telecronaca, interruzione), ma invece di chiamare un endpoint HTTP muove file tra un server SFTP e
 * be-storage, nelle due direzioni.
 * <p>
 * La password SFTP e' CIFRATA a riposo con lo stesso {@code CredentialCipher} delle schedulazioni
 * batch e non viene MAI restituita in lettura dalle API.
 */
@Entity
@Table(name = "sftp_schedule")
public class SftpSchedule {

	/** Direzione del trasferimento. */
	public static final String DIR_SFTP_TO_STORAGE = "SFTP_TO_STORAGE";
	public static final String DIR_STORAGE_TO_SFTP = "STORAGE_TO_SFTP";

	/** Cosa fare del file di ORIGINE dopo un trasferimento riuscito. */
	public static final String POST_LASCIA = "LASCIA";
	public static final String POST_CANCELLA = "CANCELLA";
	public static final String POST_SPOSTA = "SPOSTA";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String nome;

	@Column(name = "id_intermediario", nullable = false)
	private Long idIntermediario;

	@Column(nullable = false)
	private String direzione;

	@Column(name = "sftp_host", nullable = false)
	private String sftpHost;

	@Column(name = "sftp_port", nullable = false)
	private Integer sftpPort;

	@Column(name = "sftp_username", nullable = false)
	private String sftpUsername;

	@Column(name = "sftp_password_enc", nullable = false, columnDefinition = "TEXT")
	private String sftpPasswordEnc;

	/** Cartella remota: origine (SFTP -> storage) o destinazione (storage -> SFTP). */
	@Column(name = "sftp_path", nullable = false)
	private String sftpPath;

	/** Glob sul nome file (es. {@code *.csv}). Vuoto/null = tutti i file della cartella di origine. */
	@Column(name = "file_pattern")
	private String filePattern;

	@Column(name = "storage_intermediario", nullable = false)
	private String storageIntermediario;

	@Column(name = "storage_type", nullable = false)
	private String storageType;

	@Column(name = "storage_folder", nullable = false)
	private String storageFolder;

	/** LASCIA | CANCELLA | SPOSTA (in {@link #postTransferFolder}). */
	@Column(name = "post_transfer", nullable = false)
	private String postTransfer;

	@Column(name = "post_transfer_folder")
	private String postTransferFolder;

	/** null/vuoto = solo "Esegui ora" (schedulazione manuale), come per le batch. */
	@Column(name = "cron_expression")
	private String cronExpression;

	@Column(nullable = false)
	private String timezone;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "last_run_at")
	private LocalDateTime lastRunAt;

	@Column(name = "next_run_at")
	private LocalDateTime nextRunAt;

	@Column(name = "start_at")
	private LocalDateTime startAt;

	@Column(name = "id_utente_admin")
	private Long idUtenteAdmin;

	@Column(name = "data_creazione", nullable = false)
	private LocalDateTime dataCreazione;

	@Column(name = "data_cessazione")
	private LocalDateTime dataCessazione;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getNome() {
		return nome;
	}

	public void setNome(String nome) {
		this.nome = nome;
	}

	public Long getIdIntermediario() {
		return idIntermediario;
	}

	public void setIdIntermediario(Long idIntermediario) {
		this.idIntermediario = idIntermediario;
	}

	public String getDirezione() {
		return direzione;
	}

	public void setDirezione(String direzione) {
		this.direzione = direzione;
	}

	public String getSftpHost() {
		return sftpHost;
	}

	public void setSftpHost(String sftpHost) {
		this.sftpHost = sftpHost;
	}

	public Integer getSftpPort() {
		return sftpPort;
	}

	public void setSftpPort(Integer sftpPort) {
		this.sftpPort = sftpPort;
	}

	public String getSftpUsername() {
		return sftpUsername;
	}

	public void setSftpUsername(String sftpUsername) {
		this.sftpUsername = sftpUsername;
	}

	public String getSftpPasswordEnc() {
		return sftpPasswordEnc;
	}

	public void setSftpPasswordEnc(String sftpPasswordEnc) {
		this.sftpPasswordEnc = sftpPasswordEnc;
	}

	public String getSftpPath() {
		return sftpPath;
	}

	public void setSftpPath(String sftpPath) {
		this.sftpPath = sftpPath;
	}

	public String getFilePattern() {
		return filePattern;
	}

	public void setFilePattern(String filePattern) {
		this.filePattern = filePattern;
	}

	public String getStorageIntermediario() {
		return storageIntermediario;
	}

	public void setStorageIntermediario(String storageIntermediario) {
		this.storageIntermediario = storageIntermediario;
	}

	public String getStorageType() {
		return storageType;
	}

	public void setStorageType(String storageType) {
		this.storageType = storageType;
	}

	public String getStorageFolder() {
		return storageFolder;
	}

	public void setStorageFolder(String storageFolder) {
		this.storageFolder = storageFolder;
	}

	public String getPostTransfer() {
		return postTransfer;
	}

	public void setPostTransfer(String postTransfer) {
		this.postTransfer = postTransfer;
	}

	public String getPostTransferFolder() {
		return postTransferFolder;
	}

	public void setPostTransferFolder(String postTransferFolder) {
		this.postTransferFolder = postTransferFolder;
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

	public LocalDateTime getStartAt() {
		return startAt;
	}

	public void setStartAt(LocalDateTime startAt) {
		this.startAt = startAt;
	}

	public Long getIdUtenteAdmin() {
		return idUtenteAdmin;
	}

	public void setIdUtenteAdmin(Long idUtenteAdmin) {
		this.idUtenteAdmin = idUtenteAdmin;
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
}
