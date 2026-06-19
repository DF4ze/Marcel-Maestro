package fr.ses10doigts.mm.core.prompt;

/**
 * Point d'extension du system prompt (PB-04 Q1).
 *
 * <p>L'hôte fournit des instructions <strong>additionnelles</strong> (contexte métier,
 * termes du domaine) ajoutées au prompt de base du noyau. Il ne peut pas remplacer le
 * prompt de base (qui impose le format {@code AgentResponse} et la boucle).</p>
 *
 * <p><strong>Report étape 3</strong> : le texte du prompt de base et la composition
 * (assemblage base + contributions) seront écrits avec la boucle agentique. Ici, on ne
 * fige que le seam ({@code contribution()}, sans ordre d'application).</p>
 */
public interface SystemPromptExtension {

    /** Fragment d'instructions à concaténer au prompt de base. */
    String contribution();
}
