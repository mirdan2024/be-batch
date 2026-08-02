package it.be.batch.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.be.batch.dto.Dtos.SftpExecutionResponse;
import it.be.batch.dto.Dtos.SftpScheduleRequest;
import it.be.batch.dto.Dtos.SftpScheduleResponse;
import it.be.batch.dto.Dtos.SftpTestRequest;
import it.be.batch.dto.Dtos.SftpTestResponse;
import it.be.batch.service.SftpScheduleService;
import it.be.batch.service.SftpScheduler;
import it.be.batch.service.SftpTransferService;

/**
 * API della pagina "Schedulazioni SFTP". Rispecchia una a una quelle delle schedulazioni batch, cosi'
 * la UI e' identica: elenco, dettaglio, storico, test credenziali, salva, esegui ora, stop,
 * blocca/sblocca, elimina.
 */
@RestController
@RequestMapping({ "sftp-schedules", "/api/sftp-schedules" })
public class SftpScheduleController {

	private final SftpScheduleService service;
	private final SftpScheduler scheduler;
	private final SftpTransferService transferService;

	public SftpScheduleController(SftpScheduleService service, SftpScheduler scheduler,
			SftpTransferService transferService) {
		super();
		this.service = service;
		this.scheduler = scheduler;
		this.transferService = transferService;
	}

	@GetMapping
	public List<SftpScheduleResponse> findAll(@RequestParam(required = false) Long customerId) {
		if (customerId != null) {
			return service.findByCustomerId(customerId);
		}
		return service.findAll();
	}

	@GetMapping("/{id}")
	public SftpScheduleResponse findById(@PathVariable Long id) {
		return service.findById(id);
	}

	/** Storico trasferimenti (ultimi 50, decrescente per data di inizio). */
	@GetMapping("/{id}/executions")
	public List<SftpExecutionResponse> executions(@PathVariable Long id) {
		return service.findExecutions(id);
	}

	// Verifica della connessione PRIMA del salvataggio: la UI abilita "Salva" solo se l'esito e' ok.
	@PostMapping("/test-connection")
	public SftpTestResponse testConnection(@RequestBody SftpTestRequest request) {
		return transferService.testConnessione(request.sftpHost(), request.sftpPort(), request.sftpUsername(),
				request.sftpPassword(), request.sftpPath(), request.filePattern());
	}

	// Verifica delle credenziali GIA' salvate (diagnosi, senza reinserirle).
	@PostMapping("/{id}/test-connection")
	public SftpTestResponse testStoredConnection(@PathVariable Long id) {
		return transferService.testConnessioneSalvata(id);
	}

	@PostMapping
	public SftpScheduleResponse create(@RequestBody SftpScheduleRequest request) {
		return service.create(request);
	}

	@PutMapping("/{id}")
	public SftpScheduleResponse update(@PathVariable Long id, @RequestBody SftpScheduleRequest request) {
		return service.update(id, request);
	}

	// Esecuzione UNA TANTUM: parte subito, anche fuori orario (asincrona: esito nello storico).
	@PostMapping("/{id}/esegui")
	public Map<String, String> eseguiOra(@PathVariable Long id) {
		return Map.of("stato", scheduler.eseguiUnaTantum(id));
	}

	// NB: POST (non PATCH/DELETE). Il gateway di routing instrada solo GET/POST/PUT su /**: con
	// PATCH/DELETE la preflight CORS viene bloccata dal browser. Convenzione del resto dell'app.
	@PostMapping("/{id}/stop")
	public Map<String, Object> stop(@PathVariable Long id) {
		return service.interrompi(id);
	}

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
