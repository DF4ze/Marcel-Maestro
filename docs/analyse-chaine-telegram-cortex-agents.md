# Analyse de consolidation — chaîne Telegram → Cortex → Claude/Codex
**Date : 2026-06-28 · Portée : analyse uniquement (aucune modification de code)**

Objectif : comprendre pourquoi le déclenchement de tâches paraît aléatoire et pourquoi « ça
ne passe pas par Claude ou Codex », à partir de l'état réel du code et de `docs/`.

---

## 1. Ce que fait réellement le code aujourd'hui

### 1.1 Le chemin nominal (message libre Telegram)

```
Telegram @Chat (TelegramMmController.chat)
  → ConversationService.chat()
        bind AgentContext (ThreadLocal)         ← contexte projet/conversation
  → ChatAgent.chat()  [ChatClient Spring AI]
        prompt = MarcelChatPromptComposer
        tools  = submit_task, read_project_file
  → LLM #1 décide :
        • pas d'outil           → réponse texte libre  (FIN, Claude/Codex jamais sollicités)
        • submit_task(desc)     → taskQueue.submit(TaskMessage assignee="cortex", USER_REQUEST)
                                  réponse immédiate « Tâche soumise - id … »
  ── (asynchrone) ──
  → Dispatcher.pollLoop → dispatch(assignee="cortex") → CortexFactory → AgentLoop.run()
  → LLM #2 (JSON structuré, SystemPromptComposer) décide :
        • tool_calls            → exécute LUI-MÊME (read_file/write_file/maven_build/vps…)
        • sub_tasks[assignee]   → délègue à un spécialiste
        • done/KO               → termine
  → si sub_tasks : Dispatcher route SPECIALIST_REQUEST → ClaudeAgentFactoryAdapter /
                   CodexAgentFactoryAdapter → CLI claude/codex → AgentReport
                   → SPECIALIST_REPORT renvoyé au cortex
  → notification finale via Dispatcher.notifyOutcome → Telegram
```

### 1.2 Le second chemin (REST seulement)

```
POST /api/coding-agent-tasks → CodingTaskController → TaskDispatcher (virtual threads)
  → TaskRouter.resolve(category)        ← routage DÉTERMINISTE, configurable
        mm.agents.routing : CODING→claude, ANALYSIS→claude, BUILD→codex
  → ClaudeCodeAgent / CodexAgent (CLI)  ← appel DIRECT, sans Cortex
```

Ce chemin court-circuite totalement Cortex. Le javadoc de `TaskDispatcher` et le
`test-plan-coding-agent-adapter-step-5.md` indiquent qu'il sert aux **validations
manuelles** des CLI. Mais c'est un `@Service` complet exposé en REST en production.

---

## 2. Pourquoi le déclenchement paraît aléatoire

### 2.1 Trois décisions LLM empilées, aucune déterministe
Atteindre Claude/Codex suppose 3 jugements probabilistes successifs :
1. le ChatAgent (LLM #1) décide de **déléguer** via `submit_task` ;
2. Cortex (LLM #2) décide de créer des **sub_tasks** plutôt que de faire le travail lui-même ;
3. Cortex choisit le **bon `assignee`** (`claude`/`codex`).

La variance se cumule. ADR-026 a explicitement supprimé tout classifier en amont, alors
que la conception générale (§7.3 « pré-qualificateur ») le prévoyait. C'est la cause
structurelle du caractère aléatoire.

### 2.2 Le prompt conversationnel décourage la délégation
`MarcelChatPromptComposer` dit au LLM #1 : *« N'utilise pas submit_task pour une simple
question, une analyse de code, une discussion d'architecture ou une explication. »*
→ Toute une classe de demandes « code » est répondue directement par le LLM de chat et
n'entre **jamais** dans le moteur — donc jamais dans Claude/Codex. C'est probablement le
premier facteur de « ça ne passe pas par Claude ou Codex ».

### 2.3 Cortex a les mêmes outils que les spécialistes
`ToolRegistry` agrège tous les `AgentTool` (read_file, write_file, maven_build, outils VPS,
remember_fact) et l'`AgentLoop` (Cortex) les reçoit. Le prompt de routage dit même : *« Si
tu peux conclure sans délégation, réponds directement … sans créer de sub_task. »*
→ Même quand la tâche est déléguée à Cortex, il peut faire l'écriture de fichiers / le build
lui-même et ne **jamais** instancier Claude/Codex. « Le moteur a tourné » mais pas les
agents de code.

### 2.4 Deux systèmes de routage divergents
- **Chemin réel (Telegram)** : routage par `assignee` choisi par le LLM dans les sub_tasks.
- **Chemin REST de test** : routage déterministe `TaskRouter` + `mm.agents.routing` + `TaskCategory`.

→ La configuration que l'on tunerait naturellement pour contrôler le routage
(`mm.agents.routing`, `TaskCategory`) **n'a aucun effet sur le flux conversationnel**. Deux
dispatchers (`Dispatcher` vs `TaskDispatcher`), deux routeurs, deux composeurs de prompt
(`MarcelChatPromptComposer` vs `SystemPromptComposer`). Source majeure d'incohérence.

### 2.5 `submit_task` perd l'intention
La signature est `submitTask(String description)`. Pas de catégorie, pas d'agent cible, pas
de référence projet. Cortex doit tout ré-inférer depuis du texte libre. Impossible, depuis
la conversation, de dire de façon déterministe « utilise Codex ».

### 2.6 Couplage par chaînes de caractères non validées
`Dispatcher.dispatch` fait `agentFactories.get(assignee)`. Les `assignee` valides ne sont
transmis que via le **texte** du prompt (`CodingRoutingPromptExtension`), sans enum ni
validation. Si le LLM émet `claude-code`, `dev`, `Claude`, `CODING`… aucune factory n'est
trouvée. Pour une sub_task, cela produit un rapport KO renvoyé au cortex — dégradation
quasi silencieuse qui se vit comme « il ne s'est rien passé / c'est aléatoire ».

### 2.7 Échecs classés `TROUBLE` / silencieux
`AgentLoop` capture les exceptions d'appel LLM → `text=null` → parse failure → `TROUBLE`,
puis retries renforcés jusqu'aux bornes. Un hoquet provider devient des retries silencieux
puis un KO. Côté CLI, l'absence du bloc `<MARCEL_REPORT>` ⇒ `TROUBLE`. Vu de l'extérieur :
résultats incohérents d'une fois sur l'autre.

### 2.8 Boucle de feedback disjointe
Le ChatAgent répond tout de suite (« Tâche soumise »), le résultat arrive plus tard par une
notification Telegram séparée (`notifyOutcome`). Le lien `conversation_task` existe en base
mais n'est pas réinjecté dans la conversation (point ouvert assumé en E3-M6). Renforce la
sensation d'aléatoire et de déconnexion.

### 2.9 Bug latent sur le flux SSE/stream (hors Telegram)
Dans `ConversationService.chatStream`, `agentContextHolder.bind` est fait dans `Flux.defer`
sur le thread d'abonnement, mais l'exécution des outils par `.stream()` de Spring AI peut se
faire sur un autre thread Reactor où le `ThreadLocal` n'est pas positionné → `submit_task`
lève « Aucun AgentContext lié ». Le chemin **batch** (celui de Telegram) est sûr car
synchrone, même thread. À corriger avant de pousser le SSE en usage réel.

---

## 3. Écart entre l'intention documentée et le code

| Intention (docs) | Réalité (code) |
|---|---|
| Cortex « ne produit pas de code ou d'actions techniques directement » (archi générale §2.2) | Cortex possède read/write/maven/vps et s'exécute souvent lui-même |
| Pré-qualificateur léger en amont (§7.3) | Supprimé (ADR-026) : tout repose sur le jugement LLM |
| Routage déterministe par catégorie (`TaskRouter`) | Actif seulement sur le chemin REST de test, pas en conversation |
| `TaskDispatcher` = validations manuelles (javadoc, test-plan) | Exposé en `@Service` + REST en prod, prête à confusion |

Le code n'est pas « mal câblé » techniquement — il fait ce que chaque ADR décrit. Le
problème est l'**accumulation de décisions LLM non contraintes** sur un chemin où la doc
d'origine prévoyait du déterministe.

---

## 4. Recommandations de consolidation (priorisées)

**P0 — rendre le routage observable et déterministe au bon endroit**
1. Valider l'`assignee` au niveau du `Dispatcher` contre un registre/enum ; en cas
   d'inconnu, réparer ou remonter une **vraie erreur** à l'utilisateur, pas un KO silencieux.
2. Tracer chaque message avec un id de corrélation : délégué ? assignee ? spécialiste lancé ?
   CLI résolu ? Le `FileJournal` existe déjà — l'exposer transforme « j'ai l'impression » en
   faits mesurables.

**P1 — réduire la pile de décisions LLM**
3. Rendre `submit_task` **structuré** : `category` + `suggestedAgent` (enum), pour que la
   conversation transmette une intention explicite au lieu de la faire ré-inférer par Cortex.
4. Réintroduire, au choix, un pré-qualificateur déterministe léger (idée §7.3 d'origine),
   ou des règles de délégation par type de tâche au lieu du « au jugé » du prompt.

**P1 — clarifier le rôle de Cortex vs spécialistes**
5. Décider explicitement : soit Cortex **n'a pas** les outils d'exécution (write/maven/vps)
   et est forcé de déléguer à Claude/Codex, soit on définit de façon déterministe (par type
   de tâche, pas au gré du LLM) quand il s'exécute lui-même.

**P2 — supprimer la dualité de dispatch**
6. Unifier les deux chemins, ou démettre clairement `TaskDispatcher` en outil de test
   (profil dédié, hors contexte prod) pour qu'il n'existe qu'un seul cerveau de routage.

**P2 — fermer la boucle**
7. Réinjecter le résultat de tâche dans la conversation source (point ouvert E3-M6).
8. Corriger la propagation du `AgentContext` sur le chemin streaming (Reactor context ou
   capture/restore explicite) avant d'ouvrir le SSE.

---

## 4bis. Implémentation livrée (2026-06-28)

Les recommandations ci-dessus ont été implémentées :

- **Qualificateur hybride** (`TaskQualifier`) : règles de mots-clés normalisés d'abord,
  repli petit LLM sur cas ambigu, défaut de sûreté sinon. Résout catégorie → agent via
  `mm.agents.routing`. Chaque décision porte sa `source` (RULES / LLM / FALLBACK_DEFAULT).
- **Routage observable** : `submit_task` qualifie puis route directement vers `claude`/`codex`
  (plus de pari sur Cortex). La décision est journalisée (`FileJournal`, catégorie
  `routing_decision`, agent `chat-router`) et loguée avec un id de corrélation = `taskId`.
- **Suppression du double dispatch** : `TaskDispatcher`, `TaskRouter`, `CodingTaskController`,
  `ManualCodingAgentController` et leurs tests supprimés. Un seul cerveau de routage subsiste.
- **Fermeture de boucle** : interface noyau `TaskOutcomeListener` invoquée par le `Dispatcher`
  en fin de tâche utilisateur ; `ConversationTaskOutcomeListener` met à jour `conversation_task`
  (statut final, résumé, agent, catégorie, `completed_at` — migration V6) et réinjecte un
  résumé du résultat dans la mémoire de la conversation source.

Reste hors périmètre de ce lot : propagation `AgentContext` sur le chemin SSE (§2.9).

> Note : non compilé/validé ici (Java 21 + Maven absents de l'environnement d'analyse).
> Lancer `mvn verify` côté poste de dev pour valider.

## 5. Synthèse en une phrase
Claude/Codex sont **correctement câblés** mais placés derrière **trois décisions LLM non
déterministes** (déléguer ? créer une sub_task ? quel assignee ?), un **prompt qui décourage
la délégation**, un **Cortex qui peut tout faire lui-même**, et un **routage déterministe
branché ailleurs que sur le flux réel** — d'où le ressenti d'aléatoire. La consolidation
passe d'abord par l'observabilité du routage et la réduction/contrainte de ces points de
décision, avant tout ajout de fonctionnalité.
