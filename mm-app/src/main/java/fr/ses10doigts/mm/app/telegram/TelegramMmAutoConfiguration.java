package fr.ses10doigts.mm.app.telegram;

import fr.ses10doigts.mm.starter.MmCoreAutoConfiguration;
import fr.ses10doigts.telegrambots.service.sender.SimpleTelegramSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration du canal Telegram pour Marcel Maestro (étape 8).
 *
 * <p>Activé uniquement si {@code telegram.enabled=true}. Crée le bean
 * {@link TelegramHumanInteraction} qui sera découvert par le
 * {@link fr.ses10doigts.mm.starter.hitl.CompositeHumanInteraction} du starter.</p>
 *
 * <p><strong>Pourquoi {@code @AutoConfiguration} et non {@code @Configuration} ?</strong><br>
 * {@link SimpleTelegramSender} est lui-même fourni par l'auto-configuration de
 * {@code telegram-bots-mvc} ({@code TelegramAutoConfiguration}). Une {@code @Configuration}
 * ordinaire est parsée <em>avant</em> toutes les auto-configurations :
 * {@code @ConditionalOnBean(SimpleTelegramSender.class)} évaluerait donc toujours {@code false}.
 * En passant par {@code @AutoConfiguration} avec
 * {@code @AutoConfigureAfter(TelegramAutoConfiguration)} + {@code @AutoConfigureBefore(MmCoreAutoConfiguration)},
 * l'ordre garanti est : telegram-bots-mvc → TelegramMmAutoConfiguration → MmCoreAutoConfiguration.
 * Ce tri explicite est la clé : sans {@code @AutoConfigureAfter}, Spring pouvait traiter
 * {@code TelegramMmAutoConfiguration} avant que {@code SimpleTelegramSender} soit enregistré.</p>
 */
@AutoConfiguration
@AutoConfigureAfter(name = "fr.ses10doigts.telegrambots.configuration.TelegramAutoConfiguration")
@AutoConfigureBefore(MmCoreAutoConfiguration.class)
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramMmProperties.class)
@Slf4j
public class TelegramMmAutoConfiguration {

    /**
     * Adaptateur Telegram du port HumanInteraction.
     *
     * <p>Exécuté après {@code TelegramAutoConfiguration} (via {@code @AutoConfigureAfter})
     * pour garantir que {@link SimpleTelegramSender} est déjà enregistré dans le contexte
     * quand {@code @ConditionalOnBean} est évalué. Sans cet ordre explicite, la condition
     * pouvait être évaluée avant que telegram-bots-mvc ait eu le temps de créer le sender.</p>
     *
     * <p>Conditionnel sur {@link SimpleTelegramSender} : si le module
     * {@code telegram-bots-mvc} n'est pas actif, le canal est absent sans bloquer
     * le démarrage.</p>
     *
     * @param sender     sender Telegram (fourni par telegram-bots-mvc après ordering garanti)
     * @param properties propriétés {@code mm.telegram.*}
     * @return adaptateur Telegram prêt à l'emploi
     */
    @Bean
    @ConditionalOnBean(SimpleTelegramSender.class)
    public TelegramHumanInteraction telegramHumanInteraction(
            SimpleTelegramSender sender,
            TelegramMmProperties properties) {
        log.info("TelegramMmAutoConfiguration — création de TelegramHumanInteraction");
        log.debug("  sender class  : {}", sender.getClass().getName());
        log.debug("  botId         : {}", properties.getBotId());
        log.debug("  chatId        : {}", properties.getChatId());
        log.debug("  askTimeout    : {}s", properties.getAskTimeoutSeconds());

        if (properties.getChatId() == null) {
            log.warn("mm.telegram.chat-id absent — canal Telegram HITL désactivé");
            return null;
        }
        log.info("TelegramHumanInteraction câblé ✅ — botId={}, chatId={}",
                properties.getBotId(), properties.getChatId());
        return new TelegramHumanInteraction(
                sender,
                properties.getBotId(),
                properties.getChatId(),
                properties.getAskTimeoutSeconds());
    }
}
