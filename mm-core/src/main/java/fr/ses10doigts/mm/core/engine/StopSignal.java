package fr.ses10doigts.mm.core.engine;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Signal d'arrêt coopératif d'une boucle agentique (étape 3, livrable 5 ; PB-07 cas 1 ;
 * architecture cible §4).
 *
 * <p>Encapsule un {@link AtomicBoolean}. {@link AgentLoop} ne consulte ce flag
 * qu'<strong>entre deux opérations atomiques</strong> : juste avant un appel LLM et juste
 * après la réception d'un résultat — jamais en milieu d'opération (pas d'interruption au
 * milieu d'une écriture fichier ou d'une transaction). Quand le flag est levé, la boucle
 * conclut proprement en {@code KO} avec la raison « stopped by user ».</p>
 *
 * <p>Thread-safe : le déclencheur du STOP (futur Dispatcher, étape 7) et le thread de la
 * boucle accèdent au flag depuis des threads différents.</p>
 */
public final class StopSignal {

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** Signal qui n'est jamais déclenché (pratique pour les appels synchrones simples). */
    public static StopSignal never() {
        return new StopSignal();
    }

    /** Lève le flag d'arrêt. Idempotent. Appelable depuis n'importe quel thread. */
    public void stop() {
        stopped.set(true);
    }

    /** {@code true} si un arrêt a été demandé. */
    public boolean isStopped() {
        return stopped.get();
    }
}
