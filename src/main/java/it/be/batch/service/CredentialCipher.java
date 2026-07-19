package it.be.batch.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * Cifratura reversibile delle password di servizio delle schedulazioni, così non finiscono in chiaro a
 * DB. Reversibile (non un hash) perché il batch deve ripresentare la password al login di be-base.
 * Chiave e salt vengono da configurazione (batch.credentials.*): NON committare valori reali, passarli
 * come variabili d'ambiente in produzione.
 */
@Component
public class CredentialCipher {

	private final TextEncryptor encryptor;

	public CredentialCipher(@Value("${batch.credentials.secret}") String secret,
			@Value("${batch.credentials.salt}") String salt) {
		// delux = AES-256 GCM; salt deve essere una stringa esadecimale.
		this.encryptor = Encryptors.delux(secret, salt);
	}

	public String encrypt(String plain) {
		return (plain == null) ? null : encryptor.encrypt(plain);
	}

	public String decrypt(String encrypted) {
		return (encrypted == null) ? null : encryptor.decrypt(encrypted);
	}
}
