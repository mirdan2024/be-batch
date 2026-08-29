package it.be.batch.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.support.CronExpression;

/**
 * Calcolo della prossima esecuzione di una sottoscrizione batch a partire dall'espressione cron e dal
 * fuso orario. Unica implementazione condivisa da BatchScheduler/BatchExecutor e BatchSubscriptionService:
 * prima esisteva duplicata in due classi, e una delle due ignorava il fuso.
 */
public final class CronScheduleUtil {

	private CronScheduleUtil() {
	}

	// Prossima occorrenza del cron NEL fuso della sottoscrizione: senza, un cron "alle 2:00" verrebbe
	// valutato nel fuso del server, sfalsando l'orario per intermediari in timezone diverse. Il risultato
	// è un LocalDateTime nello stesso fuso, coerente col confronto in dispatch()
	// (findByEnabledTrueAndNextRunAtLessThanEqual). Ritorna null se il cron non ha occorrenze future.
	public static LocalDateTime nextRun(String cronExpression, String timezone) {
		return nextRun(cronExpression, timezone, null);
	}

	// Come sopra, ma non prima della decorrenza startAt (interpretata nel fuso della sottoscrizione).
	// Se startAt è futura si parte da lì (inclusa: -1ns per considerare un'occorrenza esattamente a
	// startAt); se è null o passata si parte da adesso, così non si schedula mai nel passato.
	public static LocalDateTime nextRun(String cronExpression, String timezone, LocalDateTime startAt) {
		// Sottoscrizione "manuale" (nessun cron): nessuna esecuzione automatica -> next_run_at null, cosi'
		// lo scheduler (findByEnabledTrueAndNextRunAtLessThanEqual) non la seleziona mai.
		if (cronExpression == null || cronExpression.isBlank()) {
			return null;
		}
		CronExpression cron = CronExpression.parse(cronExpression);
		ZoneId zone = zoneOf(timezone);
		ZonedDateTime now = ZonedDateTime.now(zone);
		ZonedDateTime base = now;
		if (startAt != null) {
			ZonedDateTime start = startAt.atZone(zone);
			base = start.isAfter(now) ? start.minusNanos(1) : now;
		}
		ZonedDateTime next = cron.next(base);
		return (next != null) ? next.toLocalDateTime() : null;
	}

	/**
	 * Tutte le occorrenze di un cron in una finestra, per il calendario delle schedulazioni.
	 *
	 * <p>
	 * Usa lo STESSO {@link CronExpression} di {@link #nextRun}, che e' quello con cui lo scheduler
	 * decide davvero quando far partire un job. Espandere il cron altrove — nel front-end, con un
	 * parser diverso — produrrebbe un calendario che col tempo racconta orari che non succedono: e'
	 * esattamente il tipo di seconda verita' che questo metodo esiste per evitare.
	 * </p>
	 *
	 * <p>
	 * Il tetto {@code max} non e' prudenza: un cron al secondo genererebbe 86.400 occorrenze al giorno.
	 * Quando scatta, il chiamante deve dirlo — un calendario troncato in silenzio si legge come un
	 * calendario completo.
	 * </p>
	 *
	 * @param startAt decorrenza della sottoscrizione: prima di quella non parte nulla
	 * @return le occorrenze nel fuso della schedulazione, in ordine; vuota se il cron manca o non e'
	 *         valido
	 */
	public static List<LocalDateTime> occorrenze(String cronExpression, String timezone, LocalDateTime startAt,
			LocalDateTime da, LocalDateTime a, int max) {

		List<LocalDateTime> out = new ArrayList<>();
		if (cronExpression == null || cronExpression.isBlank() || da == null || a == null || !da.isBefore(a)) {
			return out;
		}
		CronExpression cron;
		try {
			cron = CronExpression.parse(cronExpression);
		} catch (Exception e) {
			// Un cron non valido e' gia' un problema suo: qui si tace e non si mostrano occorrenze,
			// invece di far fallire l'intero calendario per una riga scritta male.
			return out;
		}
		ZoneId zone = zoneOf(timezone);
		// Si parte dall'istante PRIMA della finestra: cron.next() e' strettamente successivo, quindi
		// partendo da 'da' si perderebbe un'occorrenza che cade esattamente sul primo istante.
		ZonedDateTime cursore = da.atZone(zone).minusNanos(1);
		ZonedDateTime fine = a.atZone(zone);
		ZonedDateTime inizio = (startAt != null) ? startAt.atZone(zone) : null;

		while (out.size() < max) {
			ZonedDateTime prossima = cron.next(cursore);
			if (prossima == null || !prossima.isBefore(fine)) {
				break;
			}
			if (inizio == null || !prossima.isBefore(inizio)) {
				out.add(prossima.toLocalDateTime());
			}
			cursore = prossima;
		}
		return out;
	}

	// Fuso della schedulazione, con ricaduta sul fuso del server se assente o non valido. Pubblico:
	// serve anche ai trasferimenti SFTP, che risolvono i segnaposto di data (%YYYYMMDD%) nel fuso
	// della schedulazione e non in quello del server.
	public static ZoneId zoneOf(String timezone) {
		if (timezone == null || timezone.isBlank()) {
			return ZoneId.systemDefault();
		}
		try {
			return ZoneId.of(timezone);
		} catch (Exception e) {
			return ZoneId.systemDefault();
		}
	}
}
