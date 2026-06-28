package fr.ses10doigts.mm.app.specialist.coding;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Contexte Marcel injecté dans chaque mission envoyée à un agent externe.
 */
@Builder
@Getter
public class MarcelContext {

    private final String projectMd;
    private final String roadmapResultMd;
    private final List<String> c3Facts;
    private final String workingDirectory;
}
