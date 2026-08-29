package it.be.batch.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.be.batch.dto.Dtos.CalendarioResponse;
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

	/** Storico trasferimenti, decrescente per data di inizio e PAGINATO (pagina 1-based). */
	/**
	 * Calendario dei trasferimenti previsti fra due istanti.
	 *
	 * <p>
	 * Comprende le schedulazioni bloccate: sono spente adesso, ma riaccenderle e' un attimo e chi
	 * pianifica deve vedere lo slot che tornerebbero a occupare.
	 * </p>
	 */
	@GetMapping("/calendario")
	public CalendarioResponse calendario(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime da,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime a,
			@RequestParam(required = false) Long idIntermediario) {
		return service.calendario(da, a, idIntermediario);
	}

	@GetMapping("/{id}/executions")
	public it.be.batch.dto.Dtos.PaginaResponse<SftpExecutionResponse> executions(@PathVariable Long id,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
		return service.findExecutions(id, page, size);
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

	// Frecce dell'elenco: scambia di posto due schedulazioni. `conId` e' la riga che si vede sopra o
	// sotto — la sceglie la pagina, perche' con un filtro attivo non coincide con la successiva in
	// assoluto. Come su /batch-subscriptions/{id}/sposta.
	@PostMapping("/{id}/sposta")
	public void sposta(@PathVariable Long id, @RequestParam Long conId) {
		service.scambiaOrdine(id, conId);
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
