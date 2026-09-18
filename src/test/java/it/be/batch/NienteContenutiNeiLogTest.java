package it.be.batch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.common.base.util.ControlloSorgentiLog;

/**
 * Niente contenuti nei log: le regole, e il perche', stanno in {@link ControlloSorgentiLog} (commonBase).
 * Se fallisce, il messaggio dice file, riga e regola violata.
 */
class NienteContenutiNeiLogTest {

	@Test
	@DisplayName("Niente System.out, printStackTrace, ne' corpi o header HTTP nel log a info/warn/error")
	void sorgentiPuliti() throws IOException {
		List<String> violazioni = ControlloSorgentiLog.violazioni(Path.of("src/main/java"));

		assertTrue(violazioni.isEmpty(), "contenuti che finirebbero nei log:\n" + String.join("\n", violazioni));
	}
}
