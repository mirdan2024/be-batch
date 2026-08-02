package it.be.batch.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;

import it.be.batch.dto.Dtos.BatchExecutionRequest;
import it.be.batch.dto.Dtos.BatchFinishRequest;
import it.be.batch.dto.Dtos.BatchLogRequest;
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

	/**
	 * Aggiunge una riga di avanzamento alla telecronaca dell'esecuzione. La chiama il SERVIZIO che sta
	 * elaborando (l'id lo riceve nell'header {@code idExecution}), cosi' l'operatore vede a che punto e'
	 * senza dover aspettare la fine.
	 */
	@PostMapping("/{id}/log")
	public ResponseEntity<Map<String, Object>> log(@PathVariable Long id, @RequestBody BatchLogRequest request) {
		service.appendLog(id, request.message());
		return ResponseEntity.ok(Map.of("success", true));
	}

	/**
	 * Chiude l'esecuzione con lo stato definitivo (COMPLETED / FAILED / INTERROTTA): e' il servizio a
	 * dichiarare l'esito, non piu' be-batch in base al timeout della chiamata HTTP.
	 */
	@PostMapping("/{id}/finish")
	public ResponseEntity<Map<String, Object>> finish(@PathVariable Long id, @RequestBody BatchFinishRequest request) {
		service.finish(id, request.status(), request.message(), request.responseBody());
		return ResponseEntity.ok(Map.of("success", true));
	}

}