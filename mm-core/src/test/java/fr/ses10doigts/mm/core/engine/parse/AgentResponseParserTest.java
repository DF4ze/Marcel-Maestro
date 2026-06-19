package fr.ses10doigts.mm.core.engine.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import fr.ses10doigts.mm.core.agent.AgentStatus;
import org.junit.jupiter.api.Test;

/**
 * Vérifie le parsing déterministe et les cas dégradés (étape 3, livrable 3 ; PB-08, PB-09).
 * Aucune interprétation NLP : un format non conforme produit toujours un {@link
 * ParseOutcome.Failure}.
 */
class AgentResponseParserTest {

    private final AgentResponseParser parser = new AgentResponseParser();

    @Test
    void parseLeJsonNominal() {
        String json = """
                {"status":"done","reason":"fini","output":"42","tool_calls":[],"sub_tasks":[]}""";

        ParseOutcome outcome = parser.parse(json, FinishReason.STOP);

        ParseOutcome.Parsed parsed = assertInstanceOf(ParseOutcome.Parsed.class, outcome);
        assertEquals(AgentStatus.DONE, parsed.response().status());
        assertEquals("42", parsed.response().output());
    }

    @Test
    void extraitLeBlocJsonNoyeDansDuTexte() {
        // Mode de défaillance PB-09 : texte avant/après le JSON.
        String text = "Voici ma réponse : {\"status\":\"running\",\"reason\":\"go\"} merci.";

        ParseOutcome outcome = parser.parse(text, FinishReason.STOP);

        ParseOutcome.Parsed parsed = assertInstanceOf(ParseOutcome.Parsed.class, outcome);
        assertEquals(AgentStatus.RUNNING, parsed.response().status());
    }

    @Test
    void tolereUnChampInconnuMaisPasUnStatutInconnu() {
        ParseOutcome ok = parser.parse(
                "{\"status\":\"done\",\"extra\":\"ignored\"}", FinishReason.STOP);
        assertInstanceOf(ParseOutcome.Parsed.class, ok);

        ParseOutcome bad = parser.parse("{\"status\":\"Done\"}", FinishReason.STOP);
        ParseOutcome.Failure failure = assertInstanceOf(ParseOutcome.Failure.class, bad);
        assertEquals(ParseOutcome.Mode.UNKNOWN_STATUS, failure.mode());
    }

    @Test
    void detecteLaTroncatureViaFinishReason() {
        // JSON incomplet + finishReason=LENGTH → tronqué, on ne tente pas de parser.
        ParseOutcome outcome = parser.parse("{\"status\":\"run", FinishReason.LENGTH);

        ParseOutcome.Failure failure = assertInstanceOf(ParseOutcome.Failure.class, outcome);
        assertEquals(ParseOutcome.Mode.TRUNCATED, failure.mode());
    }

    @Test
    void reponseVideEstEMPTY() {
        assertEquals(ParseOutcome.Mode.EMPTY,
                ((ParseOutcome.Failure) parser.parse(null, FinishReason.UNKNOWN)).mode());
        assertEquals(ParseOutcome.Mode.EMPTY,
                ((ParseOutcome.Failure) parser.parse("   ", FinishReason.STOP)).mode());
    }

    @Test
    void jsonIrrecuperableEstINVALID_JSON() {
        ParseOutcome outcome = parser.parse("désolé, pas de json ici", FinishReason.STOP);

        ParseOutcome.Failure failure = assertInstanceOf(ParseOutcome.Failure.class, outcome);
        assertEquals(ParseOutcome.Mode.INVALID_JSON, failure.mode());
    }

    @Test
    void jsonSansAccoladeFermanteEstINVALID_JSON() {
        // Robustesse : le parser ne lève jamais ; le regex ne matche pas, échec propre.
        ParseOutcome outcome = parser.parse("{bad", FinishReason.STOP);

        ParseOutcome.Failure failure = assertInstanceOf(ParseOutcome.Failure.class, outcome);
        assertEquals(ParseOutcome.Mode.INVALID_JSON, failure.mode());
        assertFalse(outcome.isSuccess());
    }
}
