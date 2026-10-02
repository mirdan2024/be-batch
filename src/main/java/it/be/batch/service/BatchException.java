package it.be.batch.service;

/**
 * Errore applicativo di be-batch (schedulazione o definizione non trovata, dati non validi...).
 * <p>
 * Estende {@link RuntimeException} di proposito: prende il posto delle {@code RuntimeException}
 * generiche lanciate prima, e al controller deve arrivare esattamente come loro — il gestore globale
 * di commonBase la tratta come qualunque eccezione non gestita (500, messaggio generico).
 */
public class BatchException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public BatchException(String message) {
		super(message);
	}

	public BatchException(String message, Throwable cause) {
		super(message, cause);
	}
}
