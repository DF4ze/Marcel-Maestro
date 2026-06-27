package fr.ses10doigts.mm.app.config;

import fr.ses10doigts.mm.app.support.ScriptedChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Configuration de test fournissant un {@link ChatClient} local piloté par script.
 */
@TestConfiguration
public class ScriptedChatClientTestConfiguration {

    /**
     * Modèle de chat scripté pour piloter les réponses sans appel réseau.
     *
     * @return modèle scripté réutilisable par les tests
     */
    @Bean
    @Primary
    public ScriptedChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }

    /**
     * ChatClient réel construit au-dessus du modèle scripté.
     *
     * @param scriptedChatModel modèle scripté injecté
     * @return ChatClient de test sans dépendance externe
     */
    @Bean
    @Primary
    public ChatClient chatClient(ScriptedChatModel scriptedChatModel) {
        return ChatClient.builder(scriptedChatModel).build();
    }
}
