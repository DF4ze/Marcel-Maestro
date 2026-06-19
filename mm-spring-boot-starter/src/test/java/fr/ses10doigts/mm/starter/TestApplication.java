package fr.ses10doigts.mm.starter;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application Spring Boot minimale pour les tests d'intégration du starter.
 *
 * <p>Fournit le point d'entrée {@code @SpringBootApplication} nécessaire aux
 * tests {@code @SpringBootTest}. L'autoconfiguration ({@link MmCoreAutoConfiguration})
 * est découverte automatiquement.</p>
 */
@SpringBootApplication
class TestApplication {
}
