package fr.ses10doigts.mm.core.engine.parse;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.core.agent.AgentResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse la sortie texte du LLM en {@link AgentResponse}, de façon déterministe et avec
 * des filets pour les cas dégradés (étape 3, livrable 3 ; PB-08, PB-09).
 *
 * <p>Pipeline, dans l'ordre :</p>
 * <ol>
 *   <li>réponse nulle/vide → {@link ParseOutcome.Mode#EMPTY} ;</li>
 *   <li>{@code finishReason = LENGTH} → {@link ParseOutcome.Mode#TRUNCATED} (JSON
 *       probablement incomplet, on ne tente même pas de parser) ;</li>
 *   <li>désérialisation Jackson stricte du texte entier ;</li>
 *   <li>en cas d'échec, extraction regex du premier bloc <code>{…}</code> (mode DOTALL)
 *       puis nouvelle tentative ;</li>
 *   <li>statut absent/inconnu → {@link ParseOutcome.Mode#UNKNOWN_STATUS} ; reste →
 *       {@link ParseOutcome.Mode#INVALID_JSON}.</li>
 * </ol>
 *
 * <p>Le mapper est <em>tolérant aux champs inconnus</em> (robustesse : un champ surnuméraire
 * du LLM n'invalide pas la réponse) mais <em>strict sur l'enum</em> {@code status} (via le
 * {@code @JsonCreator} de {@link fr.ses10doigts.mm.core.agent.AgentStatus} — PB-08). Aucune
 * heuristique NLP : ce qui n'est pas un JSON conforme est une erreur.</p>
 *
 * <p>Immuable et thread-safe (l'{@link ObjectMapper} est configuré une fois puis en
 * lecture seule).</p>
 */
public final class AgentResponseParser {

    /** Premier bloc allant de la première accolade ouvrante à la dernière fermante. */
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private final ObjectMapper mapper;

    public AgentResponseParser() {
        this(new ObjectMapper());
    }

    /**
     * @param mapper mapper à utiliser ; il est reconfiguré pour ignorer les champs inconnus.
     */
    public AgentResponseParser(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * @param rawText      texte brut renvoyé par le LLM (peut être {@code null})
     * @param finishReason raison d'arrêt normalisée (jamais {@code null} ; passer
     *                     {@link FinishReason#UNKNOWN} si indisponible)
     * @return un {@link ParseOutcome} typé, jamais {@code null} et sans exception levée
     */
    public ParseOutcome parse(String rawText, FinishReason finishReason) {
        if (rawText == null || rawText.isBlank()) {
            return new ParseOutcome.Failure(ParseOutcome.Mode.EMPTY, "réponse vide");
        }
        if (finishReason == FinishReason.LENGTH) {
            return new ParseOutcome.Failure(ParseOutcome.Mode.TRUNCATED,
                    "finishReason=LENGTH : réponse tronquée");
        }

        ParseOutcome direct = tryDeserialize(rawText);
        if (direct != null) {
            return direct;
        }

        Matcher matcher = JSON_BLOCK.matcher(rawText);
        if (matcher.find()) {
            ParseOutcome extracted = tryDeserialize(matcher.group());
            if (extracted != null) {
                return extracted;
            }
        }
        return new ParseOutcome.Failure(ParseOutcome.Mode.INVALID_JSON,
                "aucun bloc JSON exploitable");
    }

    /**
     * Tente une désérialisation. Retourne {@code null} si le texte n'est pas du JSON
     * d'objet valide (pour laisser la chance au filet regex). Retourne un {@link
     * ParseOutcome.Failure#UNKNOWN_STATUS} si le JSON est valide mais le statut absent/inconnu
     * (inutile de réessayer le regex dans ce cas).
     */
    private ParseOutcome tryDeserialize(String candidate) {
        try {
            AgentResponse response = mapper.readValue(candidate, AgentResponse.class);
            if (response.status() == null) {
                return new ParseOutcome.Failure(ParseOutcome.Mode.UNKNOWN_STATUS,
                        "champ status absent");
            }
            return new ParseOutcome.Parsed(response);
        } catch (Exception e) {
            if (isUnknownStatus(e)) {
                return new ParseOutcome.Failure(ParseOutcome.Mode.UNKNOWN_STATUS,
                        rootMessage(e));
            }
            return null;
        }
    }

    /**
     * Le {@code @JsonCreator} d'{@code AgentStatus} lève une {@link IllegalArgumentException}
     * (« AgentStatus inconnu … » / « AgentStatus manquant »), encapsulée par Jackson. On
     * la distingue d'une vraie erreur de syntaxe JSON pour qualifier correctement l'échec.
     */
    private boolean isUnknownStatus(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException && t.getMessage() != null
                    && t.getMessage().contains("AgentStatus")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
