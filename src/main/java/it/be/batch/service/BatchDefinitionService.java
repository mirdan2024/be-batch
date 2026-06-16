package it.be.batch.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.be.batch.dto.Dtos.BatchDefinitionRequest;
import it.be.batch.dto.Dtos.BatchDefinitionResponse;
import it.be.batch.entity.BatchDefinition;
import it.be.batch.repo.BatchDefinitionRepository;

@Service
public class BatchDefinitionService {

    private final BatchDefinitionRepository repository;
    
//    

    public List<BatchDefinitionResponse> findActiveDefinitions() {
        return repository.findActiveDefinitions()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public BatchDefinitionService(BatchDefinitionRepository repository) {
	super();
	this.repository = repository;
}

	public BatchDefinitionResponse findById(Long id) {
        BatchDefinition entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Batch definition non trovato"));

        return toResponse(entity);
    }

    @Transactional
    public BatchDefinitionResponse create(BatchDefinitionRequest request) {

        if (repository.existsByCode(request.code())) {
            throw new RuntimeException("Codice batch già esistente: " + request.code());
        }

        BatchDefinition entity = new BatchDefinition();
        entity.setCode(request.code());
        entity.setDescription(request.description());
        entity.setEndpointUrl(request.endpointUrl());
        entity.setHttpMethod(request.httpMethod());
        entity.setEnabled(request.enabled() == null || request.enabled());

        return toResponse(repository.save(entity));
    }

    @Transactional
    public BatchDefinitionResponse update(Long id, BatchDefinitionRequest request) {

        BatchDefinition entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Batch definition non trovato"));

        entity.setDescription(request.description());
        entity.setEndpointUrl(request.endpointUrl());
        entity.setHttpMethod(request.httpMethod());

        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }

        return toResponse(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
    	BatchDefinition entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Batch definition non trovato"));
    	
    	entity.setEnabled(false);
    	entity.setDataCessazione(LocalDateTime.now());
    	repository.save(entity);
    }

    private BatchDefinitionResponse toResponse(BatchDefinition entity) {
        return new BatchDefinitionResponse(
                entity.getId(),
                entity.getCode(),
                entity.getDescription(),
                entity.getEndpointUrl(),
                entity.getHttpMethod(),
                entity.isEnabled()
        );
    }
}
