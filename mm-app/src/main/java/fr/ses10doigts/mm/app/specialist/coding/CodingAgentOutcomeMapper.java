package fr.ses10doigts.mm.app.specialist.coding;

import fr.ses10doigts.mm.core.agent.AgentResponse;
import fr.ses10doigts.mm.core.agent.AgentStatus;
import fr.ses10doigts.mm.core.engine.AgentOutcome;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Convertit un {@link AgentReport} specialist.coding en {@link AgentOutcome} moteur.
 */
@Component
public class CodingAgentOutcomeMapper {

    /**
     * Mappe le statut métier du rapport vers un statut exploitable par Cortex.
     *
     * @param report rapport structuré du spécialiste
     * @return outcome compatible avec le Dispatcher historique
     */
    public AgentOutcome toOutcome(AgentReport report) {
        AgentStatus finalStatus = mapStatus(report.getStatus());
        String output = buildOutput(report);
        AgentResponse response = new AgentResponse(
                finalStatus,
                summaryOrFallback(report),
                output,
                List.of(),
                List.of());
        return new AgentOutcome(finalStatus, response, 1, summaryOrFallback(report));
    }

    /**
     * Applique la correspondance de statuts définie pour Cortex.
     *
     * @param status statut métier de l'adaptateur coding
     * @return statut moteur terminal
     */
    private AgentStatus mapStatus(AgentReport.Status status) {
        if (status == null) {
            return AgentStatus.KO;
        }
        return switch (status) {
            case DONE -> AgentStatus.DONE;
            case BLOCKED -> AgentStatus.BLOCKED;
            case KO, TROUBLE -> AgentStatus.KO;
        };
    }

    /**
     * Formate un rapport lisible pour le Cortex à partir du résultat specialist.coding.
     *
     * @param report rapport brut du spécialiste
     * @return synthèse textuelle multi-ligne
     */
    private String buildOutput(AgentReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("summary: ").append(summaryOrFallback(report)).append("\n");
        builder.append("factsDiscovered: ").append(formatList(report.getFactsDiscovered())).append("\n");
        builder.append("decisions: ").append(formatList(report.getDecisions())).append("\n");
        builder.append("blocker: ").append(valueOrNone(report.getBlocker()));
        return builder.toString();
    }

    /**
     * Retourne une liste jointe ou une valeur de repli lisible.
     *
     * @param values liste à afficher
     * @return représentation textuelle compacte
     */
    private String formatList(List<String> values) {
        return values == null || values.isEmpty() ? "(none)" : String.join(" | ", values);
    }

    /**
     * Retourne le résumé du rapport, ou le blocker si c'est la seule information utile.
     *
     * @param report rapport à normaliser
     * @return résumé non vide
     */
    private String summaryOrFallback(AgentReport report) {
        if (report.getSummary() != null && !report.getSummary().isBlank()) {
            return report.getSummary().trim();
        }
        if (report.getBlocker() != null && !report.getBlocker().isBlank()) {
            return report.getBlocker().trim();
        }
        return "Rapport spécialiste sans résumé";
    }

    /**
     * Retourne une valeur non vide exploitable dans le rapport texte.
     *
     * @param value valeur brute
     * @return texte lisible
     */
    private String valueOrNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value.trim();
    }
}
