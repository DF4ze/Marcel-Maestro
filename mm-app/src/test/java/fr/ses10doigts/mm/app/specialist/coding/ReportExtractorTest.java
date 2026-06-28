package fr.ses10doigts.mm.app.specialist.coding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires rapides de {@link ReportExtractor}.
 */
class ReportExtractorTest {

    private final ReportExtractor extractor = new ReportExtractor(new ObjectMapper());

    @Test
    @DisplayName("Extrait un rapport valide present dans la sortie brute")
    void extract_withEmbeddedReport_returnsParsedReport() {
        String rawOutput = """
                logs libres
                <MARCEL_REPORT>{"status":"DONE","summary":"Mission terminee","factsDiscovered":["F1"],"decisions":["D1"],"blocker":null}</MARCEL_REPORT>
                """;

        AgentReport report = extractor.extract(rawOutput, 0);

        assertThat(report.getStatus()).isEqualTo(AgentReport.Status.DONE);
        assertThat(report.getSummary()).isEqualTo("Mission terminee");
        assertThat(report.getFactsDiscovered()).containsExactly("F1");
        assertThat(report.getDecisions()).containsExactly("D1");
        assertThat(report.getBlocker()).isNull();
    }

    @Test
    @DisplayName("Retourne trouble quand le bloc MARCEL_REPORT est absent")
    void extract_withoutReport_returnsTrouble() {
        AgentReport report = extractor.extract("sortie sans bloc attendu", 0);

        assertThat(report.getStatus()).isEqualTo(AgentReport.Status.TROUBLE);
        assertThat(report.getBlocker()).contains("sortie sans bloc attendu");
    }

    @Test
    @DisplayName("Force trouble quand le processus echoue malgre un status done")
    void extract_withNonZeroExitCodeAndDoneStatus_returnsTrouble() {
        String rawOutput = """
                <MARCEL_REPORT>{"status":"DONE","summary":"Mission terminee","factsDiscovered":[],"decisions":[],"blocker":null}</MARCEL_REPORT>
                """;

        AgentReport report = extractor.extract(rawOutput, 2);

        assertThat(report.getStatus()).isEqualTo(AgentReport.Status.TROUBLE);
    }

    @Test
    @DisplayName("Extrait correctement le JSON meme si une chaine contient le tag fermant")
    void extract_withLiteralClosingTagInsideJsonString_returnsParsedReport() {
        String rawOutput = """
                Bruit libre
                <MARCEL_REPORT>{"status":"DONE","summary":"ok","factsDiscovered":["Pattern <MARCEL_REPORT>([\\\\s\\\\S]*?)</MARCEL_REPORT>"],"decisions":[],"blocker":null}</MARCEL_REPORT>
                """;

        AgentReport report = extractor.extract(rawOutput, 0);

        assertThat(report.getStatus()).isEqualTo(AgentReport.Status.DONE);
        assertThat(report.getFactsDiscovered())
                .containsExactly("Pattern <MARCEL_REPORT>([\\s\\S]*?)</MARCEL_REPORT>");
    }

    @Test
    @DisplayName("Ignore l exemple de balise dans le prompt et garde le vrai rapport final")
    void extract_withPromptExampleAndTrailingNoise_returnsFinalParsedReport() {
        String rawOutput = """
                FORMAT DE RAPPORT
                <MARCEL_REPORT>{"status":"DONE|BLOCKED|KO|TROUBLE","summary":"...","factsDiscovered":["..."],"decisions":["..."],"blocker":"..."}</MARCEL_REPORT>
                codex
                <MARCEL_REPORT>{"status":"DONE","summary":"Contexte lisible","factsDiscovered":["F1"],"decisions":["D1"],"blocker":""}</MARCEL_REPORT>
                tokens used
                123
                <MARCEL_REPORT>{"status":"DONE","summary":"tronque
                """;

        AgentReport report = extractor.extract(rawOutput, 0);

        assertThat(report.getStatus()).isEqualTo(AgentReport.Status.DONE);
        assertThat(report.getSummary()).isEqualTo("Contexte lisible");
        assertThat(report.getFactsDiscovered()).containsExactly("F1");
        assertThat(report.getDecisions()).containsExactly("D1");
    }
}
