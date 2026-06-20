package fr.ses10doigts.mm.starter.hitl;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriétés de configuration de la coexistence multi-canal HITL.
 *
 * <p>Préfixe : {@code mm.hitl}. La propriété {@code primary-channel} détermine
 * le comportement du {@link CompositeHumanInteraction} pour les demandes
 * {@code ask()} :</p>
 * <ul>
 *   <li>{@code race} (défaut) — tous les canaux reçoivent la demande en parallèle,
 *       le premier à répondre gagne, les autres sont annulés.</li>
 *   <li>{@code console} — seul le canal console reçoit les demandes {@code ask()}.</li>
 *   <li>{@code telegram} — seul le canal Telegram reçoit les demandes {@code ask()}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "mm.hitl")
@Getter
@Setter
public class HitlChannelProperties {

    /** Canal primaire pour ask() : "race", "console" ou "telegram". */
    private String primaryChannel = "race";
}
