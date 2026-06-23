package fr.ses10doigts.mm.core.prompt;

import java.util.List;

/**
 * Compose le system prompt de Cortex : prompt de base du noyau + contributions de
 * l'hôte (étape 3, livrable 2 ; PB-04 Q1).
 *
 * <p><strong>Le prompt de base vit dans le noyau et n'est PAS remplaçable.</strong> Il
 * impose le contrat de sortie {@link fr.ses10doigts.mm.core.agent.AgentResponse} (la
 * sortie JSON <em>est</em> la machine à états, ADR-006). L'hôte ne peut qu'<em>ajouter</em>
 * des instructions (contexte métier, termes du domaine) via des beans
 * {@link SystemPromptExtension} ; il ne peut ni retirer ni réécrire le contrat.</p>
 *
 * <p>Le composer fournit aussi les deux fragments de relance utilisés par la boucle :
 * la {@linkplain #continuation() continuation} (statut {@code running}) et le
 * {@linkplain #reinforcedRetry() prompt renforcé} (statut {@code trouble} / échec de
 * parsing, PB-09). Ces fragments sont des <em>données</em>, jamais de l'interprétation NLP.</p>
 *
 * <p>Immuable et sans état : sûr à partager comme singleton.</p>
 */
public final class SystemPromptComposer {

    /**
     * Prompt de base imposé par le noyau. Ne mentionne aucun métier : il ne décrit que
     * le rôle de planificateur (Cortex) et le contrat de sortie JSON.
     */
    static final String BASE_PROMPT = """
            Tu es Cortex, l'agent planificateur de Marcel Maestro.
            Tu raisonnes, tu planifies et tu délègues ; tu ne rédiges jamais de prose libre.

            RÈGLE ABSOLUE DE SORTIE
            Tu réponds EXCLUSIVEMENT par un unique objet JSON valide, sans aucun texte
            avant ni après, sans bloc de code Markdown, sans commentaire. La forme est :

            {
              "status": "running|done|blocked|trouble|KO",
              "reason": "description courte de la situation",
              "output": "résultat final si status=done, sinon null",
              "tool_calls": [{"tool": "nom_outil", "params": {}}],
              "sub_tasks": [{"assignee": "specialist_id", "description": "..."}]
            }

            SÉMANTIQUE DES STATUTS (valeurs exactes, sensibles à la casse)
            - "running"  : la tâche progresse, une nouvelle itération est nécessaire.
            - "done"     : la tâche est terminée ; "output" contient le résultat.
            - "blocked"  : tu attends une validation humaine AVANT d'exécuter un outil risqué.
                           Remplis OBLIGATOIREMENT "tool_calls" avec l'outil que tu veux exécuter
                           et "reason" avec ce que tu veux faire et pourquoi. L'humain verra
                           l'outil ET ses paramètres exacts pour prendre sa décision.
            - "trouble"  : difficulté rencontrée ; tu réessaies une autre approche.
            - "KO"       : échec définitif, la tâche ne peut pas aboutir.

            CONTRAINTES
            - "status" prend l'une des valeurs ci-dessus, en respectant exactement la casse
              ("done" et non "Done", "KO" en majuscules).
            - "output" vaut null tant que "status" n'est pas "done".
            - "tool_calls" et "sub_tasks" sont des tableaux (éventuellement vides).
            - Seul Cortex crée des sous-tâches ; un spécialiste ne planifie jamais.
            """;

    private static final String CONTINUATION = """
            Poursuis la tâche à partir de l'état courant.
            Rappel : réponds UNIQUEMENT par l'objet JSON AgentResponse, rien d'autre.""";

    private static final String REINFORCED_RETRY = """
            Ta réponse précédente n'était pas un objet JSON AgentResponse exploitable.
            Réponds MAINTENANT par un unique objet JSON valide, strictement conforme au
            schéma, sans aucun texte avant ni après, sans bloc Markdown.
            Le champ "status" doit valoir exactement l'une de : running, done, blocked,
            trouble, KO.""";

    private final List<SystemPromptExtension> extensions;

    /**
     * @param extensions contributions de l'hôte ; {@code null} est toléré (aucune
     *                   contribution). La liste est copiée défensivement.
     */
    public SystemPromptComposer(List<SystemPromptExtension> extensions) {
        this.extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** Crée un composer sans aucune contribution d'hôte. */
    public static SystemPromptComposer base() {
        return new SystemPromptComposer(List.of());
    }

    /**
     * Assemble le prompt complet : base du noyau, puis chaque contribution non vide de
     * l'hôte, séparées par une ligne blanche. La base est toujours en tête et n'est
     * jamais remplacée.
     */
    public String compose() {
        StringBuilder sb = new StringBuilder(BASE_PROMPT);
        for (SystemPromptExtension extension : extensions) {
            if (extension == null) {
                continue;
            }
            String contribution = extension.contribution();
            if (contribution != null && !contribution.isBlank()) {
                sb.append("\n\n").append(contribution.strip());
            }
        }
        return sb.toString();
    }

    /** Fragment de relance injecté comme message utilisateur sur statut {@code running}. */
    public String continuation() {
        return CONTINUATION;
    }

    /**
     * Fragment de relance injecté comme message utilisateur quand la réponse précédente
     * était {@code trouble} ou n'a pas pu être parsée (PB-09, retry sur prompt renforcé).
     */
    public String reinforcedRetry() {
        return REINFORCED_RETRY;
    }
}
