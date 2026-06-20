package fr.ses10doigts.mm.app.specialist;

import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.engine.AgentLoop;
import fr.ses10doigts.mm.core.engine.AgentOutcome;
import fr.ses10doigts.mm.core.engine.AgentStateMachine;
import fr.ses10doigts.mm.core.engine.LoopConfig;
import fr.ses10doigts.mm.core.engine.StopSignal;
import fr.ses10doigts.mm.core.engine.parse.AgentResponseParser;
import fr.ses10doigts.mm.core.journal.Journal;
import fr.ses10doigts.mm.core.orchestration.AgentFactory;
import fr.ses10doigts.mm.core.prompt.SystemPromptComposer;
import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Spécialiste de démo — prouve le cycle Cortex → spécialiste → rapport → Cortex.
 *
 * <p>Réutilise la boucle agentique de l'étape 3 avec un system prompt étendu :
 * il répète la demande reçue en ajoutant {@code [ECHO]} devant, puis répond
 * {@code status: done}. Aucun outil, aucun raisonnement complexe.</p>
 *
 * <p>Le prompt de base du noyau (contrat JSON) est conservé ; l'extension
 * spécialiste ajoute l'identité et la mission d'écho.</p>
 *
 * <p>Ce spécialiste sert exclusivement à valider les coutures de l'orchestrateur
 * (étape 7). Il sera remplacé par de vrais spécialistes dans le roster final.</p>
 */
@Slf4j
public class EchoSpecialist implements AgentFactory {

    /** Identifiant de ce spécialiste, utilisé comme {@code assignee} dans les TaskMessage. */
    public static final String AGENT_ID = "echo-specialist";

    private static final String ECHO_EXTENSION = """
            RÔLE SPÉCIALISTE — EchoSpecialist
            Tu n'es PAS Cortex. Tu es un spécialiste de démo nommé EchoSpecialist.
            Ta seule mission : répéter la demande reçue en ajoutant [ECHO] devant.

            Tu ne planifies JAMAIS de sous-tâches (ADR-007 : seul le Cortex planifie).
            Tu ne fais qu'une seule itération, puis tu termines avec status: done.

            Exemple de réponse attendue :
            {"status":"done","reason":"Écho effectué","output":"[ECHO] la demande originale","tool_calls":[],"sub_tasks":[]}
            """;

    private final ChatClient chatClient;
    private final AgentResponseParser parser;
    private final AgentStateMachine stateMachine;
    private final Journal journal;

    /**
     * Construit un EchoSpecialist.
     *
     * @param chatClient   client LLM (peut être {@code null} dans les tests)
     * @param parser       parser de réponse structurée
     * @param stateMachine machine à états pour le routage
     * @param journal      journal d'audit (peut être {@code null})
     */
    public EchoSpecialist(ChatClient chatClient,
                          AgentResponseParser parser,
                          AgentStateMachine stateMachine,
                          Journal journal) {
        this.chatClient = chatClient;
        this.parser = parser;
        this.stateMachine = stateMachine;
        this.journal = journal;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String agentId() {
        return AGENT_ID;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lance une boucle agentique avec le prompt de base du noyau (contrat JSON)
     * étendu par l'identité et la mission d'écho. Bornes serrées
     * ({@code maxIterations=5}) puisque l'écho ne devrait prendre qu'une seule itération.</p>
     */
    @Override
    public AgentOutcome execute(TaskMessage task, StopSignal stop) {
        log.info("EchoSpecialist démarré — taskId={}, contenu='{}'",
                task.taskId(), task.content());

        SystemPromptExtension echoExtension = () -> ECHO_EXTENSION;
        SystemPromptComposer echoPrompt = new SystemPromptComposer(List.of(echoExtension));

        LoopConfig echoConfig = new LoopConfig(5, 2, 3);

        AgentLoop loop = new AgentLoop(
                chatClient, echoPrompt, parser, stateMachine, echoConfig, journal);

        AgentOutcome outcome = loop.run(task, stop);
        log.info("EchoSpecialist terminé — taskId={}, status={}, iterations={}",
                task.taskId(), outcome.finalStatus().json(), outcome.iterations());
        return outcome;
    }
}
