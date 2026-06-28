# État d'implémentation — Marcel Maestro
**Date : 2026-06-28**  
**Statut : E1 ✅ + E2 ✅ + E3-M0 ✅ + E3-M1 ✅ + E3-M2 ✅ + E3-M3 ✅ + E3-M4 ✅ + E3-M5 ✅ + E3-M6 ✅ + Consolidation routage ✅**

---

## 1. Ce qui est construit

### E1 — Moteur agentique

Le socle agentique est en place :
- boucle `AgentLoop` en JSON structuré ;
- orchestration `Dispatcher` + `TaskQueue` ;
- HITL avec consentements persistés ;
- mémoire factuelle JPA ;
- registre d'outils et garde-fous filesystem/VPS ;
- pilotage REST + notifications Telegram.

### E2 — Multi-projets et conversations

La couche projet/conversation est livrée :
- projets persistés, archivables, avec workspaces rattachés ;
- conversations persistées par projet ;
- mémoire Spring AI isolée par `conversationId` ;
- continuité Telegram sur la conversation active ;
- génération asynchrone du titre de conversation.

### E3 — Conversation hybride complète

Le mode conversationnel est maintenant opérationnel :
- `ChatAgent` répond réellement dans `POST /messages` ;
- la conversation peut déléguer une tâche via `submit_task` ;
- le contexte projet (`PROJECT.md`, `ROADMAP.md`) est injecté dans le prompt ;
- Telegram conserve la continuité de conversation ;
- la navigation entre conversations existe en REST et Telegram ;
- le streaming SSE est disponible ;
- le lien persistant conversation ↔ tâches est stocké en base ;
- un brief de conversation est disponible en REST et Telegram.

---

## 2. État fonctionnel actuel

### Conversation REST

Endpoints principaux :
- `POST /api/projects/{projectId}/conversations`
- `GET /api/projects/{projectId}/conversations`
- `PATCH /api/projects/{projectId}/conversations/{conversationId}`
- `POST /api/projects/{projectId}/conversations/{conversationId}/archive`
- `DELETE /api/projects/{projectId}/conversations/{conversationId}`
- `POST /api/projects/{projectId}/conversations/{conversationId}/messages`
- `GET /api/projects/{projectId}/conversations/{conversationId}/tasks`
- `GET /api/projects/{projectId}/conversations/{conversationId}/brief`

Comportement :
- mode batch JSON par défaut ;
- mode SSE si `Accept: text/event-stream` ;
- persistance du message assistant final en fin de flux ;
- exposition des tâches liées à une conversation ;
- production d'un résumé ponctuel de la conversation courante.

### Telegram

Fonctions disponibles :
- sélection de projet ;
- navigation entre conversations ;
- création explicite d'une nouvelle conversation ;
- arrêt et suivi de tâche ;
- brief de la conversation active ;
- boutons inline avec icônes plus explicites.

Règle importante :
- un `/switch` vers un projet sans conversation explicite ne crée pas immédiatement de conversation ;
- la conversation n'est créée qu'au premier message libre envoyé, ce qui évite les conversations fantômes.

### Tâches liées aux conversations

Le chaînage conversation ↔ tâche est maintenant tracé en base via `conversation_task` :
- une conversation peut lister les tâches qu'elle a lancées ;
- une tâche connaît sa conversation source ;
- les notifications Telegram et la consultation REST peuvent se rattacher à ce lien.

---

## 3. Schéma de données

Migrations présentes :
- `V1` : mémoire factuelle et consentements ;
- `V2` : `project` + `project_workspace` ;
- `V3` : `conversation` + `SPRING_AI_CHAT_MEMORY` ;
- `V5` : `conversation_task` ;
- `V6` : colonnes de résultat sur `conversation_task` (`agent_id`, `category`, `result_summary`, `completed_at`) ;
- colonnes d'activité conversation ajoutées pour le tri et le suivi (`message_count`, `last_message_at`).

---

## 4. Ce qui a changé récemment

Les derniers incréments ont livré :
- SSE sur le endpoint conversationnel ;
- persistance et exposition REST des tâches liées ;
- brief conversationnel via `ConversationBriefService` ;
- commande Telegram `/brief` ;
- commandes Telegram `/conversations`, `/conv`, `/new`, `/switch` ;
- création paresseuse de conversation après switch projet ;
- boutons Telegram avec icônes de navigation.

---

## 4bis. Consolidation du routage (2026-06-28)

Suite à l'analyse de la chaîne Telegram → Cortex → Claude/Codex, le routage a été consolidé
pour supprimer son caractère aléatoire (détails : `docs/analyse-chaine-telegram-cortex-agents.md`,
ADR-034 à ADR-037) :

- **Qualificateur hybride** (`TaskQualifier`) : règles de mots-clés d'abord, repli LLM sur cas
  ambigu, défaut de sûreté. Résout catégorie → agent via `mm.agents.routing`.
- **Routage direct et observable** : `submit_task` route directement vers `claude`/`codex` ;
  la décision est journalisée (`FileJournal`, catégorie `routing_decision`) et loguée avec
  `taskId` comme id de corrélation.
- **Suppression du double dispatch** : `TaskDispatcher`, `TaskRouter` et les contrôleurs REST
  `coding-agent-tasks` / `manual/coding-agents` retirés ; un seul cerveau de routage subsiste.
- **Fermeture de boucle** : `TaskOutcomeListener` (noyau) + `ConversationTaskOutcomeListener`
  (hôte) ; `conversation_task` enrichi (agent, catégorie, résumé du résultat, `completed_at`,
  migration V6) et résultat réinjecté dans la mémoire de la conversation.

Limite assumée : l'orchestration multi-étapes par Cortex n'opère plus depuis le flux
conversationnel (Cortex reste actif sur `/api/tasks`).

## 5. Points de vigilance restants

Le socle E3 est livré, mais quelques sujets restent naturellement ouverts :
- qualité et coût du prompt de brief sur de très longues conversations ;
- stratégie future de résumé/caching pour les gros contextes projet ;
- enrichissements UX Telegram éventuels autour des listes longues ;
- supervision produit des flux SSE côté client consommateur.
