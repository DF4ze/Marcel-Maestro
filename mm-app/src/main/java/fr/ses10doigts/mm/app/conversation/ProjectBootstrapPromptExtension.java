package fr.ses10doigts.mm.app.conversation;

import fr.ses10doigts.mm.app.project.ProjectBootstrapService;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Injecte les règles de cadrage automatique pour la toute première conversation projet.
 */
@Component
@RequiredArgsConstructor
public class ProjectBootstrapPromptExtension implements SystemPromptExtension {

    private final AgentContextHolder contextHolder;
    private final ProjectBootstrapService projectBootstrapService;

    @Override
    public String contribution() {
        AgentContext context = contextHolder.get();
        if (context == null
                || !projectBootstrapService.isBootstrapConversation(context.projectId(), context.conversationId())) {
            return "";
        }

        return """
                ### Mode cadrage initial du projet
                Cette conversation est la discussion de cadrage initial du projet.
                Son but est de construire progressivement PROJECT.md avec l'utilisateur.

                Règles :
                - explique clairement à l'utilisateur que cette première discussion sert à définir le projet ;
                - précise qu'il peut ouvrir une autre discussion s'il veut arrêter l'interview et revenir ici plus tard pour mieux cadrer le projet ;
                - précise dès le premier message que la roadmap sera traitée plus tard, uniquement si l'utilisateur le demande, dans une discussion dédiée à la construction de la roadmap ;
                - considère que les réponses utilisateur de cette discussion sont automatiquement ajoutées à PROJECT.md ;
                - appuie-toi sur le contenu déjà présent dans PROJECT.md pour éviter de reposer les mêmes questions ;
                - pose une seule question courte, ciblée et utile à la fois ;
                - tant que le projet reste flou, continue à guider la discussion par questions successives ;
                - quand le cadrage est suffisant pour l'instant, arrête de poser des questions et dis-le explicitement.
                """;
    }
}
