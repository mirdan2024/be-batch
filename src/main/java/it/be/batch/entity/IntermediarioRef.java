package it.be.batch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Vista di SOLA LETTURA sulla tabella intermediari (di proprietà di be-base, stesso db_base). Serve solo
 * a risolvere idIntermediario -> nominativo nella UI delle schedulazioni. be-batch non scrive mai qui e
 * non ha ddl-auto, quindi mappare solo due colonne della tabella è sicuro (le SELECT leggono queste,
 * gli INSERT/UPDATE non avvengono).
 */
@Entity
@Table(name = "intermediari")
public class IntermediarioRef {

	@Id
	@Column(name = "id_intermediario")
	private Long idIntermediario;

	@Column(name = "nominativo")
	private String nominativo;

	public Long getIdIntermediario() {
		return idIntermediario;
	}

	public String getNominativo() {
		return nominativo;
	}
}
