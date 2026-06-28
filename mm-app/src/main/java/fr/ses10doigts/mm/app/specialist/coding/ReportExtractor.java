package fr.ses10doigts.mm.app.specialist.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Extrait le bloc MARCEL_REPORT de la sortie brute d'un agent CLI.
 */
@Component
@RequiredArgsConstructor
public class ReportExtractor {

    private static final String REPORT_OPEN = "<MARCEL_REPORT>";
    private static final String REPORT_CLOSE = "</MARCEL_REPORT>";

    private final ObjectMapper objectMapper;

    /**
     * Extrait et normalise le rapport structuré à partir de la sortie brute.
     *
     * @param rawOutput sortie complète stdout+stderr de l'agent
     * @param exitCode code retour du processus ; -1 indique un timeout forcé
     * @return rapport structuré ou statut TROUBLE si la sortie est inexploitable
     */
    public AgentReport extract(String rawOutput, int exitCode) {
        AgentReport report = extractParsedReport(rawOutput);
        if (report == null) {
            return AgentReport.trouble(truncate(rawOutput));
        }

        if (exitCode != 0 && report.getStatus() == AgentReport.Status.DONE) {
            return AgentReport.trouble(truncate(rawOutput));
        }
        return report;
    }

    /**
     * Cherche le premier couple de balises dont le contenu parse correctement en JSON de rapport.
     *
     * <p>Le prompt envoyé à l'agent contient lui-même un exemple de bloc MARCEL_REPORT.
     * De plus, le JSON renvoyé peut contenir les chaînes littérales
     * {@code <MARCEL_REPORT>} ou {@code </MARCEL_REPORT>}. On ne peut donc pas se
     * contenter d'un simple premier/dernier index. On teste les couples possibles
     * de la fin vers le début jusqu'à obtenir un JSON valide.</p>
     *
     * @param rawOutput sortie brute complète de l'agent
     * @return rapport parsé, ou {@code null} si aucun couple exploitable n'est trouvé
     */
    private AgentReport extractParsedReport(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return null;
        }

        int searchCloseFrom = rawOutput.length();
        while (true) {
            int closeIndex = rawOutput.lastIndexOf(REPORT_CLOSE, searchCloseFrom - 1);
            if (closeIndex < 0) {
                return null;
            }

            int searchOpenFrom = closeIndex;
            while (true) {
                int openIndex = rawOutput.lastIndexOf(REPORT_OPEN, searchOpenFrom - 1);
                if (openIndex < 0) {
                    break;
                }

                int contentStart = openIndex + REPORT_OPEN.length();
                String candidate = rawOutput.substring(contentStart, closeIndex);
                AgentReport parsed = tryParse(candidate);
                if (parsed != null) {
                    return parsed;
                }
                searchOpenFrom = openIndex;
            }
            searchCloseFrom = closeIndex;
        }
    }

    private AgentReport tryParse(String reportJson) {
        try {
            RawAgentReport rawReport = objectMapper.readValue(reportJson, RawAgentReport.class);
            return AgentReport.builder()
                    .status(parseStatus(rawReport.status))
                    .summary(rawReport.summary)
                    .factsDiscovered(defaultList(rawReport.factsDiscovered))
                    .decisions(defaultList(rawReport.decisions))
                    .blocker(rawReport.blocker)
                    .build();
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    private AgentReport.Status parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status manquant");
        }
        return AgentReport.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
    }

    private List<String> defaultList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private String truncate(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "(sortie vide)";
        }
        String normalized = rawOutput.trim();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    private static class RawAgentReport {
        public String status;
        public String summary;
        public List<String> factsDiscovered;
        public List<String> decisions;
        public String blocker;
    }
}
