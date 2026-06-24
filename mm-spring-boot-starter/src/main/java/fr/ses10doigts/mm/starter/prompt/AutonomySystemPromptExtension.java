package fr.ses10doigts.mm.starter.prompt;

import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import lombok.extern.slf4j.Slf4j;

/**
 * Extension du system prompt qui module l'autonomie de Cortex face au statut
 * {@code "blocked"} (HITL de clarification).
 *
 * <p>Le prompt de base ({@link fr.ses10doigts.mm.core.prompt.SystemPromptComposer})
 * pose le <em>principe d'initiative</em> : avant de bloquer, l'agent tente d'obtenir
 * l'information lui-même, produit ce qu'on lui demande de produire, et agit sur les
 * actions réversibles. Cette extension <strong>déplace le curseur</strong> de ce
 * principe via un niveau d'autonomie {@code 0..10} configurable
 * ({@code mm.autonomy.level}) :</p>
 * <ul>
 *   <li>{@code 0} → demande très souvent (régime prudent) ;</li>
 *   <li>{@code 5} → équilibré (agit sur le réversible, demande sur l'important) ;</li>
 *   <li>{@code 10} → quasi totalement autonome (ne bloque qu'en cas d'impossibilité
 *       stricte).</li>
 * </ul>
 *
 * <p><strong>Hors périmètre&nbsp;:</strong> le consentement des outils risqués
 * ({@code RiskLevel.HIGH} via {@code ToolExecutionGuard}) n'est PAS affecté par ce
 * curseur — il reste toujours demandé. Ce réglage ne concerne que le {@code "blocked"}
 * de clarification (information / décision, hors outil).</p>
 */
@Slf4j
public class AutonomySystemPromptExtension implements SystemPromptExtension {

    private static final int MIN = 0;
    private static final int MAX = 10;

    /** Niveau effectif, borné à {@code [0, 10]}. */
    private final int level;

    /**
     * @param level niveau d'autonomie demandé ; toute valeur hors {@code [0, 10]} est
     *              ramenée dans les bornes
     */
    public AutonomySystemPromptExtension(int level) {
        int clamped = Math.clamp(level, MIN, MAX);
        if (clamped != level) {
            log.warn("mm.autonomy.level={} hors bornes [{},{}], ramené à {}",
                    level, MIN, MAX, clamped);
        }
        this.level = clamped;
    }

    /** @return le niveau d'autonomie effectif (borné). */
    public int level() {
        return level;
    }

    @Override
    public String contribution() {
        return """
                NIVEAU D'AUTONOMIE : %d/10
                Ce curseur règle OÙ placer la frontière du PRINCIPE D'INITIATIVE entre
                « agis seul » et « demande à l'humain » (statut "blocked"). Il ne change
                RIEN au consentement des outils risqués, qui reste toujours demandé.
                %s
                Quand tu agis sur une hypothèse que tu as choisie toi-même, mentionne-la
                explicitement dans "reason" (et dans "output" si status="done") pour que
                l'humain puisse te corriger après coup.""".formatted(level, rubric());
    }

    /**
     * Traduit le niveau numérique en consigne comportementale concrète — un LLM suit
     * mal un chiffre nu, bien mieux une règle décrite.
     */
    private String rubric() {
        if (level <= 2) {
            return """
                    Régime PRUDENT : émets "blocked" dès qu'il y a la moindre ambiguïté ou
                    un choix non trivial. N'invente que le strictement évident ; préfère
                    poser la question plutôt que supposer.""";
        }
        if (level <= 6) {
            return """
                    Régime ÉQUILIBRÉ : agis seul sur tout ce qui est trivial ou réversible
                    (produire un texte demandé, choisir un nom, lire un fichier). Émets
                    "blocked" dès qu'un choix est important, irréversible ou à conséquence
                    réelle.""";
        }
        return """
                Régime AUTONOME : n'émets "blocked" que si avancer est STRICTEMENT
                impossible — information impossible à obtenir ou à inventer (secret, fait
                externe vérifiable). Pour tout le reste, prends la meilleure hypothèse
                raisonnable et agis, puis signale ton hypothèse.""";
    }
}
