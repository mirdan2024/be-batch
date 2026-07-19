package it.be.batch.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.be.batch.dto.Dtos.BatchExecutionResponse;
import it.be.batch.dto.Dtos.BatchSubscriptionRequest;
import it.be.batch.dto.Dtos.BatchSubscriptionResponse;
import it.be.batch.service.BatchSubscriptionService;

@RestController
@RequestMapping({ "batch-subscriptions", "/api/batch-subscriptions" })
public class BatchSubscriptionController {

    private final BatchSubscriptionService service;
    
    

    public BatchSubscriptionController(BatchSubscriptionService service) {
		super();
		this.service = service;
	}

	@GetMapping
    public List<BatchSubscriptionResponse> findAll(
            @RequestParam(required = false) Long customerId
    ) {
        if (customerId != null) {
            return service.findByCustomerId(customerId);
        }

        return service.findAll();
    }

    @GetMapping("/{id}")
    public BatchSubscriptionResponse findById(@PathVariable Long id) {
        return service.findById(id);
    }

    // Storico esecuzioni della sottoscrizione (ultime 50, decrescente per data di inizio).
    @GetMapping("/{id}/executions")
    public List<BatchExecutionResponse> executions(@PathVariable Long id) {
        return service.findExecutions(id);
    }

    @PostMapping
    public BatchSubscriptionResponse create(@RequestBody BatchSubscriptionRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public BatchSubscriptionResponse update(
            @PathVariable Long id,
            @RequestBody BatchSubscriptionRequest request
    ) {
        return service.update(id, request);
    }

    // NB: POST (non PATCH/DELETE). Il gateway di routing instrada solo GET/POST/PUT su /**: con PATCH/DELETE
    // la preflight CORS viene bloccata dal browser ("Failed to fetch"). Convenzione del resto dell'app.
    @PostMapping("/{id}/enable")
    public void enable(@PathVariable Long id) {
        service.enable(id);
    }

    @PostMapping("/{id}/disable")
    public void disable(@PathVariable Long id) {
        service.disable(id);
    }

    @PostMapping("/{id}/delete")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}