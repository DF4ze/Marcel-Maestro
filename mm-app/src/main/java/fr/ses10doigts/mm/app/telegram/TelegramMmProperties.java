package fr.ses10doigts.mm.app.telegram;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriétés de configuration spécifiques à l'intégration Telegram de Marcel Maestro.
 *
 * <p>Préfixe : {@code mm.telegram}. Le {@code chat-id} identifie le chat Telegram
 * auquel envoyer les notifications et les demandes HITL. Un seul utilisateur en V1
 * (ADR-016 : un seul bot, pas de multi-utilisateurs).</p>
 */
@ConfigurationProperties(prefix = "mm.telegram")
@Getter
@Setter
public class TelegramMmProperties {

    /** ID du chat Telegram pour les notifications et le HITL. */
    private Long chatId;

    /** ID du bot à utiliser (doit correspondre à un bot déclaré dans telegram.bots). */
    private String botId = "mm-bot";

    /** Timeout en secondes pour une demande HITL ask() (défaut : 300 = 5 min). */
    private int askTimeoutSeconds = 300;
}
