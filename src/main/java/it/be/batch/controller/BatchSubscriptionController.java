package it.be.batch.controller;

import java.util.List;

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

import it.be.batch.dto.Dtos.BatchExecutionResponse;
import it.be.batch.dto.Dtos.CalendarioResponse;
import it.be.batch.dto.Dtos.BatchSubscriptionRequest;
import it.be.batch.dto.Dtos.BatchSubscriptionResponse;
import it.be.batch.dto.Dtos.TestCredentialsRequest;
import it.be.batch.dto.Dtos.TestCredentialsResponse;
import it.be.batch.service.BatchSubscriptionService;

@RestController
@RequestMapping({ "batch-subscriptions", "/api/batch-subscriptions" })
public class BatchSubscriptionController {

    private final BatchSubscriptionService service;
    private final it.be.batch.service.BatchScheduler batchScheduler;

    public BatchSubscriptionController(BatchSubscriptionService service,
            it.be.batch.service.BatchScheduler batchScheduler) {
		super();
		this.service = service;
		this.batchScheduler = batchScheduler;
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

    /**
     * Calendario delle partenze previste fra due istanti.
     *
     * <p>
     * Comprende le schedulazioni bloccate, che sul calendario servono: sono spente adesso ma
     * riaccenderle e' un attimo, e chi pianifica deve vedere lo slot che tornerebbero a occupare.
     * </p>
     *
     * @param da   inizio finestra (ISO, es. 2026-09-01T00:00:00)
     * @param a    fine finestra, esclusa
     * @param idIntermediario opzionale, per restringere a un cliente
     */
    @GetMapping("/calendario")
    public CalendarioResponse calendario(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime da,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime a,
            @RequestParam(required = false) Long idIntermediario) {
        return service.calendario(da, a, idIntermediario);
    }

    // Storico esecuzioni della sottoscrizione, decrescente per data di inizio e PAGINATO (pagina
    // 1-based). I default riproducono la prima schermata di prima; la modale usa i suoi controlli.
    @GetMapping("/{id}/executions")
    public it.be.batch.dto.Dtos.PaginaResponse<BatchExecutionResponse> executions(@PathVariable Long id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
        return service.findExecutions(id, page, size);
    }

    // Verifica delle credenziali PRIMA del salvataggio: la UI abilita "Salva" solo se l'esito e' ok.
    // Stesso login che fara' lo scheduler (be-base /doLoginBatch), cosi' l'errore emerge subito.
    @PostMapping("/test-credentials")
    public TestCredentialsResponse testCredentials(@RequestBody TestCredentialsRequest request) {
        return service.testCredentials(request.username(), request.password());
    }

    // Verifica delle credenziali GIA' salvate su una schedulazione (diagnosi, senza reinserirle).
    @PostMapping("/{id}/test-credentials")
    public TestCredentialsResponse testStoredCredentials(@PathVariable Long id) {
        return service.testStoredCredentials(id);
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

    // Esecuzione UNA TANTUM: parte subito, anche fuori orario (asincrona: esito nello storico).
    // Risponde {"stato": "AVVIATA" | "NON_TROVATA" | "DEFINIZIONE_DISATTIVATA"}.
    @PostMapping("/{id}/esegui")
    public java.util.Map<String, String> eseguiOra(@PathVariable Long id) {
        return java.util.Map.of("stato", batchScheduler.eseguiUnaTantum(id));
    }

    // NB: POST (non PATCH/DELETE). Il gateway di routing instrada solo GET/POST/PUT su /**: con PATCH/DELETE
    // la preflight CORS viene bloccata dal browser ("Failed to fetch"). Convenzione del resto dell'app.
    // Interruzione dell'elaborazione in corso: chiude la riga di batch_execution e, se la definizione
    // dichiara uno stop-url, invia lo stop al servizio chiamato (che si ferma in modo cooperativo).
    @PostMapping("/{id}/stop")
    public java.util.Map<String, Object> stop(@PathVariable Long id) {
        return service.interrompi(id);
    }

    // Frecce dell'elenco: scambia di posto due schedulazioni. `conId` e' la riga che si vede sopra o
    // sotto — la sceglie la pagina, perche' con un filtro attivo non coincide con la successiva in
    // assoluto. POST e non PATCH: il gateway instrada solo GET/POST/PUT (vedi nota sopra).
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