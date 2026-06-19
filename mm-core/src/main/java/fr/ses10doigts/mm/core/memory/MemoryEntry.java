package fr.ses10doigts.mm.core.memory;

import java.time.Instant;

/**
 * Entrée de mémoire factuelle (ADR-009).
 *
 * <p>Le {@code scope} fusionne la mémoire globale (C3+C6) : {@code "global"},
 * {@code "project:<id>"}, {@code "session:<id>"}, {@code "tenant:<id>"}. Le
 * {@code tenant} est présent dès J1 mais figé à {@code "default"} en MVP (ADR-013).</p>
 *
 * @param key       clé de l'entrée
 * @param value     valeur stockée
 * @param scope     portée ({@code "global"}, {@code "project:<id>"}, …)
 * @param tenant    identifiant artisan ; {@code "default"} en MVP
 * @param createdAt date de création
 * @param updatedAt date de dernière modification
 */
public record MemoryEntry(
        String key,
        String value,
        String scope,
        String tenant,
        Instant createdAt,
        Instant updatedAt) {
}
