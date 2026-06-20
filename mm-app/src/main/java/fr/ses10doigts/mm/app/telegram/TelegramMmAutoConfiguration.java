package fr.ses10doigts.mm.app.telegram;

import fr.ses10doigts.telegrambots.service.sender.SimpleTelegramSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration du canal Telegram pour Marcel Maestro (étape 8).
 *
 * <p>Activé uniquement si {@code telegram.enabled=true} ET un {@code mm.telegram.chat-id}
 * est configuré. Crée le bean {@link TelegramHumanInteraction} qui sera découvert
 * par le {@link fr.ses10doigts.mm.starter.hitl.CompositeHumanInteraction} du starter.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramMmProperties.class)
@Slf4j
public class TelegramMmAutoConfiguration {

    /**
     * Adaptateur Telegram du port HumanInteraction. Câblé uniquement si le module
     * telegram-bots-mvc est actif (SimpleTelegramSender disponible) et un chatId
     * est configuré.
     *
     * @param sender     sender Telegram explicite (par botId)
     * @param properties propriétés MM Telegram
     * @return adaptateur Telegram prêt à l'emploi
     */
    @Bean
    @ConditionalOnBean(SimpleTelegramSender.class)
    public TelegramHumanInteraction telegramHumanInteraction(
            SimpleTelegramSender sender,
            TelegramMmProperties properties) {
        if (properties.getChatId() == null) {
            log.info("Telegram activé mais mm.telegram.chat-id absent — canal Telegram désactivé");
            return null;
        }
        return new TelegramHumanInteraction(
                sender,
                properties.getBotId(),
                properties.getChatId(),
                properties.getAskTimeoutSeconds());
    }
}
