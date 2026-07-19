package it.be.batch.dto;

import java.time.LocalDateTime;

import it.be.batch.entity.BatchDefinition.HttpMethodType;

public class Dtos {

	public record BatchDefinitionRequest(String code, String description, String endpointUrl, HttpMethodType httpMethod,
			Boolean enabled) {
	}

	public record BatchDefinitionResponse(Long id, String code, String description, String endpointUrl,
			HttpMethodType httpMethod, boolean enabled) {
	}

	// username/password: credenziali con cui la schedulazione esegue il servizio. In update la password
	// vuota/null lascia invariata quella esistente (non la si reinvia a ogni modifica).
	// startAt: data/ora di partenza (decorrenza) in formato ISO locale "yyyy-MM-ddTHH:mm". Opzionale:
	// null/vuoto = nessun vincolo (parte alla prossima occorrenza del cron).
	public record BatchSubscriptionRequest(Long idIntermediario, String customerName, Long batchDefinitionId,
			String cronExpression, String username, String password, String timezone, Boolean enabled, String paramsJson,
			String bodyJson, Long idUtenteAdmin, String startAt) {
	}

	// NB: nessun campo password. La password non viene mai restituita in lettura.
	public record BatchSubscriptionResponse(Long id, Long idIntermediario, Long batchDefinitionId,
			String batchCode, String cronExpression, String username, String timezone, boolean enabled,
			LocalDateTime lastRunAt, LocalDateTime nextRunAt, String paramsJson, String bodyJson, Long idUtenteAdmin,
			LocalDateTime startAt) {
	}
	

	public record BatchExecutionRequest(Long id,String status, String response,Integer response_code) {
	}

	// Riga di storico esecuzione per la UI. durationMs = ended_at - started_at (null se non conclusa).
	// responseBody = JSON di ritorno del servizio (può essere assente).
	public record BatchExecutionResponse(Long id, String status, LocalDateTime startedAt, LocalDateTime endedAt,
			Long durationMs, Integer responseCode, String errorMessage, String responseBody) {
	}
	
	public record LoginResponse(String jwt) {};
}
