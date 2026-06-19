package fr.ses10doigts.mm.core.engine.parse;

import fr.ses10doigts.mm.core.agent.AgentResponse;

/**
 * Résultat <strong>typé</strong> d'un parsing de sortie LLM (PB-09).
 *
 * <p>Type scellé : soit un {@link Parsed} (succès), soit un {@link Failure} portant le
 * mode de défaillance. La boucle agentique traite tout {@link Failure} comme le statut
 * {@code trouble} et relance sur prompt renforcé — sans jamais interpréter le texte libre.</p>
 */
public sealed interface ParseOutcome permits ParseOutcome.Parsed, ParseOutcome.Failure {

    /** {@code true} si le parsing a produit un {@link AgentResponse} exploitable. */
    boolean isSuccess();

    /** Parsing réussi. */
    record Parsed(AgentResponse response) implements ParseOutcome {
        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    /** Parsing en échec, avec son mode et un détail diagnostique (pour le journal). */
    record Failure(Mode mode, String detail) implements ParseOutcome {
        @Override
        public boolean isSuccess() {
            return false;
        }
    }

    /** Modes de défaillance observés en pratique (PB-09). */
    enum Mode {
        /** Réponse nulle ou vide (timeout réseau, modèle surchargé). */
        EMPTY,
        /** Réponse tronquée détectée via {@code finishReason = LENGTH}. */
        TRUNCATED,
        /** Pas de JSON exploitable, ou JSON syntaxiquement invalide. */
        INVALID_JSON,
        /** JSON syntaxiquement valide mais champ {@code status} absent ou inconnu (PB-08). */
        UNKNOWN_STATUS
    }
}
