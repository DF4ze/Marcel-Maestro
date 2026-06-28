package fr.ses10doigts.mm.app.specialist.coding;

import lombok.Builder;
import lombok.Getter;

/**
 * Résultat d'exécution d'un processus externe.
 */
@Builder
@Getter
public class ProcessResult {

    private final String output;
    private final int exitCode;
}
