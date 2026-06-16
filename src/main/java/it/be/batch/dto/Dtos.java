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

	public record BatchSubscriptionRequest(Long idIntermediario, String customerName, Long batchDefinitionId,
			String cronExpression, String timezone, Boolean enabled, String paramsJson, String bodyJson,Long idUtenteAdmin) {
	}

	public record BatchSubscriptionResponse(Long id, Long idIntermediario, Long batchDefinitionId,
			String batchCode, String cronExpression, String timezone, boolean enabled, LocalDateTime lastRunAt,
			LocalDateTime nextRunAt, String paramsJson, String bodyJson,Long idUtenteAdmin) {
	}
	

	public record BatchExecutionRequest(Long id,String status, String response,Integer response_code) {
	}
	
	public record LoginResponse(String jwt) {};
}
