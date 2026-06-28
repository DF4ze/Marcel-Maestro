# Prompt agent — E3-M5 : Gestionnaire de conversations

## Contexte du projet

Tu travailles sur **Marcel Maestro**, un moteur agentique Java 21 / Spring Boot 3 organisé en
3 modules Maven :

- `mm-core` — noyau pur Java (zéro dépendance Spring/JPA — règle absolue, enforcer Maven actif)
- `mm-spring-boot-starter` — entités JPA, repositories, autoconfiguration
- `mm-app` — application Spring Boot, contrôleurs REST, services applicatifs, Telegram

DB : SQLite + Flyway. ORM : Spring Data JPA. Bot : TelegramBots (Pengrad).
Spring AI est utilisé pour la conversation (`ChatClient`, `JdbcChatMemory`, `MessageChatMemoryAdvisor`).

---

## Ce qui existe déjà — lis ces fichiers en priorité

```
mm-spring-boot-starter/src/main/java/fr/ses10doigts/mm/starter/conversation/
  ConversationEntity.java          ← status OPEN|ARCHIVED déjà présent, colonne en DB (V3)
  ConversationRepository.java      ← findAllByProjectId, countByProjectIdAndStatus
  ConversationStatus.java          ← enum OPEN | ARCHIVED

mm-spring-boot-starter/src/main/resources/db/migration/
  V3__conversations_and_chat_memory.sql   ← schéma conversation + SPRING_AI_CHAT_MEMORY

mm-app/src/main/java/fr/ses10doigts/mm/app/
  conversation/ConversationService.java   ← startConversation, listByProject, getConversation,
                                            chat, delete (avec chatMemory.clear)
  rest/ConversationController.java        ← POST, GET list, GET /{id}, GET /{id}/messages,
                                            POST /{id}/messages
  rest/dto/ConversationResponse.java      ← record : id, projectId, title, startedAt, status
  telegram/TelegramSessionService.java    ← activeConversationIds déjà géré, switchSuggestions
                                            (modèle à réutiliser pour les conversations)
  telegram/TelegramMmController.java      ← /projects, /switch, /reset déjà là
```

**Schéma SPRING_AI_CHAT_MEMORY (SQLite) :**
```sql
SPRING_AI_CHAT_MEMORY (conversation_id TEXT, content TEXT, type TEXT, timestamp INTEGER)
-- timestamp = epoch millis
-- index sur (conversation_id, timestamp)
```

**Point clé** : la colonne `status TEXT NOT NULL DEFAULT 'OPEN'` est déjà dans la table `conversation`
depuis V3. **Il n'y a aucune migration Flyway à créer pour ce milestone.**

---

## Objectif du milestone E3-M5

Permettre à l'utilisateur de **naviguer entre plusieurs conversations** du projet en cours,
comme il peut déjà naviguer entre projets avec `/switch`.

Actuellement :
- Telegram maintient une unique conversation active par `chatId`, mais l'utilisateur ne peut pas
  en choisir une autre sans en créer une nouvelle.
- L'API REST liste les conversations mais sans `messageCount` ni `lastMessageAt`.
- Il n'existe pas de commande `/conversations` ni `/conv <n>` dans Telegram.

Ce qu'on ajoute dans ce milestone :
- REST : enrichissement de la liste, renommage manuel, archivage
- Telegram : `/conversations`, `/conv <n>`, `/new`

---

## 1. REST — `ConversationController` + `ConversationService`

### 1.1 Enrichissement de `GET /projects/{projectId}/conversations`

`ConversationResponse` devient un record enrichi :

```java
public record ConversationResponse(
    String id,
    String projectId,
    String title,
    String startedAt,
    String status,
    long   messageCount,   // ← nouveau
    String lastMessageAt   // ← nouveau : ISO-8601, null si aucun message
)
```

`messageCount` et `lastMessageAt` sont calculés via une **requête native** sur
`SPRING_AI_CHAT_MEMORY`, en une seule passe (pas de N+1) :

```sql
SELECT conversation_id,
       COUNT(*)  AS message_count,
       MAX(timestamp) AS last_ts
FROM SPRING_AI_CHAT_MEMORY
WHERE conversation_id IN (:ids)
GROUP BY conversation_id
```

Injecter `JdbcTemplate` ou `EntityManager` dans `ConversationService` pour exécuter cette
requête. `lastMessageAt` = `Instant.ofEpochMilli(last_ts).toString()` ou `null` si la ligne
est absente.

Le tri de la liste doit être : conversations avec message récent d'abord (`lastMessageAt DESC`),
puis par `startedAt DESC` si égalité. Filtre optionnel : `?status=OPEN|ARCHIVED|ALL` (défaut `OPEN`).
Ajouter `findAllByProjectIdAndStatus(String projectId, ConversationStatus status)` dans
`ConversationRepository`.

### 1.2 `PATCH /projects/{projectId}/conversations/{conversationId}` — renommer

Corps : `{"title": "Nouveau titre"}`

- Valider que le titre n'est pas vide.
- Mettre à jour `conversation.title` en base.
- Retourner `200 OK` avec le `ConversationResponse` mis à jour.
- 404 si conversation introuvable.

Ajouter dans `ConversationService` :
```java
@Transactional
public ConversationEntity rename(String conversationId, String newTitle) { ... }
```

### 1.3 `POST /projects/{projectId}/conversations/{conversationId}/archive` — archiver

- Passe le statut à `ARCHIVED` (ne supprime pas, ne purge pas la mémoire).
- Si la conversation archivée est la conversation active de la session Telegram courante,
  NE PAS vider la session ici (c'est le Controller/Service Telegram qui gère son état).
- Retourner `200 OK` avec le `ConversationResponse` mis à jour.
- 404 si conversation introuvable.
- 409 Conflict si déjà `ARCHIVED`.

Ajouter dans `ConversationService` :
```java
@Transactional
public ConversationEntity archive(String conversationId) { ... }
```

---

## 2. Telegram — nouvelles commandes

### 2.1 Modèle suggestions conversations dans `TelegramSessionService`

S'inspirer exactement du mécanisme `switchSuggestions` (projets) :
```java
private final Map<Long, List<String>> conversationSuggestions = new ConcurrentHashMap<>();

public void setConversationSuggestions(Long chatId, List<String> conversationIds) { ... }
public void clearConversationSuggestions(Long chatId) { ... }
public Optional<ConversationEntity> resolveConversationSuggestion(Long chatId, int index) { ... }
```

`resolveConversationSuggestion` vérifie que la conversation est toujours `OPEN` avant de retourner.

Ajouter aussi une méthode pour lister les conversations OPEN d'un projet triées par activité :
```java
public List<ConversationEntity> listOpenConversationsForProject(String projectId, int limit) { ... }
```
(requête `conversationRepository.findAllByProjectIdAndStatus(projectId, ConversationStatus.OPEN)`,
triée par `startedAt DESC` ou — si la colonne `lastMessageAt` n'est pas en entité — simplement par
ordre naturel ; l'important est d'avoir une liste stable et bornée à `limit`)

### 2.2 Commande `/conversations` dans `TelegramMmController`

Affiche les 5 dernières conversations OPEN du projet actif :

```
📋 Conversations — [NomProjet]
1. Analyse du module mm-core (hier)
2. Bug VPS 2026-06-26
3. Refacto TelegramSessionService (il y a 2j)
4. Discussion roadmap E3
5. Bootstrap 📌

Tape /conv <n> pour reprendre une conversation.
```

- Utiliser `TelegramSessionService.listOpenConversationsForProject(projectId, 5)`.
- Enregistrer la liste dans `conversationSuggestions` pour résolution via `/conv`.
- Si pas de projet actif → répondre "Aucun projet actif. Utilise /switch pour en sélectionner un."
- Si aucune conversation OPEN → répondre "Aucune conversation ouverte sur ce projet. Envoie un message pour en démarrer une."

Formatage de la date : si aujourd'hui → "aujourd'hui", si hier → "hier", sinon date courte
(exemple : "26 juin"). Utiliser `LocalDate` côté Java.

### 2.3 Commande `/conv <n>` dans `TelegramMmController`

- Parser l'argument : `/conv 2` → index 2 (1-based dans l'affichage, 0-based en interne).
- Résoudre via `TelegramSessionService.resolveConversationSuggestion(chatId, index - 1)`.
- Si trouvé : `sessionService.setActiveConversationId(chatId, conv.getId())`, répondre :
  ```
  ✅ Conversation reprise : "Analyse du module mm-core"
  ```
- Si l'index est hors limites ou la conversation introuvable/archivée → répondre avec un message
  d'erreur et inviter à relancer `/conversations`.
- Si aucune suggestion en mémoire (l'utilisateur tape `/conv 1` sans avoir fait `/conversations`
  avant) → répondre "Lance d'abord /conversations pour voir la liste."

### 2.4 Commande `/new` dans `TelegramMmController`

- Crée une nouvelle conversation via `conversationService.startConversation(projectId)`.
- Met à jour `sessionService.setActiveConversationId(chatId, newConv.getId())`.
- Vide les suggestions de conversations.
- Répond :
  ```
  🆕 Nouvelle conversation démarrée.
  Envoie ton premier message à Marcel !
  ```
- Distinct de `/reset` qui réinitialise simplement le pointeur sans créer explicitement
  une nouvelle entité (la création a lieu au premier message).

---

## 3. Règles de codage obligatoires

- **Lombok** : `@Slf4j`, `@RequiredArgsConstructor`, `@Builder`, etc. sur toutes les classes.
- **Logging** : `log.info` à chaque transition de statut, switch de conversation, commande Telegram.
  `log.debug` sur les résolutions d'index et les requêtes.
- **JavaDoc** : toute méthode publique et protégée, niveau classe aussi.
- **`mm-core` pur** : aucune dépendance Spring/JPA/infra dans `mm-core`. Toute logique métier
  liée aux entités va dans `mm-spring-boot-starter` ou `mm-app`.
- **`application-template.yml`** : si tu ajoutes une propriété dans `application.yml`, la
  répercuter immédiatement dans `application-template.yml`.

---

## 4. Tests

### 4.1 Tests JUnit

**Rapides (pas de @SpringBootTest) :**
- `ConversationServiceRenameTest` : renommer une conversation → titre mis à jour
- `ConversationServiceArchiveTest` : archiver → statut ARCHIVED, 409 si déjà archivé
- `ConversationListEnrichmentTest` : `messageCount` et `lastMessageAt` calculés correctement
  (mocker `JdbcTemplate`)

**Lents (@Tag("slow") + @SpringBootTest + SQLite) :**
- `ConversationControllerPatchTest` : `PATCH /{id}` → 200 avec titre mis à jour, 404 si absent
- `ConversationControllerArchiveTest` : `POST /{id}/archive` → 200, puis GET confirme ARCHIVED
- `ConversationListFilterTest` : `GET ?status=OPEN` vs `?status=ARCHIVED` vs `?status=ALL`
- `TelegramConversationSwitchTest` : `/conversations` → liste, `/conv 1` → switch,
  `/new` → nouvelle conv active

⚠️ **Build Maven très lent** : prévoir **10 à 15 minutes** pour `mvn verify` sur cet environnement
(Windows, Java 21). Tout test JUnit qui démarre un contexte Spring doit porter :
```java
@Tag("slow")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
```
Ne jamais laisser un timeout par défaut Spring Boot sur un test d'intégration.

### 4.2 Plan de test Postman

Créer un fichier `docs/evolution-3-conversation/postman-e3-m5.json` (collection Postman v2.1).

**Dossier 1 — Setup**
1. `POST /projects` — créer un projet de test `"Test E3-M5"`
2. `POST /projects/{id}/conversations` — créer une conversation A
3. `POST /projects/{id}/conversations` — créer une conversation B
4. `POST /projects/{id}/conversations/{idA}/messages` — body `{"content": "Premier message conv A"}`
5. `POST /projects/{id}/conversations/{idB}/messages` — body `{"content": "Premier message conv B"}`

**Dossier 2 — Liste enrichie**
6. `GET /projects/{id}/conversations` — vérifier `messageCount ≥ 1` et `lastMessageAt != null`
7. `GET /projects/{id}/conversations?status=OPEN` — seulement les OPEN
8. `GET /projects/{id}/conversations?status=ALL` — toutes

**Dossier 3 — Renommer**
9. `PATCH /projects/{id}/conversations/{idA}` — body `{"title": "Renommée via Postman"}`
   → 200, vérifier `title = "Renommée via Postman"`
10. `PATCH /projects/{id}/conversations/{idA}` — body `{"title": ""}` → 400

**Dossier 4 — Archiver**
11. `POST /projects/{id}/conversations/{idB}/archive` → 200, `status = "ARCHIVED"`
12. `POST /projects/{id}/conversations/{idB}/archive` → 409 (déjà archivé)
13. `GET /projects/{id}/conversations?status=OPEN` → conv B absente
14. `GET /projects/{id}/conversations?status=ARCHIVED` → conv B présente
15. `GET /projects/{id}/conversations?status=ALL` → conv A et B présentes

**Dossier 5 — Nettoyage** _(toujours en dernier)_
16. `DELETE /projects/{id}/conversations/{idA}` — supprimer conv A
17. `DELETE /projects/{id}/conversations/{idB}` — supprimer conv B (même si archivée)
18. `DELETE /projects/{id}` — supprimer le projet de test

### 4.3 Plan de test manuel Telegram

**Prérequis** : Marcel Maestro démarré, bot Telegram connecté, au moins un projet ACTIVE.

1. Dans Telegram, envoie `/conversations`.
   → Vérifie que la liste s'affiche avec numéros et dates.
2. Envoie un message libre : "Bonjour Marcel, test M5."
   → Vérifie une réponse.
3. Envoie `/new`.
   → Vérifie le message de confirmation "Nouvelle conversation démarrée".
4. Envoie "Deuxième conversation, test."
   → Vérifie une réponse dans la nouvelle conversation (vérifier en DB que `conversationId` est différent).
5. Envoie `/conversations`.
   → Vérifie que les 2 conversations apparaissent (dont la nouvelle en tête si triée par date).
6. Envoie `/conv 2` (ou le numéro correspondant à la première conversation).
   → Vérifie "✅ Conversation reprise : ...".
7. Envoie "Suite de la première conversation."
   → Vérifie en SQLite : `SELECT conversation_id, content FROM SPRING_AI_CHAT_MEMORY ORDER BY timestamp` —
   le message doit être dans la première conversation, pas la nouvelle.
8. Envoie `/conv 99` (index invalide).
   → Vérifie le message d'erreur.
9. Envoie `/conv 1` sans avoir fait `/conversations` avant (redémarrer Marcel pour effacer les suggestions).
   → Vérifie le message "Lance d'abord /conversations...".
10. Via Postman : archiver la conversation active.
    Envoie n'importe quel message Telegram.
    → Marcel doit pouvoir répondre (la conversation archivée est en lecture seule côté REST mais
    la session Telegram peut toujours envoyer des messages dans une conv archivée, ou créer une nouvelle
    si tu veux bloquer — à décider lors de l'implémentation et documenter le choix).

---

## 5. Points ouverts à trancher lors de l'implémentation

- **Message dans une conversation ARCHIVED via Telegram** : doit-on bloquer et auto-créer une nouvelle
  conversation, ou laisser passer ? Documenter le choix dans un commentaire du contrôleur.
- **`lastMessageAt` via requête native ou colonne entité** : la requête native sur `SPRING_AI_CHAT_MEMORY`
  est correcte mais crée un couplage au schéma interne Spring AI. Alternativement, ajouter une colonne
  `last_message_at TEXT` à `conversation` mise à jour dans `ConversationService.chat()`. Le choix doit
  être documenté dans un commentaire.
- **Tri de la liste** : si la requête native `MAX(timestamp)` n'est pas disponible pour une conversation
  sans message, `lastMessageAt = null`. Le tri doit mettre les conversations sans message en dernier.

---

## 6. Livrable attendu

À la fin du milestone :

1. Tous les tests JUnit passent (`mvn verify` — prévoir 10-15 min).
2. La collection Postman `postman-e3-m5.json` est présente dans `docs/evolution-3-conversation/`.
3. Les commandes `/conversations`, `/conv <n>`, `/new` fonctionnent dans Telegram.
4. `GET /projects/{pId}/conversations` retourne `messageCount` et `lastMessageAt`.
5. `PATCH /projects/{pId}/conversations/{id}` et `POST /{id}/archive` opérationnels.
6. Pas de régression sur les tests E3-M0 à M4.

Si une question se pose sur l'existant (signature d'une méthode, schéma réel de la DB, comportement
d'un service), lire le fichier source avant de supposer.
