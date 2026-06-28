package fr.ses10doigts.mm.app.specialist.coding;

/**
 * Adapter du spécialiste Claude pour le {@code Dispatcher} historique.
 */
public class ClaudeAgentFactoryAdapter extends AbstractCodingAgentFactoryAdapter {

    /**
     * Construit l'adapter du spécialiste Claude.
     *
     * @param claudeCodeAgent agent CLI Claude
     * @param missionMapper convertisseur moteur -> mission coding
     * @param outcomeMapper convertisseur rapport -> outcome moteur
     */
    public ClaudeAgentFactoryAdapter(ClaudeCodeAgent claudeCodeAgent,
                                     TaskMessageCodingMissionMapper missionMapper,
                                     CodingAgentOutcomeMapper outcomeMapper) {
        super(claudeCodeAgent, missionMapper, outcomeMapper);
    }

    /**
     * Retourne l'identifiant attendu dans le champ {@code assignee}.
     *
     * @return {@code "claude"}
     */
    @Override
    public String agentId() {
        return "claude";
    }

    /**
     * Catégorie métier de repli portée par cet adapter.
     *
     * @return {@link TaskCategory#CODING}
     */
    @Override
    protected TaskCategory category() {
        return TaskCategory.CODING;
    }
}
