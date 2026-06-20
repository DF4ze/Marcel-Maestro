package fr.ses10doigts.mm.core.engine;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.AgentResponse;
import fr.ses10doigts.mm.core.agent.AgentStatus;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.ToolCall;
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
import fr.ses10doigts.mm.core.tool.AgentTool;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import fr.ses10doigts.mm.core.tool.ToolExecutionGuard;
import fr.ses10doigts.mm.core.tool.ToolRegistry;
import fr.ses10doigts.mm.core.tool.ToolResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Boucle agentique de Cortex : fiable et bornée (étapes 3-6 ; ADR-006).
 *
 * <p>Orchestre, autour du {@link ChatClient} Spring AI <strong>injecté</strong> (le bean
 * concret est câblé côté starter/app — le noyau reste pur) :</p>
 * <ol>
 *   <li>composition du system prompt ({@link SystemPromptComposer}) ;</li>
 *   <li>appel LLM puis parsing déterministe ({@link AgentResponseParser}) — tout échec de
 *       format devient {@link AgentStatus#TROUBLE}, jamais d'interprétation NLP ;</li>
 *   <li>garde-fous et bornage ({@link LoopGuards}) ;</li>
 *   <li>routage par statut ({@link AgentStateMachine}) ;</li>
 *   <li>STOP coopératif ({@link StopSignal}) vérifié uniquement entre opérations atomiques ;</li>
 *   <li><strong>étape 6</strong> : exécution des {@code tool_calls} via {@link ToolRegistry}
 *       et {@link ToolExecutionGuard} (HITL, path validation, timeout).</li>
 * </ol>
 *
 * <p>Déterministe en bas (parsing, routage, bornes), LLM pour le jugement seulement.
 * Sans état propre : une instance est réutilisable et thread-safe tant que le
 * {@link ChatClient} l'est ; l'état d'une exécution vit dans des {@link LoopGuards}
 * locaux.</p>
 *
 * <p><strong>Hors scope</strong> : délégation des {@code sub_tasks} au Dispatcher (étape 7),
 * error-triggered retrieval sur {@code trouble} (ADR-011).
 * Les sub_tasks sont parsés et journalisés mais pas exécutés ici.</p>
 */
@Slf4j
public final class AgentLoop {

    private final ChatClient chatClient;
    private final SystemPromptComposer promptComposer;
    private final AgentResponseParser parser;
    private final AgentStateMachine stateMachine;
    private final LoopConfig config;
    private final Journal journal;
    private final HumanInteraction humanInteraction;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionGuard toolExecutionGuard;

    /**
     * Constructeur complet (étape 6).
     *
     * @param chatClient         client Spring AI injecté (bean concret côté hôte)
     * @param promptComposer     composition du system prompt
     * @param parser             parsing robuste de la sortie LLM
     * @param stateMachine       routage par statut
     * @param config             bornes de la boucle
     * @param journal            journal append-only ; {@code null} accepté
     * @param humanInteraction   canal humain pour le cas {@code BLOCKED} ; {@code null} accepté
     * @param toolRegistry       registre d'outils ; {@code null} accepté (pas d'exécution
     *                           d'outils, les tool_calls sont journalisés mais ignorés)
     * @param toolExecutionGuard garde d'exécution transverse ; {@code null} accepté
     */
    public AgentLoop(ChatClient chatClient,
                     SystemPromptComposer promptComposer,
                     AgentResponseParser parser,
                     AgentStateMachine stateMachine,
                     LoopConfig config,
                     Journal journal,
                     HumanInteraction humanInteraction,
                     ToolRegistry toolRegistry,
                     ToolExecutionGuard toolExecutionGuard) {
        this.chatClient = chatClient;
        this.promptComposer = promptComposer;
        this.parser = parser;
        this.stateMachine = stateMachine;
        this.config = config;
        this.journal = journal;
        this.humanInteraction = humanInteraction;
        this.toolRegistry = toolRegistry;
        this.toolExecutionGuard = toolExecutionGuard;
    }

    /**
     * Constructeur rétro-compatible (sans outils ni HITL).
     */
    public AgentLoop(ChatClient chatClient,
                     SystemPromptComposer promptComposer,
                     AgentResponseParser parser,
                     AgentStateMachine stateMachine,
                     LoopConfig config,
                     Journal journal) {
        this(chatClient, promptComposer, parser, stateMachine, config, journal, null, null, null);
    }

    /**
     * Constructeur rétro-compatible (avec HITL, sans outils).
     */
    public AgentLoop(ChatClient chatClient,
                     SystemPromptComposer promptComposer,
                     AgentResponseParser parser,
                     AgentStateMachine stateMachine,
                     LoopConfig config,
                     Journal journal,
                     HumanInteraction humanInteraction) {
        this(chatClient, promptComposer, parser, stateMachine, config, journal,
                humanInteraction, null, null);
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

            // ── Étape 6 : exécution des tool_calls ─────────────────────────
            if (last != null && hasToolCalls(last) && toolRegistry != null) {
                log.info("Exécution de {} tool_call(s) à l'itération {}",
                        last.toolCalls().size(), guards.iterations());
                StringBuilder toolResults = new StringBuilder();
                for (int i = 0; i < last.toolCalls().size(); i++) {
                    if (stop.isStopped()) {
                        return stopped(agentId, ctx, last, guards);
                    }
                    ToolCall call = last.toolCalls().get(i);
                    String idempotencyKey = ctx.taskId() + ":" + call.tool()
                            + ":" + guards.iterations() + ":" + i;
                    AgentContext toolCtx = new AgentContext(
                            ctx.tenant(), ctx.projectId(), ctx.conversationId(),
                            ctx.taskId(), idempotencyKey);

                    journal(agentId, ctx, "tool_call",
                            Map.of("tool", call.tool(),
                                    "params", String.valueOf(call.params()),
                                    "idempotencyKey", idempotencyKey));

                    Optional<AgentTool> toolOpt = toolRegistry.get(call.tool());
                    ToolResult result;
                    if (toolOpt.isEmpty()) {
                        log.info("Outil '{}' introuvable dans le registre", call.tool());
                        result = ToolResult.fail("outil inconnu : " + call.tool());
                    } else {
                        AgentTool tool = toolOpt.get();
                        if (toolExecutionGuard != null) {
                            result = toolExecutionGuard.execute(tool,
                                    call.params() != null ? call.params() : Collections.emptyMap(),
                                    toolCtx);
                        } else {
                            try {
                                result = tool.execute(
                                        call.params() != null ? call.params() : Collections.emptyMap(),
                                        toolCtx);
                            } catch (Exception e) {
                                log.info("Exception lors de l'exécution de '{}' : {}",
                                        call.tool(), e.getMessage());
                                result = ToolResult.fail(e.getMessage());
                            }
                        }
                    }

                    journal(agentId, ctx, "tool_result",
                            Map.of("tool", call.tool(),
                                    "success", result.success(),
                                    "error", String.valueOf(result.error())));

                    toolResults.append("[tool_result:").append(call.tool()).append("] ");
                    if (result.success()) {
                        toolResults.append(String.valueOf(result.data()));
                    } else {
                        toolResults.append("ERREUR: ").append(result.error());
                    }
                    toolResults.append("\n");
                }
                // Injecter les résultats comme message utilisateur pour la prochaine itération
                history.add(new UserMessage(toolResults.toString().trim()));
                log.info("Résultats d'outils injectés dans l'historique");
                // La boucle continue : on ne route pas sur DONE/KO ici, le LLM
                // décidera du statut suivant en voyant les résultats
                continue;
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

    /**
     * Vérifie si une réponse contient des tool_calls à exécuter.
     */
    private static boolean hasToolCalls(AgentResponse response) {
        return response.toolCalls() != null && !response.toolCalls().isEmpty();
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
