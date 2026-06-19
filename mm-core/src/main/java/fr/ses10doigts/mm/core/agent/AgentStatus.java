package fr.ses10doigts.mm.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Statut d'un agent — le cœur de la machine à états (ADR-006).
 *
 * <p>Le champ {@code status} de {@link AgentResponse} se sérialise/désérialise vers
 * ces valeurs. Mapping JSON <strong>strict et déterministe</strong> : minuscules
 * pour tous les statuts, {@code "KO"} en majuscules. Toute valeur inconnue ou
 * absente lève {@link IllegalArgumentException}.</p>
 *
 * <p><strong>Report étape 3</strong> : l'échec de mapping (ex. {@code "Done"} au lieu
 * de {@code "done"}, PB-08) doit être traité comme {@link #TROUBLE} par la machine à
 * états, avec retry sur prompt renforcé. Aucune tolérance NLP ici.</p>
 */
public enum AgentStatus {

    /** Planifié, pas encore démarré. */
    PENDING("pending"),
    /** En cours, nouvelle itération de boucle. */
    RUNNING("running"),
    /** Terminé avec succès. */
    DONE("done"),
    /** Attend une validation externe ou une ressource. */
    BLOCKED("blocked"),
    /** Difficultés — déclenche l'error-triggered retrieval (étape 3). */
    TROUBLE("trouble"),
    /** Échec définitif. */
    KO("KO");

    private final String json;

    AgentStatus(String json) {
        this.json = json;
    }

    /** Valeur telle qu'écrite dans le JSON de sortie du LLM. */
    @JsonValue
    public String json() {
        return json;
    }

    /**
     * Désérialisation stricte depuis le JSON.
     *
     * @throws IllegalArgumentException si la valeur est nulle ou inconnue
     */
    @JsonCreator
    public static AgentStatus fromJson(String value) {
        if (value == null) {
            throw new IllegalArgumentException("AgentStatus manquant");
        }
        for (AgentStatus status : values()) {
            if (status.json.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("AgentStatus inconnu : " + value);
    }
}
