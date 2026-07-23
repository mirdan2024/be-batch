package it.be.batch.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

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

	private static ZoneId zoneOf(String timezone) {
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
