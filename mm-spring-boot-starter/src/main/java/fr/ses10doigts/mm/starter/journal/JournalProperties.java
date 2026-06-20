package fr.ses10doigts.mm.starter.journal;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriétés de configuration du journal JSONL.
 *
 * <p>Préfixe : {@code mm.journal}. Le chemin de base est configurable
 * via {@code mm.journal.base-path} (défaut {@code ./logs/journal}).</p>
 */
@ConfigurationProperties(prefix = "mm.journal")
@Getter
@Setter
public class JournalProperties {

    /** Répertoire racine où les fichiers JSONL sont écrits. */
    private String basePath = "./logs/journal";
}
