package fr.ses10doigts.mm.core.engine;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.AgentResponse;
import fr.ses10doigts.mm.core.agent.AgentStatus;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.engine.parse.AgentResponseParser;
import fr.ses10doigts.mm.core.engine.parse.FinishReason;
import fr.ses10doigts.mm.core.engine.parse.ParseOutcome;
import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.core.hitl.HumanInteraction;
import fr.ses10doigts.mm.core.hitl.NotificationLevel;
import fr.ses10doigts.mm.core.journal.Journal;
import fr.ses10doigts.mm.core.journal.JournalEntry;
import fr.ses10doigts.mm.core.prompt.SystemPromptComposer;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Boucle agentique de Cortex : fiable et bornée (étape 3, livrables 2-5 ; ADR-006).
 *
 * <p>Orchestre, autour du {@link ChatClient} Spring AI <strong>injecté</strong> (le bean
 * concret est câblé côté starter/app — le noyau reste pur) :</p>
 * <ol>
 *   <li>composition du system prompt ({@link SystemPromptComposer}) ;</li>
 *   <li>appel LLM puis parsing déterministe ({@link AgentResponseParser}) — tout échec de
 *       format devient {@link AgentStatus#TROUBLE}, jamais d'interprétation NLP ;</li>
 *   <li>garde-fous et bornage ({@link LoopGuards}) ;</li>
 *   <li>routage par statut ({@link AgentStateMachine}) ;</li>
 *   <li>STOP coopératif ({@link StopSignal}) vérifié uniquement entre opérations atomiques.</li>
 * </ol>
 *
 * <p>Déterministe en bas (parsing, routage, bornes), LLM pour le jugement seulement.
 * Sans état propre : une instance est réutilisable et thread-safe tant que le
 * {@link ChatClient} l'est ; l'état d'une exécution vit dans des {@link LoopGuards}
 * locaux.</p>
 *
 * <p><strong>Étape 4</strong> : intégration du HITL sur {@code blocked}. Quand le LLM
 * produit {@code status: blocked}, la boucle appelle {@link HumanInteraction#ask}
 * (si disponible) et reprend sur {@code ALLOW} ou termine en {@code KO} sur
 * {@code DENY}. Le garde-fou sur les tool_calls ({@link fr.ses10doigts.mm.core.hitl.HitlGuard})
 * sera branché à l'étape 6, au même point que l'exécution des outils.</p>
 *
 * <p><strong>Hors scope</strong> : exécution réelle des {@code tool_calls}
 * (étape 6), délégation des {@code sub_tasks} au Dispatcher (étape 7),
 * error-triggered retrieval sur {@code trouble} (ADR-011).
 * Ces champs sont parsés et journalisés mais pas exécutés ici.</p>
 */
public final class AgentLoop {

    private final ChatClient chatClient;
    private final SystemPromptComposer promptComposer;
    private final AgentResponseParser parser;
    private final AgentStateMachine stateMachine;
    private final LoopConfig config;
    private final Journal journal;
    private final HumanInteraction humanInteraction;

    /**
     * Constructeur complet (étape 4+).
     *
     * @param chatClient        client Spring AI injecté (bean concret côté hôte)
     * @param promptComposer    composition du system prompt
     * @param parser            parsing robuste de la sortie LLM
     * @param stateMachine      routage par statut
     * @param config            bornes de la boucle
     * @param journal           journal append-only ; {@code null} accepté
     * @param humanInteraction  canal humain pour le cas {@code BLOCKED} ; {@code null}
     *                          accepté (le loop se comporte alors comme avant l'étape 4 :
     *                          {@code BLOCKED} → terminaison immédiate)
     */
    public AgentLoop(ChatClient chatClient,
                     SystemPromptComposer promptComposer,
                     AgentResponseParser parser,
                     AgentStateMachine stateMachine,
                     LoopConfig config,
                     Journal journal,
                     HumanInteraction humanInteraction) {
        this.chatClient = chatClient;
        this.promptComposer = promptComposer;
        this.parser = parser;
        this.stateMachine = stateMachine;
        this.config = config;
        this.journal = journal;
        this.humanInteraction = humanInteraction;
    }

    /**
     * Constructeur rétro-compatible (sans HITL).
     */
    public AgentLoop(ChatClient chatClient,
                     SystemPromptComposer promptComposer,
                     AgentResponseParser parser,
                     AgentStateMachine stateMachine,
                     LoopConfig config,
                     Journal journal) {
        this(chatClient, promptComposer, parser, stateMachine, config, journal, null);
    }

    /**
     * Exécute la boucle jusqu'à un statut terminal ({@code done}, {@code KO}) ou une
     * attente humaine ({@code blocked}).
     *
     * @param task tâche à traiter (contenu, contexte, destinataire)
     * @param stop signal d'arrêt coopératif (passer {@link StopSignal#never()} si aucun)
     * @return le résultat final, jamais {@code null}
     */
    public AgentOutcome run(TaskMessage task, StopSignal stop) {
        String agentId = task.assignee() != null ? task.assignee() : "cortex";
        AgentContext ctx = task.ctx();
        String systemPrompt = promptComposer.compose();

        List<Message> history = new ArrayList<>();
        history.add(new UserMessage(task.content() == null ? "" : task.content()));

        LoopGuards guards = new LoopGuards(config);
        AgentResponse last = null;

        while (true) {
            if (!guards.tryStartIteration()) {
                return terminate(agentId, ctx, AgentStatus.KO,
                        guards.maxIterationsVerdict().reason(), last, guards);
            }
            if (stop.isStopped()) {
                return stopped(agentId, ctx, last, guards);
            }

            String text;
            FinishReason finishReason;
            try {
                ChatResponse response = chatClient.prompt()
                        .system(systemPrompt)
                        .messages(history)
                        .call()
                        .chatResponse();
                text = extractText(response);
                finishReason = extractFinishReason(response);
            } catch (RuntimeException e) {
                // Échec d'appel (réseau, provider) : traité comme une difficulté, pas une
                // exception qui remonte. La boucle relancera sur prompt renforcé.
                text = null;
                finishReason = FinishReason.UNKNOWN;
                journal(agentId, ctx, "llm_error", Map.of("message", String.valueOf(e.getMessage())));
            }

            if (stop.isStopped()) {
                return stopped(agentId, ctx, last, guards);
            }

            ParseOutcome outcome = parser.parse(text, finishReason);
            AgentStatus status;
            if (outcome instanceof ParseOutcome.Parsed parsed) {
                last = parsed.response();
                status = last.status();
                history.add(new AssistantMessage(text));
            } else {
                ParseOutcome.Failure failure = (ParseOutcome.Failure) outcome;
                status = AgentStatus.TROUBLE;
                journal(agentId, ctx, "parse_failure",
                        Map.of("mode", failure.mode().name(), "detail", String.valueOf(failure.detail())));
            }

            journal(agentId, ctx, "iteration", Map.of(
                    "iteration", guards.iterations(),
                    "status", status.json(),
                    "finishReason", finishReason.name()));

            GuardVerdict verdict = guards.recordStatus(status);
            if (verdict.isTerminal()) {
                return terminate(agentId, ctx, AgentStatus.KO, verdict.reason(), last, guards);
            }

            Routing routing = stateMachine.route(status);
            switch (routing) {
                case TERMINATE_DONE ->
                        { return terminate(agentId, ctx, AgentStatus.DONE,
                                reasonOr(last, "terminé"), last, guards); }
                case TERMINATE_KO ->
                        { return terminate(agentId, ctx, AgentStatus.KO,
                                reasonOr(last, "KO signalé par l'agent"), last, guards); }
                case AWAIT_HUMAN -> {
                    if (humanInteraction != null && last != null) {
                        String question = reasonOr(last,
                                "L'agent demande une validation pour continuer");
                        HitlRequest hitlReq = new HitlRequest(question, RiskLevel.HIGH, ctx);
                        journal(agentId, ctx, "hitl_ask",
                                Map.of("question", question, "riskLevel", "HIGH"));
                        ConsentDecision decision = humanInteraction.ask(hitlReq);
                        journal(agentId, ctx, "hitl_decision",
                                Map.of("decision", decision.name()));
                        if (decision == ConsentDecision.DENY) {
                            humanInteraction.notify(new AgentNotification(
                                    "Tâche refusée", "L'utilisateur a refusé la poursuite",
                                    NotificationLevel.WARNING, ctx));
                            return terminate(agentId, ctx, AgentStatus.KO,
                                    "refusé par l'utilisateur", last, guards);
                        }
                        // Reprend la boucle avec la décision humaine comme contexte
                        history.add(new UserMessage(
                                "Validation humaine accordée (" + decision.name()
                                        + "). Continue."));
                    } else {
                        return terminate(agentId, ctx, AgentStatus.BLOCKED,
                                "en attente de validation humaine (pas de canal HITL disponible)",
                                last, guards);
                    }
                }
                case RETRY_REINFORCED -> history.add(new UserMessage(promptComposer.reinforcedRetry()));
                case CONTINUE -> history.add(new UserMessage(promptComposer.continuation()));
            }
        }
    }

    private AgentOutcome stopped(String agentId, AgentContext ctx, AgentResponse last, LoopGuards guards) {
        return terminate(agentId, ctx, AgentStatus.KO, "stopped by user", last, guards);
    }

    private AgentOutcome terminate(String agentId, AgentContext ctx, AgentStatus status,
                                   String reason, AgentResponse last, LoopGuards guards) {
        journal(agentId, ctx, "termination", Map.of(
                "finalStatus", status.json(),
                "reason", String.valueOf(reason),
                "iterations", guards.iterations()));
        return new AgentOutcome(status, last, guards.iterations(), reason);
    }

    private static String reasonOr(AgentResponse response, String fallback) {
        if (response != null && response.reason() != null && !response.reason().isBlank()) {
            return response.reason();
        }
        return fallback;
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private static FinishReason extractFinishReason(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getMetadata() == null) {
            return FinishReason.UNKNOWN;
        }
        return FinishReason.fromProvider(response.getResult().getMetadata().getFinishReason());
    }

    private void journal(String agentId, AgentContext ctx, String category, Map<String, Object> data) {
        if (journal == null) {
            return;
        }
        String taskId = ctx != null ? ctx.taskId() : null;
        journal.append(new JournalEntry(
                Instant.now(), agentId, taskId, category, new LinkedHashMap<>(data)));
    }
}
