package it.be.batch.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.be.batch.dto.Dtos.BatchDefinitionRequest;
import it.be.batch.dto.Dtos.BatchDefinitionResponse;
import it.be.batch.service.BatchDefinitionService;

@RestController
@RequestMapping("/api/batch-definitions")
public class BatchDefinitionController {

	private final BatchDefinitionService service;

	public BatchDefinitionController(BatchDefinitionService service) {
		super();
		this.service = service;
	}

	@GetMapping
	public List<BatchDefinitionResponse> findAll() {
		return service.findAll();
	}

	@GetMapping("/{id}")
	public BatchDefinitionResponse findById(@PathVariable Long id) {
		return service.findById(id);
	}

	@PostMapping
	public BatchDefinitionResponse create(@RequestBody BatchDefinitionRequest request) {
		return service.create(request);
	}

	@PutMapping("/{id}")
	public BatchDefinitionResponse update(@PathVariable Long id, @RequestBody BatchDefinitionRequest request) {
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		service.delete(id);
	}
}