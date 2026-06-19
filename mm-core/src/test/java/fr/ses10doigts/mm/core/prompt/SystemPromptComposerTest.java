package fr.ses10doigts.mm.core.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Vérifie que le prompt de base impose le contrat de sortie et reste non remplaçable,
 * l'hôte ne pouvant qu'ajouter des contributions (étape 3, livrable 2 ; PB-04 Q1).
 */
class SystemPromptComposerTest {

    @Test
    void leBasePromptImposeLeContratAgentResponse() {
        String prompt = SystemPromptComposer.base().compose();

        assertTrue(prompt.contains("JSON"), prompt);
        assertTrue(prompt.contains("\"status\""), prompt);
        // Tous les statuts sérialisés doivent être documentés au modèle.
        assertTrue(prompt.contains("running"));
        assertTrue(prompt.contains("done"));
        assertTrue(prompt.contains("blocked"));
        assertTrue(prompt.contains("trouble"));
        assertTrue(prompt.contains("KO"));
    }

    @Test
    void lhoteAjouteSaContributionApresLaBase() {
        SystemPromptExtension extension = () -> "CONTEXTE MÉTIER : dev/devops perso.";
        SystemPromptComposer composer = new SystemPromptComposer(List.of(extension));

        String prompt = composer.compose();

        assertTrue(prompt.startsWith(SystemPromptComposer.BASE_PROMPT),
                "la base doit rester en tête et ne pas être remplacée");
        assertTrue(prompt.contains("CONTEXTE MÉTIER : dev/devops perso."));
    }

    @Test
    void lesContributionsNullesOuVidesSontIgnorees() {
        SystemPromptComposer composer = new SystemPromptComposer(
                List.of(() -> "   ", () -> "AJOUT UTILE"));

        String prompt = composer.compose();

        assertTrue(prompt.contains("AJOUT UTILE"));
        assertFalse(prompt.contains("   AJOUT"), "pas de fragment blanc concaténé");
    }

    @Test
    void fragmentsDeRelanceNonVides() {
        SystemPromptComposer composer = SystemPromptComposer.base();

        assertFalse(composer.continuation().isBlank());
        assertFalse(composer.reinforcedRetry().isBlank());
    }
}
