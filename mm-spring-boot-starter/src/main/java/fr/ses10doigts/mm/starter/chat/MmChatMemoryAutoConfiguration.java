package fr.ses10doigts.mm.starter.chat;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepositoryDialect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Autoconfiguration de la mémoire chat JDBC (E2-M2).
 *
 * <p>Câble un {@link JdbcChatMemoryRepository} utilisant la même datasource SQLite
 * que {@code JpaMemoryStore} (ADR-014 : datasource unique). Le dialecte est détecté
 * automatiquement via {@link JdbcChatMemoryRepositoryDialect#from(DataSource)} qui
 * retourne un {@code SqliteChatMemoryRepositoryDialect} quand le product name est
 * "SQLite" (Spring AI 1.1.7, {@code JdbcChatMemoryRepositoryDialect#from} switch case).</p>
 *
 * <p>Le bean {@link MessageWindowChatMemory} est également déclaré ici pour éviter
 * toute dépendance à l'autoconfiguration Spring AI {@code ChatMemoryAutoConfiguration}
 * qui n'est pas garantie sur le classpath (on utilise le non-starter
 * {@code spring-ai-model-chat-memory-repository-jdbc}). La fenêtre est configurée
 * à 20 messages (comportement par défaut Spring AI).</p>
 *
 * <p>Les deux beans sont conditionnés par {@code @ConditionalOnMissingBean} :
 * l'hôte peut toujours surcharger avec sa propre implémentation.</p>
 */
@AutoConfiguration
@ConditionalOnClass(JdbcChatMemoryRepository.class)
@Slf4j
public class MmChatMemoryAutoConfiguration {

    /**
     * Crée le {@link JdbcChatMemoryRepository} câblé sur la datasource SQLite.
     *
     * <p>Le dialecte est auto-détecté via {@code DatabaseMetaData.getDatabaseProductName()}.
     * Pour SQLite, cela retourne un {@code SqliteChatMemoryRepositoryDialect} qui
     * stocke les timestamps en {@code INTEGER} (epoch millis) et ordonne par timestamp.</p>
     *
     * <p>Le schéma {@code SPRING_AI_CHAT_MEMORY} est créé par Flyway V3 — le builder
     * n'appelle jamais {@code initializeSchema()} pour ne pas entrer en conflit.</p>
     *
     * @param jdbcTemplate template JDBC partagé (datasource SQLite)
     * @param dataSource   datasource SQLite pour la détection du dialecte
     * @return repository JDBC prêt
     */
    @Bean
    @ConditionalOnMissingBean(JdbcChatMemoryRepository.class)
    public JdbcChatMemoryRepository jdbcChatMemoryRepository(
            JdbcTemplate jdbcTemplate, DataSource dataSource) {
        JdbcChatMemoryRepositoryDialect dialect = JdbcChatMemoryRepositoryDialect.from(dataSource);
        log.info("MmChatMemoryAutoConfiguration — JdbcChatMemoryRepository câblé, dialecte={}",
                dialect.getClass().getSimpleName());
        return JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(dialect)
                .build();
    }

    /**
     * Crée le {@link MessageWindowChatMemory} enveloppant le repository JDBC.
     *
     * <p>La fenêtre glissante est fixée à 20 messages (comportement par défaut
     * Spring AI). L'hôte peut surcharger avec {@code @ConditionalOnMissingBean(ChatMemory.class)}
     * pour modifier la taille ou la politique de rétention.</p>
     *
     * @param repository le repository JDBC déclaré ci-dessus (ou fourni par l'hôte)
     * @return mémoire chat prête à injecter dans {@code ConversationService}
     */
    @Bean
    @ConditionalOnMissingBean(ChatMemory.class)
    public MessageWindowChatMemory messageWindowChatMemory(
            JdbcChatMemoryRepository repository) {
        log.info("MmChatMemoryAutoConfiguration — MessageWindowChatMemory câblé (fenêtre 20 messages)");
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }
}
