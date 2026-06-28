package fr.ses10doigts.mm.app.specialist.coding;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Rapport structuré retourné par un agent spécialiste.
 */
@Builder
@Getter
public class AgentReport {

    /**
     * Statut métier normalisé du rapport.
     */
    public enum Status {
        DONE,
        BLOCKED,
        KO,
        TROUBLE
    }

    private final Status status;
    private final String summary;
    private final List<String> factsDiscovered;
    private final List<String> decisions;
    private final String blocker;

    /**
     * Construit un rapport d'échec technique immédiat.
     *
     * @param reason raison synthétique de l'échec
     * @return rapport KO normalisé
     */
    public static AgentReport ko(String reason) {
        return AgentReport.builder()
                .status(Status.KO)
                .summary(reason)
                .factsDiscovered(List.of())
                .decisions(List.of())
                .blocker(reason)
                .build();
    }

    /**
     * Construit un rapport de trouble quand la sortie brute n'est pas exploitable.
     *
     * @param rawOutput sortie brute ou message de fallback
     * @return rapport TROUBLE normalisé
     */
    public static AgentReport trouble(String rawOutput) {
        return AgentReport.builder()
                .status(Status.TROUBLE)
                .summary("Rapport MARCEL introuvable ou invalide")
                .factsDiscovered(List.of())
                .decisions(List.of())
                .blocker(rawOutput)
                .build();
    }
}
