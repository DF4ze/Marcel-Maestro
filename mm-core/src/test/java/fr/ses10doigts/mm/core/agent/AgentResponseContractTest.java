package fr.ses10doigts.mm.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Vérifie le CONTRAT de (dé)sérialisation JSON — pas la boucle (étape 3).
 *
 * <p>Garantit que « la sortie JSON EST la machine à états » (ADR-006) repose sur un
 * mapping déterministe : noms snake_case, statuts minuscules sauf {@code KO}, et
 * rejet strict des valeurs inconnues.</p>
 */
class AgentResponseContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialiseLeContratDeSortieNominal() throws Exception {
        String json = """
            {
              "status": "running",
              "reason": "en cours",
              "output": null,
              "tool_calls": [{"tool": "build", "params": {"clean": true}}],
              "sub_tasks": [{"assignee": "java-spring", "description": "corriger le test"}]
            }
            """;

        AgentResponse response = mapper.readValue(json, AgentResponse.class);

        assertEquals(AgentStatus.RUNNING, response.status());
        assertEquals("en cours", response.reason());
        assertNull(response.output());
        assertEquals("build", response.toolCalls().get(0).tool());
        assertEquals(Boolean.TRUE, response.toolCalls().get(0).params().get("clean"));
        assertEquals("java-spring", response.subTasks().get(0).assignee());
    }

    @Test
    void serialiseAvecLesBonsNomsDeChamps() throws Exception {
        AgentResponse response = new AgentResponse(
                AgentStatus.KO, "stopped by user", null, null, null);

        String json = mapper.writeValueAsString(response);

        assertTrue(json.contains("\"status\":\"KO\""), json);
        assertTrue(json.contains("\"tool_calls\""), json);
        assertTrue(json.contains("\"sub_tasks\""), json);
    }

    @Test
    void mappeChaqueStatutVersSaValeurJson() {
        assertEquals("pending", AgentStatus.PENDING.json());
        assertEquals("running", AgentStatus.RUNNING.json());
        assertEquals("done", AgentStatus.DONE.json());
        assertEquals("blocked", AgentStatus.BLOCKED.json());
        assertEquals("trouble", AgentStatus.TROUBLE.json());
        assertEquals("KO", AgentStatus.KO.json());
    }

    @Test
    void rejetteUnStatutInconnu() {
        // PB-08 : "Done" (majuscule) ne matche pas "done" — erreur, jamais d'interprétation NLP.
        // (Jackson encapsule l'IllegalArgumentException du @JsonCreator en JsonMappingException.)
        assertThrows(JsonMappingException.class,
                () -> mapper.readValue("{\"status\":\"Done\"}", AgentResponse.class));
    }

    @Test
    void rejetteUneValeurDeStatutDirecteInconnue() {
        assertThrows(IllegalArgumentException.class, () -> AgentStatus.fromJson("wat"));
        assertThrows(IllegalArgumentException.class, () -> AgentStatus.fromJson(null));
    }
}
