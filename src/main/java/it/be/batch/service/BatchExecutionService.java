package it.be.batch.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.be.batch.dto.Dtos.BatchExecutionRequest;
import it.be.batch.entity.BatchExecution;
import it.be.batch.repo.BatchExecutionRepository;

@Service
public class BatchExecutionService {

	private final BatchExecutionRepository repository;


	public BatchExecutionService(BatchExecutionRepository repository) {
		super();
		this.repository = repository;
	}


	@Transactional
	public void update(Long id, BatchExecutionRequest request) {

		BatchExecution entity = repository.findById(id)
				.orElseThrow(() -> new RuntimeException("Esecuzione batch non trovata: " + id));

		entity.setResponseBody(request.response());
		entity.setStatus(request.status());
		entity.setResponseCode(request.response_code());

		repository.save(entity);
	}

}
