package fr.ses10doigts.mm.starter.hitl;

import fr.ses10doigts.mm.core.hitl.HumanInteraction;

/**
 * Extension du port {@link HumanInteraction} ajoutant la possibilité d'annuler
 * un {@code ask()} en cours depuis un autre thread.
 *
 * <p>Utilisé par {@link CompositeHumanInteraction} pour débloquer le canal perdant
 * quand le premier canal répond dans une course parallèle.</p>
 *
 * <p>Vit dans le starter (pas dans mm-core) : c'est un détail d'implémentation
 * de la coexistence multi-canal, pas un contrat du noyau.</p>
 */
public interface CancellableHumanInteraction extends HumanInteraction {

    /**
     * Annule un {@link #ask} en cours. L'implémentation doit débloquer le thread
     * en attente le plus vite possible. Le résultat retourné par le {@code ask()}
     * annulé est ignoré par le Composite.
     */
    void cancelPendingAsk();
}
