package fr.ses10doigts.mm.core.hitl;

/**
 * Port de validation humaine (ADR-005).
 *
 * <p>L'hôte choisit le canal : console, Telegram, web… Le moteur ne connaît que ce
 * contrat. {@link #ask(HitlRequest)} est bloquant (attend la décision) ;
 * {@link #notify(AgentNotification)} est à sens unique.</p>
 */
public interface HumanInteraction {

    /**
     * Demande une validation humaine et attend la décision.
     *
     * @param request demande de consentement
     * @return décision de l'humain
     */
    ConsentDecision ask(HitlRequest request);

    /**
     * Notifie l'humain sans attendre de réponse.
     *
     * @param notification notification à pousser
     */
    void notify(AgentNotification notification);
}
