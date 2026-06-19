package fr.ses10doigts.mm.app.spike;

import fr.ses10doigts.mm.core.engine.parse.AgentResponseParser;
import fr.ses10doigts.mm.core.engine.parse.FinishReason;
import fr.ses10doigts.mm.core.engine.parse.ParseOutcome;
import fr.ses10doigts.mm.core.prompt.SystemPromptComposer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * SPIKE DE DÉRISQUAGE JSON — go/no-go de l'étape 3 (roadmap §3, item 1 ; PB-08, PB-09).
 *
 * <p>Valide <strong>empiriquement</strong> que le LLM produit du JSON conforme au contrat
 * {@code AgentResponse} à plus de 95 %, sur plusieurs modèles. Réutilise le
 * <em>vrai</em> {@link SystemPromptComposer} et le <em>vrai</em> {@link AgentResponseParser}
 * du noyau : on mesure le contrat réel, pas une maquette.</p>
 *
 * <p><strong>Ce test n'est PAS lancé par {@code mvn verify}</strong> (tag {@code spike}
 * exclu via surefire) et se désactive sans clé API. Pour le lancer :</p>
 * <pre>
 *   # PowerShell (Windows)
 *   $env:OPENROUTER_API_KEY = "sk-or-..."
 *   # Optionnel : modèles et nombre d'itérations
 *   $env:SPIKE_MODELS = "openai/gpt-4o-mini,anthropic/claude-3.5-haiku,meta-llama/llama-3.1-8b-instruct"
 *   $env:SPIKE_ITERATIONS = "20"
 *   mvn -pl mm-app test -Dgroups=spike "-Dtest=JsonReliabilitySpikeTest"
 * </pre>
 *
 * <p>Le rapport (tableau markdown + verdict) est imprimé sur la console et écrit dans
 * {@code mm-app/target/spike-report.md}. À coller dans la conversation pour décider du
 * provider (PB-02) et du go/no-go.</p>
 */
@Tag("spike")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class JsonReliabilitySpikeTest {

    private static final double THRESHOLD = 0.95;

    private static final List<String> DEFAULT_MODELS = List.of(
            "openai/gpt-4o-mini",
            "anthropic/claude-3.5-haiku",
            "meta-llama/llama-3.1-8b-instruct");

    /** Jeu de prompts représentatif. On ne juge pas la décision, seulement le FORMAT. */
    private static final List<String> PROMPTS = List.of(
            "Dis simplement bonjour à l'utilisateur et termine la tâche.",
            "Tu dois lister les fichiers du projet mais tu n'as aucun outil disponible. Que fais-tu ?",
            "Décompose en sous-tâches pour des spécialistes la migration d'une base de données SQLite vers PostgreSQL.",
            "Le build Maven échoue sur une NullPointerException dans le module de paiement. Quelle est ta prochaine action ?",
            "La demande de l'utilisateur est ambiguë et nécessite une validation humaine avant de continuer.");

    @Test
    void mesureLaConformiteJsonParModele() throws Exception {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = envOr("OPENROUTER_BASE_URL", "https://openrouter.ai/api");
        int iterations = Integer.parseInt(envOr("SPIKE_ITERATIONS", "20"));
        List<String> models = parseModels(System.getenv("SPIKE_MODELS"));

        String systemPrompt = SystemPromptComposer.base().compose();
        AgentResponseParser parser = new AgentResponseParser();

        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build();

        List<ModelReport> reports = new ArrayList<>();
        for (String model : models) {
            reports.add(runModel(api, model, systemPrompt, parser, iterations));
        }

        String markdown = renderMarkdown(reports, iterations);
        System.out.println(markdown);
        Path out = Path.of("target", "spike-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, markdown);
        System.out.println("\nRapport écrit dans : " + out.toAbsolutePath());
    }

    private ModelReport runModel(OpenAiApi api, String model, String systemPrompt,
                                 AgentResponseParser parser, int iterations) {
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.2).build())
                .build();
        ChatClient client = ChatClient.builder(chatModel).build();

        ModelReport report = new ModelReport(model);
        for (int i = 0; i < iterations; i++) {
            for (String userPrompt : PROMPTS) {
                report.total++;
                try {
                    ChatResponse response = client.prompt()
                            .system(systemPrompt)
                            .user(userPrompt)
                            .call()
                            .chatResponse();
                    String text = text(response);
                    FinishReason finishReason = finishReason(response);
                    report.finishReasons.merge(finishReason, 1, Integer::sum);

                    ParseOutcome outcome = parser.parse(text, finishReason);
                    if (outcome instanceof ParseOutcome.Parsed) {
                        report.conformant++;
                    } else {
                        ParseOutcome.Failure failure = (ParseOutcome.Failure) outcome;
                        report.failures.merge(failure.mode(), 1, Integer::sum);
                        if (report.samples.size() < 3) {
                            report.samples.add("[" + failure.mode() + "] "
                                    + truncate(text, 160));
                        }
                    }
                } catch (RuntimeException e) {
                    report.errors++;
                    if (report.samples.size() < 3) {
                        report.samples.add("[CALL_ERROR] " + truncate(e.getMessage(), 160));
                    }
                }
            }
        }
        return report;
    }

    // --- rendu du rapport ---------------------------------------------------

    private String renderMarkdown(List<ModelReport> reports, int iterations) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Spike JSON go/no-go — étape 3\n\n");
        sb.append("Seuil de conformité visé : **>").append((int) (THRESHOLD * 100))
                .append("%** de réponses parsées en `AgentResponse`.\n\n");
        sb.append("| Modèle | N | Conformes | Taux | INVALID_JSON | UNKNOWN_STATUS | TRUNCATED | EMPTY | Erreurs | Verdict |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|:---:|\n");
        for (ModelReport r : reports) {
            double rate = r.total == 0 ? 0 : (double) r.conformant / r.total;
            sb.append("| ").append(r.model)
                    .append(" | ").append(r.total)
                    .append(" | ").append(r.conformant)
                    .append(" | ").append(String.format("%.1f%%", rate * 100))
                    .append(" | ").append(r.failures.getOrDefault(ParseOutcome.Mode.INVALID_JSON, 0))
                    .append(" | ").append(r.failures.getOrDefault(ParseOutcome.Mode.UNKNOWN_STATUS, 0))
                    .append(" | ").append(r.failures.getOrDefault(ParseOutcome.Mode.TRUNCATED, 0))
                    .append(" | ").append(r.failures.getOrDefault(ParseOutcome.Mode.EMPTY, 0))
                    .append(" | ").append(r.errors)
                    .append(" | ").append(rate >= THRESHOLD ? "✅ GO" : "❌ NO-GO")
                    .append(" |\n");
        }
        sb.append("\n## finishReason par modèle\n\n");
        for (ModelReport r : reports) {
            sb.append("- **").append(r.model).append("** : ").append(r.finishReasons).append("\n");
        }
        sb.append("\n## Échantillons d'échec (diagnostic)\n\n");
        for (ModelReport r : reports) {
            sb.append("- **").append(r.model).append("**\n");
            if (r.samples.isEmpty()) {
                sb.append("    - (aucun)\n");
            }
            for (String sample : r.samples) {
                sb.append("    - `").append(sample.replace("`", "'")).append("`\n");
            }
        }
        sb.append("\n_").append(iterations).append(" itérations × ").append(PROMPTS.size())
                .append(" prompts par modèle._\n");
        return sb.toString();
    }

    private static String text(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private static FinishReason finishReason(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getMetadata() == null) {
            return FinishReason.UNKNOWN;
        }
        return FinishReason.fromProvider(response.getResult().getMetadata().getFinishReason());
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<String> parseModels(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MODELS;
        }
        return List.of(raw.split("\\s*,\\s*"));
    }

    /** Statistiques accumulées pour un modèle. */
    private static final class ModelReport {
        final String model;
        int total;
        int conformant;
        int errors;
        final Map<ParseOutcome.Mode, Integer> failures = new EnumMap<>(ParseOutcome.Mode.class);
        final Map<FinishReason, Integer> finishReasons = new EnumMap<>(FinishReason.class);
        final List<String> samples = new ArrayList<>();

        ModelReport(String model) {
            this.model = model;
        }
    }
}
