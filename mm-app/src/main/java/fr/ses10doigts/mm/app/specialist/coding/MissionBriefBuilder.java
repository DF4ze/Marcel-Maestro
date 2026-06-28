package fr.ses10doigts.mm.app.specialist.coding;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Assemble le mission brief injecte en entree de Claude Code ou Codex.
 */
@Component
public class MissionBriefBuilder {

    /**
     * Construit le brief complet a partir de la tache et du contexte Marcel.
     *
     * @param task mission detaillee a confier a l'agent externe
     * @param context contexte projet, roadmap et memoire utile a injecter
     * @return prompt complet pret a etre envoye au CLI
     */
    public String build(AgentTask task, MarcelContext context) {
        String factsBlock = formatFacts(context.getC3Facts());
        String workspacesBlock = formatWorkspaces(context);
        return """
                CONTEXTE PROJET
                %s

                ETAT D'AVANCEMENT
                %s

                MEMOIRE FACTUELLE
                %s

                RACINES DE TRAVAIL AUTORISEES
                %s

                CONSIGNES D'ACCES
                Travaille depuis le repertoire courant fourni par Marcel.
                N'accede qu'aux chemins situes sous les racines autorisees ci-dessus.
                Si un fichier manque dans le workspace interne, verifie les workspaces rattaches avant de conclure a un blocage.

                TA MISSION
                Task ID : %s
                Titre : %s

                %s

                FORMAT DE RAPPORT
                Termine IMPERATIVEMENT par un bloc unique :
                <MARCEL_REPORT>{"status":"DONE|BLOCKED|KO|TROUBLE","summary":"...","factsDiscovered":["..."],"decisions":["..."],"blocker":"..."}</MARCEL_REPORT>
                Reponds avec un JSON valide dans ce bloc final. Aucun markdown dans le JSON.
                """.formatted(
                safeBlock(context.getProjectMd()),
                safeBlock(context.getRoadmapResultMd()),
                factsBlock,
                workspacesBlock,
                safeInline(task.getId()),
                safeInline(task.getTitle()),
                safeBlock(task.getDescription()));
    }

    private String formatFacts(List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return "- Aucun fait C3 pertinent selectionne.";
        }
        return facts.stream()
                .map(fact -> "- " + safeInline(fact))
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("- Aucun fait C3 pertinent selectionne.");
    }

    private String formatWorkspaces(MarcelContext context) {
        List<String> workspaces = context.getDeclaredWorkspaces();
        String currentDirectory = safeInline(context.getWorkingDirectory());
        if (workspaces == null || workspaces.isEmpty()) {
            return "- Repertoire courant : " + currentDirectory;
        }
        return workspaces.stream()
                .map(workspace -> workspace.equals(context.getWorkingDirectory())
                        ? "- " + safeInline(workspace) + " (repertoire courant)"
                        : "- " + safeInline(workspace))
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("- Repertoire courant : " + currentDirectory);
    }

    private String safeBlock(String value) {
        if (value == null || value.isBlank()) {
            return "(vide)";
        }
        return value.trim();
    }

    private String safeInline(String value) {
        if (value == null || value.isBlank()) {
            return "(non renseigne)";
        }
        return value.trim();
    }
}
