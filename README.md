# Marcel Maestro (MM)

Noyau agentique **product-agnostic** sous discipline de frontières stricte : le noyau
(`mm-core`) ne connaît aucun métier. Autour de lui, on branche des consommateurs.
L'agent Chef (le LLM planificateur) s'appelle **Cortex** ; l'orchestrateur (le code de
routage non-LLM) est le **Dispatcher**.

La conception détaillée vit dans [`docs/`](docs/) (roadmap V1 = source de vérité).

## Modules

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

## Configuration & démarrage

La configuration vit dans [`mm-app/src/main/resources/application.yml`](mm-app/src/main/resources/application.yml).
Les valeurs sensibles sont des **placeholders** alimentés par variables d'environnement.

Variables à renseigner avant de lancer :

| Variable | Rôle |
|----------|------|
| `OPENROUTER_API_KEY` | Clé du provider LLM (OpenRouter, API compatible OpenAI) |
| `MM_TELEGRAM_BOT_TOKEN` | Token du bot Telegram (BotFather) |
| `MM_TELEGRAM_CHAT_ID` | Chat id Telegram destinataire des notifications / HITL |

Le provider LLM est **indispensable** : sans `ChatClient` (donc sans clé), la boucle Cortex
n'est pas câblée et l'application démarre mais ne traite aucune tâche.

```bash
export OPENROUTER_API_KEY=sk-or-...
export MM_TELEGRAM_BOT_TOKEN=123456:ABC...
export MM_TELEGRAM_CHAT_ID=123456789
java -jar mm-app/target/mm-app.jar
```

Modèle LLM par défaut : `openai/gpt-4o-mini` (modifiable via `spring.ai.openai.chat.options.model`).
Pour démarrer **sans** Telegram : `telegram.enabled=false`.
La structure exacte du token Telegram dépend du module `telegram-bots-mvc` — adapter la clé
`telegram.bots.mm-bot.token` si besoin.

## Interaction

L'entrée principale est l'**API REST de pilotage** (`mm-app`, ADR-017). La console ne sert
qu'aux demandes de consentement HITL (stdin), pas à soumettre des requêtes.

```bash
# Soumettre une demande à Cortex
curl -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"content":"Ta demande à Cortex"}'

curl http://localhost:8080/api/tasks            # tâches actives + taille de file
curl http://localhost:8080/api/tasks/{taskId}   # statut
curl -X DELETE http://localhost:8080/api/tasks/{taskId}   # STOP
```

Une fois Telegram configuré : notifications de fin de tâche / blocage, HITL interactif par
boutons inline, et commandes `/stop`, `/status`, `/brief`, `/switch`, `/conversations`, `/conv`, `/new`.

## Avancement (roadmap V1)

Les 8 étapes du V1 sont implémentées (voir [`docs/roadmap_v1.md`](docs/roadmap_v1.md), source
de vérité, et [`docs/prompts_etapes.md`](docs/prompts_etapes.md)). Différé hors V1 :
apprentissage nocturne + mémoire vectorielle C4, métier artisan, multi-tenant réel, GUI web riche.
