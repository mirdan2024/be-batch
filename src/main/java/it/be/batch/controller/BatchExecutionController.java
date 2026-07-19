package it.be.batch.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.be.batch.dto.Dtos.BatchExecutionRequest;
import it.be.batch.service.BatchExecutionService;

@RestController
@RequestMapping({ "batch-execution", "/api/batch-execution" })
public class BatchExecutionController {

	private final BatchExecutionService service;

	public BatchExecutionController(BatchExecutionService service) {
		super();
		this.service = service;
	}


	// L'id dell'esecuzione è nel path (fonte di verità), non nel body: è l'idExecution che il servizio
	// target riceve nell'header e usa per il callback di aggiornamento esito.
	@PutMapping("/{id}")
	public ResponseEntity<String> update(@PathVariable Long id, @RequestBody BatchExecutionRequest request) {
		service.update(id, request);

		return ResponseEntity.ok("ok");
	}

}