package it.be.batch.dto;

import java.time.LocalDateTime;

import it.be.batch.entity.BatchDefinition.HttpMethodType;

public class Dtos {

	public record BatchDefinitionRequest(String code, String description, String endpointUrl, HttpMethodType httpMethod,
			Boolean enabled) {
	}

	public record BatchDefinitionResponse(Long id, String code, String description, String endpointUrl,
			HttpMethodType httpMethod, boolean enabled, String ambitoIntermediario) {
	}

	// username/password: credenziali con cui la schedulazione esegue il servizio. In update la password
	// vuota/null lascia invariata quella esistente (non la si reinvia a ogni modifica).
	// startAt: data/ora di partenza (decorrenza) in formato ISO locale "yyyy-MM-ddTHH:mm". Opzionale:
	// null/vuoto = nessun vincolo (parte alla prossima occorrenza del cron).
	// jobSuccessivo: CODICE della definition da lanciare a fine esecuzione riuscita (concatenamento).
	// Vuoto/null = nessun seguito.
	public record BatchSubscriptionRequest(Long idIntermediario, String customerName, Long batchDefinitionId,
			String cronExpression, String username, String password, String timezone, Boolean enabled, String paramsJson,
			String bodyJson, Long idUtenteAdmin, String startAt, String jobSuccessivo) {
	}

	// NB: nessun campo password. La password non viene mai restituita in lettura.
	// intermediarioNome = nominativo risolto da idIntermediario (per la UI: autocomplete/dettaglio/lista).
	public record BatchSubscriptionResponse(Long id, Long idIntermediario, Long batchDefinitionId,
			String batchCode, String cronExpression, String username, String timezone, boolean enabled,
			LocalDateTime lastRunAt, LocalDateTime nextRunAt, String paramsJson, String bodyJson, Long idUtenteAdmin,
			LocalDateTime startAt, String intermediarioNome,
			// Elaborazione in corso: c'e' una batch_execution PENDING non ancora conclusa.
			// esecuzioneDa = da quando (per mostrare la durata e capire se e' piantata).
			boolean inEsecuzione, LocalDateTime esecuzioneDa,
			// Codice del lavoro lanciato a fine esecuzione riuscita (concatenamento). Null = nessuno.
			String jobSuccessivo) {
	}
	

	public record BatchExecutionRequest(Long id,String status, String response,Integer response_code) {
	}

	// Pagina di risultati per le liste della UI: righe piu' il TOTALE, che serve alla paginazione per
	// sapere quante pagine esistono (con la sola lista si saprebbe solo che ce n'e' un'altra).
	public record PaginaResponse<T>(java.util.List<T> items, long total, int page, int size) {
	}

	// Riga di storico esecuzione per la UI. durationMs = ended_at - started_at (null se non conclusa).
	// responseBody = JSON di ritorno del servizio (può essere assente).
	public record BatchExecutionResponse(Long id, String status, LocalDateTime startedAt, LocalDateTime endedAt,
			Long durationMs, Integer responseCode, String errorMessage, String responseBody,
			// Telecronaca scritta dal servizio durante l'elaborazione (fasi, record, avanzamento, errori).
			String log) {
	}
	
	public record LoginResponse(String jwt) {};

	// Verifica credenziali della schedulazione: la UI deve provarle PRIMA di poter salvare, cosi' una
	// password sbagliata (o riempita dall'autofill del browser) si scopre subito e non al primo cron.
	public record TestCredentialsRequest(String username, String password) {
	}

	public record TestCredentialsResponse(boolean ok, String message) {
	}

	// Telecronaca dell'elaborazione: il servizio chiamato aggiunge righe di avanzamento e, a fine corsa,
	// chiude lui l'esecuzione con lo stato definitivo. Cosi' be-batch non resta appeso ad aspettare.
	public record BatchLogRequest(String message) {
	}

	public record BatchFinishRequest(String status, String message, String responseBody) {
	}

	// ------------------------------------------------------------------------------------------------
	// SCHEDULAZIONI SFTP
	// ------------------------------------------------------------------------------------------------

	// Come per le batch: in update la password vuota/null lascia invariata quella esistente (non viene
	// mai restituita in lettura, quindi il client non ce l'ha per rimandarla).
	// direzione   = SFTP_TO_STORAGE | STORAGE_TO_SFTP
	// postTransfer= LASCIA | CANCELLA | SPOSTA (in postTransferFolder)
	// startAt     = ISO locale "yyyy-MM-ddTHH:mm" (input datetime-local), opzionale.
	public record SftpScheduleRequest(String nome, Long idIntermediario, String direzione, String sftpHost,
			Integer sftpPort, String sftpUsername, String sftpPassword, String sftpPath, String filePattern,
			String storageIntermediario, String storageType, String storageFolder, String postTransfer,
			String postTransferFolder, String cronExpression, String timezone, Boolean enabled, Long idUtenteAdmin,
			String startAt) {
	}

	// NB: nessun campo password. La password SFTP non viene mai restituita in lettura.
	public record SftpScheduleResponse(Long id, String nome, Long idIntermediario, String intermediarioNome,
			String direzione, String sftpHost, Integer sftpPort, String sftpUsername, String sftpPath,
			String filePattern, String storageIntermediario, String storageType, String storageFolder,
			String postTransfer, String postTransferFolder, String cronExpression, String timezone, boolean enabled,
			LocalDateTime lastRunAt, LocalDateTime nextRunAt, LocalDateTime startAt, Long idUtenteAdmin,
			// Trasferimento in corso (sftp_execution PENDING non conclusa) e da quando.
			boolean inEsecuzione, LocalDateTime esecuzioneDa) {
	}

	// Riga di storico per la UI: stessi campi delle esecuzioni batch piu' i contatori del trasferimento.
	public record SftpExecutionResponse(Long id, String status, LocalDateTime startedAt, LocalDateTime endedAt,
			Long durationMs, Integer fileTrasferiti, Long byteTrasferiti, String errorMessage, String log) {
	}

	// Verifica della connessione SFTP PRIMA del salvataggio (host/porta/utente/password): stessa logica
	// del test credenziali delle batch, cosi' un errore si scopre subito e non al primo cron.
	// filePattern facoltativo: se c'e', la verifica riporta anche quanti file corrisponderebbero OGGI
	// col segnaposto di data gia' risolto (es. LIS_%YYYYMMDD% -> LIS_20260802).
	public record SftpTestRequest(String sftpHost, Integer sftpPort, String sftpUsername, String sftpPassword,
			String sftpPath, String filePattern) {
	}

	public record SftpTestResponse(boolean ok, String message) {
	}
}
