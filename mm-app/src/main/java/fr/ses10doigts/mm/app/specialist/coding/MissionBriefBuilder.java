package fr.ses10doigts.mm.app.specialist.coding;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Assemble le mission brief injecté en entrée de Claude Code ou Codex.
 */
@Component
public class MissionBriefBuilder {

    /**
     * Construit le brief complet à partir de la tâche et du contexte Marcel.
     *
     * @param task mission détaillée à confier à l'agent externe
     * @param context contexte projet, roadmap et mémoire utile à injecter
     * @return prompt complet prêt à être envoyé au CLI
     */
    public String build(AgentTask task, MarcelContext context) {
        String factsBlock = formatFacts(context.getC3Facts());
        return """
                CONTEXTE PROJET
                %s

                ÉTAT D'AVANCEMENT
                %s

                MÉMOIRE FACTUELLE
                %s

                TA MISSION
                Task ID : %s
                Titre : %s

                %s

                FORMAT DE RAPPORT
                Termine IMPÉRATIVEMENT par un bloc unique :
                <MARCEL_REPORT>{"status":"DONE|BLOCKED|KO|TROUBLE","summary":"...","factsDiscovered":["..."],"decisions":["..."],"blocker":"..."}</MARCEL_REPORT>
                Réponds avec un JSON valide dans ce bloc final. Aucun markdown dans le JSON.
                """.formatted(
                safeBlock(context.getProjectMd()),
                safeBlock(context.getRoadmapResultMd()),
                factsBlock,
                safeInline(task.getId()),
                safeInline(task.getTitle()),
                safeBlock(task.getDescription()));
    }

    private String formatFacts(List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return "- Aucun fait C3 pertinent sélectionné.";
        }
        return facts.stream()
                .map(fact -> "- " + safeInline(fact))
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("- Aucun fait C3 pertinent sélectionné.");
    }

    private String safeBlock(String value) {
        if (value == null || value.isBlank()) {
            return "(vide)";
        }
        return value.trim();
    }

    private String safeInline(String value) {
        if (value == null || value.isBlank()) {
            return "(non renseigné)";
        }
        return value.trim();
    }
}
