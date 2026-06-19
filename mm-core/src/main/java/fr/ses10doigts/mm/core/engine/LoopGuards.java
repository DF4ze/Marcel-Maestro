package fr.ses10doigts.mm.core.engine;

import fr.ses10doigts.mm.core.agent.AgentStatus;

/**
 * Garde-fous d'une exécution de boucle : bornage et détection de boucle infinie
 * (étape 3, livrable 5 ; PB-07 cas 4).
 *
 * <p><strong>À état, non thread-safe, à usage unique :</strong> une instance suit une et
 * une seule exécution de {@link AgentLoop}. Trois compteurs :</p>
 * <ul>
 *   <li>itérations globales → {@code maxIterations} ;</li>
 *   <li>statuts {@code trouble} cumulés → {@code maxTroubleRetries} ;</li>
 *   <li>longueur de la série du même statut consécutif → {@code maxSameStatusStreak}
 *       (un LLM qui alterne ou répète indéfiniment est arrêté même sous la borne globale).</li>
 * </ul>
 */
public final class LoopGuards {

    private final LoopConfig config;

    private int iterations;
    private int troubleCount;
    private AgentStatus lastStatus;
    private int sameStatusStreak;

    public LoopGuards(LoopConfig config) {
        this.config = config;
    }

    /**
     * Tente de démarrer une itération (à appeler <em>avant</em> l'appel LLM).
     *
     * @return {@code true} si l'itération est dans la borne {@code maxIterations} ;
     *         {@code false} si la borne est déjà atteinte (la boucle doit conclure en {@code KO}).
     */
    public boolean tryStartIteration() {
        if (iterations >= config.maxIterations()) {
            return false;
        }
        iterations++;
        return true;
    }

    /**
     * Enregistre le statut obtenu à l'itération courante (à appeler <em>après</em> le
     * parsing) et rend le verdict des garde-fous.
     *
     * @param status statut effectif (un échec de parsing est passé comme {@link
     *               AgentStatus#TROUBLE} par la boucle)
     */
    public GuardVerdict recordStatus(AgentStatus status) {
        if (status == AgentStatus.TROUBLE) {
            troubleCount++;
            if (troubleCount > config.maxTroubleRetries()) {
                return GuardVerdict.KO_TROUBLE_EXCEEDED;
            }
        }

        if (status == lastStatus) {
            sameStatusStreak++;
        } else {
            sameStatusStreak = 1;
            lastStatus = status;
        }
        if (sameStatusStreak >= config.maxSameStatusStreak()) {
            return GuardVerdict.KO_INFINITE_LOOP;
        }

        return GuardVerdict.CONTINUE;
    }

    /** Verdict quand {@link #tryStartIteration()} a refusé (borne globale atteinte). */
    public GuardVerdict maxIterationsVerdict() {
        return GuardVerdict.KO_MAX_ITERATIONS;
    }

    /** Nombre d'itérations effectivement démarrées. */
    public int iterations() {
        return iterations;
    }

    /** Nombre de statuts {@code trouble} cumulés. */
    public int troubleCount() {
        return troubleCount;
    }
}
