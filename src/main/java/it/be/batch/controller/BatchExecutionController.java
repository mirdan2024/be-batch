package it.be.batch.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.be.batch.dto.Dtos.BatchExecutionRequest;
import it.be.batch.service.BatchExecutionService;

@RestController
@RequestMapping("/batch-execution")
public class BatchExecutionController {

	private final BatchExecutionService service;

	public BatchExecutionController(BatchExecutionService service) {
		super();
		this.service = service;
	}


	@PutMapping("/{id}")
	public ResponseEntity<String> update(@RequestBody BatchExecutionRequest request) {
		service.update(request);
		
		return ResponseEntity.ok("ok");
	}

}