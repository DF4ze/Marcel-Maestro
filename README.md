# Marcel Maestro (MM)

Noyau agentique **product-agnostic** sous discipline de frontières stricte : le noyau
(`mm-core`) ne connaît aucun métier. Autour de lui, on branche des consommateurs.
L'agent Chef (le LLM planificateur) s'appelle **Cortex** ; l'orchestrateur (le code de
routage non-LLM) est le **Dispatcher**.

La conception détaillée vit dans [`docs/`](docs/) (roadmap V1 = source de vérité).

## Modules (Étape 1 — Fondations & frontières)

| Module | Rôle | Dépendances autorisées |
|--------|------|------------------------|
| `mm-core` | Moteur agentique pur | Spring AI core uniquement |
| `mm-spring-boot-starter` | Implémentations par défaut (autoconfiguration) | `mm-core`, Spring Boot autoconfigure |
| `mm-app` | Consommateur dev/devops | `mm-spring-boot-starter` |

## Stack

- Java **21** (LTS)
- Spring Boot **3.5.15** + Spring AI **1.1.7** (tout-GA)
- Build **Maven** (mono-repo, parent POM)

## Build & validation

```bash
# Build vert sur tous les modules
mvn verify

# Litmus de pureté du noyau : doit passer SANS aucun consommateur dans le classpath
mvn -pl mm-core test

# Aucune dépendance interdite dans le noyau (doit retourner 0 ligne)
mvn -pl mm-core dependency:tree | grep -iE "mm-app|facture|devis|stock|artisan|spring-web|spring-data"

# Smoke test de démarrage : doit logguer « Marcel Maestro — noyau chargé … » puis « Started MarcelMaestroApplication »
java -jar mm-app/target/mm-app.jar
```

Le litmus de pureté est aussi appliqué **au build** via `maven-enforcer-plugin`
(`mm-core/pom.xml`) et en **CI** via [`.gitlab-ci.yml`](.gitlab-ci.yml).

## Hors scope de l'étape 1

Toute interface métier, tout appel LLM, toute base de données. Les contrats
enfichables (« les prises ») arrivent à l'étape 2.
