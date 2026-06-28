package fr.ses10doigts.mm.app.specialist.coding;

/**
 * Adapter du spécialiste Codex pour le {@code Dispatcher} historique.
 */
public class CodexAgentFactoryAdapter extends AbstractCodingAgentFactoryAdapter {

    /**
     * Construit l'adapter du spécialiste Codex.
     *
     * @param codexAgent agent CLI Codex
     * @param missionMapper convertisseur moteur -> mission coding
     * @param outcomeMapper convertisseur rapport -> outcome moteur
     */
    public CodexAgentFactoryAdapter(CodexAgent codexAgent,
                                    TaskMessageCodingMissionMapper missionMapper,
                                    CodingAgentOutcomeMapper outcomeMapper) {
        super(codexAgent, missionMapper, outcomeMapper);
    }

    /**
     * Retourne l'identifiant attendu dans le champ {@code assignee}.
     *
     * @return {@code "codex"}
     */
    @Override
    public String agentId() {
        return "codex";
    }

    /**
     * Catégorie métier de repli portée par cet adapter.
     *
     * @return {@link TaskCategory#BUILD}
     */
    @Override
    protected TaskCategory category() {
        return TaskCategory.BUILD;
    }
}
