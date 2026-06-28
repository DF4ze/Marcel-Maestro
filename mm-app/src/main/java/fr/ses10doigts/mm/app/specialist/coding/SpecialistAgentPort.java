package fr.ses10doigts.mm.app.specialist.coding;

/**
 * Port d'entrée pour tout agent spécialiste du consortium Marcel.
 *
 * <p>Le Chef et le Dispatcher ne connaissent que cette interface.</p>
 */
public interface SpecialistAgentPort {

    /**
     * Exécute une mission spécialiste dans un contexte Marcel donné.
     *
     * @param task mission atomique déléguée par l'orchestrateur
     * @param context contexte projet et mémoire injectés dans le prompt
     * @return rapport structuré retourné par l'agent
     */
    AgentReport execute(AgentTask task, MarcelContext context);
}
