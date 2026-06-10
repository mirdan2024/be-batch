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

	public record BatchSubscriptionRequest(Long customerId, String customerName, Long batchDefinitionId,
			String cronExpression, String timezone, Boolean enabled, String paramsJson, String bodyJson) {
	}

	public record BatchSubscriptionResponse(Long id, Long customerId, Long batchDefinitionId,
			String batchCode, String cronExpression, String timezone, boolean enabled, LocalDateTime lastRunAt,
			LocalDateTime nextRunAt, String paramsJson, String bodyJson) {
	}
}
