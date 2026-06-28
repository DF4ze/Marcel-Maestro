package fr.ses10doigts.mm.app.specialist.coding;

import java.text.Normalizer;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Qualificateur hybride de tâches déléguées : règles déterministes d'abord, repli LLM ensuite.
 *
 * <p>Objectif : rendre le routage <strong>prévisible et observable</strong>. La majorité des
 * demandes sont tranchées par des règles de mots-clés (coût nul, testable). Le petit LLM n'est
 * sollicité que sur les cas réellement ambigus, et un défaut de sûreté garantit qu'aucune
 * tâche ne reste sans agent.</p>
 *
 * <p>La résolution finale catégorie → agent (ex. {@code CODING -> claude}, {@code BUILD -> codex})
 * réutilise la table déterministe {@code mm.agents.routing} portée par {@link CodingAgentsProperties}.
 * C'est l'ancienne logique de routage du sous-système coding, recentrée ici où elle sert réellement.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskQualifier {

    /** Catégorie de repli quand aucune règle ne tranche et que le LLM est indisponible/illisible. */
    private static final TaskCategory DEFAULT_CATEGORY = TaskCategory.CODING;

    /** Agent de repli si la table de routage ne couvre pas la catégorie résolue. */
    private static final String DEFAULT_AGENT_ID = "claude";

    private static final Map<TaskCategory, List<String>> KEYWORDS = buildKeywords();

    private final CodingAgentsProperties properties;
    private final ObjectProvider<ChatClient> chatClientProvider;

    /**
     * Qualifie une description de tâche en catégorie métier puis en agent spécialiste.
     *
     * @param description description libre de la tâche à déléguer
     * @return qualification complète (catégorie, agent, origine de la décision)
     */
    public Qualification qualify(String description) {
        TaskCategory ruleCategory = classifyByRules(description);
        if (ruleCategory != null) {
            return resolve(ruleCategory, QualificationSource.RULES);
        }

        TaskCategory llmCategory = classifyByLlm(description);
        if (llmCategory != null) {
            return resolve(llmCategory, QualificationSource.LLM);
        }

        log.info("TaskQualifier — aucune règle ni LLM exploitable, repli sur {}", DEFAULT_CATEGORY);
        return resolve(DEFAULT_CATEGORY, QualificationSource.FALLBACK_DEFAULT);
    }

    /**
     * Applique les règles déterministes de mots-clés.
     *
     * @param description description de la tâche
     * @return catégorie gagnante, ou {@code null} si aucune règle ne tranche
     */
    private TaskCategory classifyByRules(String description) {
        String normalized = normalize(description);
        if (normalized.isBlank()) {
            return null;
        }

        Map<TaskCategory, Integer> scores = new EnumMap<>(TaskCategory.class);
        for (Map.Entry<TaskCategory, List<String>> entry : KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (normalized.contains(keyword)) {
                    score++;
                }
            }
            if (score > 0) {
                scores.put(entry.getKey(), score);
            }
        }
        if (scores.isEmpty()) {
            return null;
        }

        TaskCategory best = null;
        int bestScore = -1;
        for (Map.Entry<TaskCategory, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                best = entry.getKey();
            }
        }
        log.debug("TaskQualifier — règles: scores={}, retenu={}", scores, best);
        return best;
    }

    /**
     * Repli LLM léger pour les descriptions non tranchées par les règles.
     *
     * @param description description de la tâche
     * @return catégorie déduite par le LLM, ou {@code null} si indisponible/illisible
     */
    private TaskCategory classifyByLlm(String description) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null || description == null || description.isBlank()) {
            return null;
        }
        try {
            String answer = chatClient.prompt()
                    .system("Tu es un routeur de tâches. Classe la demande de l'utilisateur dans EXACTEMENT une "
                            + "catégorie parmi : CODING (écriture/refacto/correction/lecture de code), "
                            + "ANALYSIS (analyse, audit, explication technique), "
                            + "BUILD (build Maven, scripts, shell, CI, déploiement, ops). "
                            + "Réponds par un seul mot, en majuscules, sans ponctuation.")
                    .user(description)
                    .call()
                    .content();
            return parseCategory(answer);
        } catch (RuntimeException e) {
            log.warn("TaskQualifier — repli LLM en échec, on passera au défaut: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse une réponse LLM en catégorie, de façon tolérante mais sans interprétation libre.
     *
     * @param answer réponse brute du LLM
     * @return catégorie reconnue, ou {@code null}
     */
    private TaskCategory parseCategory(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String upper = answer.trim().toUpperCase(Locale.ROOT);
        for (TaskCategory category : TaskCategory.values()) {
            if (upper.contains(category.name())) {
                return category;
            }
        }
        return null;
    }

    /**
     * Résout l'agent cible pour une catégorie via la table de routage configurée.
     *
     * @param category catégorie retenue
     * @param source origine de la décision
     * @return qualification complète
     */
    private Qualification resolve(TaskCategory category, QualificationSource source) {
        String agentId = properties.getRouting().getOrDefault(category, DEFAULT_AGENT_ID);
        return new Qualification(category, agentId, source);
    }

    /**
     * Normalise un texte pour la comparaison : minuscules et suppression des accents.
     *
     * @param text texte d'entrée
     * @return texte normalisé, jamais {@code null}
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "");
    }

    /**
     * Construit les listes de mots-clés (déjà normalisés) par catégorie.
     *
     * @return table immuable des mots-clés de classification
     */
    private static Map<TaskCategory, List<String>> buildKeywords() {
        Map<TaskCategory, List<String>> keywords = new EnumMap<>(TaskCategory.class);
        keywords.put(TaskCategory.BUILD, List.of(
                "build", "compile", "compilation", "mvn", "maven", "package", "gradle",
                "deploy", "deploie", "deploiement", "vps", "ci", "pipeline", "gitlab",
                "docker", "install", "script", "shell", "bash", "ops", "release", "lance le build"));
        keywords.put(TaskCategory.ANALYSIS, List.of(
                "analyse", "analyser", "audit", "explique", "explication", "expliquer",
                "comprendre", "review", "revue", "inspecte", "inspection", "diagnostic",
                "diagnostique", "pourquoi", "evalue", "evaluation"));
        keywords.put(TaskCategory.CODING, List.of(
                "code", "coder", "implemente", "implementer", "implementation",
                "ecris", "ecrire", "refacto", "refactor", "refactorise", "bug", "corrige",
                "corriger", "fix", "classe", "methode", "fonction", "test", "ajoute",
                "ajouter", "developpe", "developper", "cree une classe", "renomme"));
        return Map.copyOf(keywords);
    }
}
