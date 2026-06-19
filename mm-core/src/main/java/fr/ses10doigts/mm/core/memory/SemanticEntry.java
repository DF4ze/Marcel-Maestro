package fr.ses10doigts.mm.core.memory;

/**
 * Entrée de mémoire procédurale (couture différée).
 *
 * <p><strong>Forme minimale</strong> (validée à l'étape 2). À enrichir au moment de
 * l'implémentation réelle (apprentissage différé) — ex. score de fraîcheur, embedding,
 * métadonnées. Voir note de report étape 3.</p>
 *
 * @param id      identifiant de l'entrée
 * @param content contenu (pattern, solution d'erreur…)
 * @param scope   portée
 * @param tenant  identifiant artisan ; {@code "default"} en MVP
 */
public record SemanticEntry(
        String id,
        String content,
        String scope,
        String tenant) {
}
