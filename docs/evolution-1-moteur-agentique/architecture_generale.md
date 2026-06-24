# Marcel Maestro — Document de Conception Générale
**Session brainstorming du 2026-06-17**
**Statut : En cours de définition**

---

## 1. Vision & Philosophie

Le projet Marcel Maestro vise à construire un consortium d'agents IA locaux, spécialisés et collaboratifs, dont l'objectif est d'automatiser et d'assister les workflows de développement (et au-delà) de façon persistante, apprenante et extensible.

La philosophie directrice : **les agents doivent apprendre de leurs erreurs, enrichir leur mémoire au fil du temps, et réduire progressivement la charge cognitive de l'utilisateur**. À terme, l'utilisateur ne devrait plus avoir à répéter du contexte, à expliquer où se trouvent les outils, ni à corriger les mêmes erreurs deux fois.

Ce système s'inspire de plusieurs mécanismes observés dans Claude Cowork (gestion de projets, mémoire, skills/plugins, HITL), tout en permettant un contrôle plus granulaire, une architecture locale, et une vraie spécialisation par domaine.

---

## 2. Architecture Multi-Agents

### 2.1 Structure générale

Le système est organisé en **consortium hiérarchique** :

- **1 agent Cortex** (nom à définir) : seul point d'entrée conversationnel principal. Il réfléchit, remet en question, approfondit, planifie et délègue. Il ne produit pas de code ou d'actions techniques directement.
- **N agents Spécialistes** : instanciés à la demande, chacun dispose d'un ensemble d'outils propres à son domaine. Exemples envisagés :
  - Agent Java/Spring
  - Agent Frontend
  - Agent CI/CD / Builder
  - Agent Crypto
  - Agent Data / DB
  - (extensible)

### 2.2 Rôle du Cortex

Le Cortex est responsable de :
- Comprendre et reformuler la demande de l'utilisateur
- Décomposer en tâches atomiques assignables
- Décider de la séquence (certaines tâches sont séquentielles, d'autres parallélisables)
- Résoudre les conflits de ressources par la planification (voir section 4)
- Recevoir les rapports de fin de tâche de chaque spécialiste
- Synthétiser et présenter le résultat à l'utilisateur

**Décision** : Le Cortex est le point d'entrée par défaut pour toute nouvelle conversation. Cependant, l'utilisateur peut adresser directement un spécialiste (via Telegram bot ou sélection dans la GUI) — notamment en cas de blocage, pour un debug ciblé ou pour driver manuellement un spécialiste.

### 2.3 Communication inter-agents

Les spécialistes peuvent **se parler directement** sans passer par le Cortex pour les échanges opérationnels (ex : l'agent Java demande un build à l'agent CI/CD, qui répond avec une erreur, l'agent Java modifie et relance). Cette boucle directe évite la latence d'un aller-retour systématique.

En revanche, **à la fin de chaque tâche**, chaque spécialiste produit un rapport qui est envoyé au Cortex ET enregistré dans son propre dossier de rapports. Le Cortex conserve ainsi le contexte global sans avoir à participer à chaque micro-échange.

**Décision retenue** : communication directe entre spécialistes pour les boucles opérationnelles, rapport systématique au Cortex en fin de tâche.

---

## 3. Human-In-The-Loop (HITL)

### 3.1 Principe

Avant toute action potentiellement impactante (écriture de fichiers, déploiement, commande système, appel API externe, etc.), le système demande validation à l'utilisateur.

### 3.2 Niveaux de consentement

Le système propose 4 niveaux de granularité lors d'une demande de validation :

| Niveau | Portée |
|--------|--------|
| **Once** | Autorisé uniquement pour cette action précise |
| **Session** | Autorisé pour toutes les actions similaires dans la conversation courante |
| **Project** | Autorisé pour ce type d'action dans ce projet |
| **Always** | Autorisé globalement, mémorisé de façon permanente |

Ce système permet une montée progressive de la confiance : l'utilisateur commence conservateur, et au fil du temps les agents gagnent en autonomie sur les actions connues et maîtrisées.

**Décision retenue** : implémentation du système à 4 niveaux dès le début. Les règles "Project" et "Always" sont stockées dans la mémoire persistante (couche 3).

---

## 4. Gestion de l'exécution

### 4.1 Mode asynchrone

Toutes les tâches des spécialistes s'exécutent en mode **asynchrone**. L'utilisateur n'attend pas le résultat — il est notifié quand la tâche est terminée (ou en erreur).

### 4.2 Lifecycle des agents

Les agents spécialistes sont **instanciés à la demande**, pas en tant que démons permanents. L'instanciation est légère (pas de LLM chargé H24 pour chaque spécialiste).

Un composant unique tourne en permanence : le **Dispatcher**. C'est un processus léger (pas un LLM) qui :
- Écoute les nouvelles demandes entrantes (depuis la GUI ou Telegram)
- Instancie les agents à la demande
- Gère la file de tâches asynchrones
- Route les notifications de fin de tâche
- Gère les commandes `STOP` de l'utilisateur

**Analogie** : le Dispatcher est un chef de gare, pas un agent intelligent. C'est le seul processus vraiment permanent.

### 4.3 Résolution des conflits de fichiers

**Problème identifié** : deux spécialistes travaillant en parallèle sur les mêmes fichiers peuvent se marcher dessus sans le savoir.

**Décision** : pas de système de file locking (trop lourd, double requête à chaque accès). Le Cortex, qui connaît le plan d'actions global, **ne parallélise jamais deux tâches qui touchent les mêmes ressources**. La prévention du conflit est architecturale, pas technique.

### 4.4 Annulation de tâche

L'utilisateur peut envoyer un ordre `STOP` à tout moment. Le Dispatcher intercepte la commande et signale l'arrêt à l'agent en cours d'exécution. L'agent doit gérer proprement l'interruption (pas de fichier à moitié écrit, rapport d'état "KO" émis).

---

## 5. Architecture Mémoire

La mémoire est identifiée comme **le composant le plus critique** du système. Elle est organisée en 6 couches avec des rôles, des portées et des technologies distincts.

### Couche 1 — Mémoire de travail (Working Memory)

- **Nature** : éphémère, contenu du contexte LLM actif
- **Portée** : tâche en cours uniquement
- **Technologie** : context window du LLM
- **Disparaît à** : fin de la session / fin de tâche

### Couche 2 — Journal quotidien (Daily Log)

- **Nature** : append-only, timestampé, par agent
- **Portée** : journée courante
- **Technologie** : fichier texte ou JSONL par agent
- **Contenu** : tout ce qui entre et sort du LLM pendant la journée (actions, résultats, erreurs, décisions)
- **Usage** : matière première du batch de nuit. N'est **jamais** lu directement par le LLM en production.
- **Archivage** : en fin de journée, le batch compresse les journaux (gz/zip) et les archive.

**Décision** : ne pas vectoriser le journal en temps réel. La vectorisation brute de tout le flux LLM génère un ratio signal/bruit catastrophique. On délègue l'extraction au batch.

### Couche 3 — Mémoire factuelle persistante

- **Nature** : faits courts, précis, requêtables
- **Portée** : globale ou par agent
- **Technologie** : base relationnelle (SQLite ou PostgreSQL)
- **Exemples de contenu** :
  - `mvn = /usr/local/bin/mvn`
  - `projet-spring-x = port 8082`
  - `serveur-prod = 192.168.1.10`
  - Règles HITL de niveau "Project" et "Always"
- **Mis à jour par** : l'agent lui-même en fin de tâche (faits immédiats), et le batch (extraction depuis le journal)
- **Accédé par** : lookup ciblé avant action (ex : "où est mvn ?")

### Couche 4 — Mémoire procédurale (Semantic Memory)

- **Nature** : patterns, procédures, solutions à des erreurs connues
- **Portée** : globale ou par domaine/agent
- **Technologie** : base vectorielle (ChromaDB, Qdrant, ou pgvector)
- **Exemples de contenu** :
  - "Pour déployer le projet X : étapes A, B, C"
  - "Quand erreur `OutOfMemoryError` sur le build, augmenter `-Xmx` dans `.mvn/jvm.config`"
  - "Pattern de test Spring Boot : utiliser `@SpringBootTest` avec port aléatoire"
- **Mis à jour par** : le batch de nuit (extraction et enrichissement depuis le journal)
- **Accédé par** : requête sémantique (similarité vectorielle) — notamment en cas d'erreur (error-triggered retrieval)

**Scoring de fraîcheur** : chaque entrée de la couche 4 dispose d'un score composite :
- **Fréquence d'utilisation** : combien de fois cette mémoire a été récupérée et s'est avérée utile
- **Date de dernière confirmation** : la mémoire a-t-elle été confirmée/réutilisée récemment
- Les entrées avec un score élevé remontent en priorité dans les résultats de retrieval. C'est une forme de répétition espacée appliquée à la mémoire d'agent.

### Couche 5 — Mémoire projet

- **Nature** : état courant d'un projet, partagée entre tous les agents travaillant dessus
- **Portée** : par projet
- **Technologie** : fichiers structurés (voir section 6 sur la gestion de projet)
- **Contenu** :
  - `project.md` : contexte, décisions architecturales, règles spécifiques au projet
  - `roadmap.md` : plan d'action (voir section 6)
  - `roadmap_result.md` : état d'avancement (voir section 6)
- **Mis à jour par** : l'agent automatiquement à chaque `status: done`, l'utilisateur manuellement pour les règles et décisions

### Couche 6 — Mémoire globale

- **Nature** : conventions et faits qui s'appliquent à tous les agents sur tous les projets
- **Portée** : système entier
- **Technologie** : fichiers + base relationnelle (couche 3 avec flag `global`)
- **Exemples** : conventions de nommage, chemins système globaux, règles d'équipe transverses

---

## 6. Gestion de Projet

### 6.1 Fichiers de projet

Chaque projet dispose de 3 fichiers clés dans son dossier :

**`project.md`** (collaboratif)
- Contexte du projet, objectifs, contraintes techniques
- Règles de codage spécifiques (coding rules)
- Décisions architecturales et leur justification
- Mis à jour par l'agent (append-only, propose des ajouts) et par l'utilisateur (peut reformuler, nettoyer)
- Tend à se stabiliser avec le temps : au démarrage les règles évoluent vite, puis de moins en moins

**`roadmap.md`** (collaboratif, ouvert)
- Élaboré lors d'une session de brainstorming initiale (comme cette session)
- Décrit les grandes étapes et phases du projet
- Intentionnellement flexible : des étapes peuvent être ajoutées, agrégées, réordonnées au fil du projet
- Modifiable par l'agent (avec validation HITL) quand de nouvelles étapes sont découvertes

**`roadmap_result.md`** (machine-only, append-style)
- **Écrit exclusivement par les agents**, jamais édité manuellement
- Source de vérité sur l'état d'avancement
- Format compact et parseable sans LLM

Statuts possibles :
```
pending    → pas encore démarré
running    → en cours
done       → terminé avec succès
blocked    → en attente d'une décision externe ou d'une dépendance
trouble    → en cours mais rencontre des difficultés, cherche une solution
KO         → échoué / abandonné
```

Exemple de contenu :
```
Étape 1 — Setup infrastructure          [done]
Étape 2 — Agent Cortex                    [running]
  Phase 2.1 — Dispatcher               [done]
  Phase 2.2 — Routing LLM              [running]
  Phase 2.3 — HITL system              [pending]
Étape 3 — Mémoire couche 3 & 4         [pending]
```

### 6.2 Injection de contexte au démarrage de conversation

À chaque ouverture d'une nouvelle conversation dans un projet, le système injecte automatiquement le contexte projet sans que l'utilisateur ait à le demander.

**Mécanisme** :
1. Le système compare le timestamp du **dernier résumé projet généré** avec le timestamp de dernière modification de `project.md` et `roadmap.md`
2. Si aucun fichier n'a changé depuis le dernier résumé → injection directe du résumé existant (coût nul)
3. Si au moins un fichier a été modifié → régénération du résumé avant injection

`roadmap_result.md` est **toujours injecté directement** (pas besoin de résumé, il est déjà compact).

**Résultat** : l'utilisateur ne dit plus jamais "va lire project.md". L'agent démarre chaque conversation avec le contexte frais.

### 6.3 Mise à jour automatique en fin de tâche

À chaque fois qu'un agent passe en `status: done` dans sa sortie structurée, le système déclenche automatiquement :
1. La mise à jour de `roadmap_result.md` (changement de statut de l'étape concernée)
2. Un append dans `project.md` si des décisions ou faits nouveaux ont émergé
3. La mise à jour du timestamp de dernière modification → déclenchera une régénération du résumé à la prochaine conversation

Ce cycle auto-entretenu garantit que le contexte projet s'enrichit progressivement sans intervention manuelle.

### 6.4 Arborescence fichiers

```
Documents/
└── marcel-maestro/
    ├── _global/                    # Mémoire globale, conventions système
    ├── agents/
    │   ├── cortex/
    │   │   └── logs/               # Journaux quotidiens
    │   ├── java-spring/
    │   │   ├── logs/
    │   │   └── rapports/           # Rapports de fin de tâche
    │   ├── cicd/
    │   │   ├── logs/
    │   │   └── rapports/
    │   └── .../
    └── projets/
        ├── projet-alpha/
        │   ├── project.md
        │   ├── roadmap.md
        │   ├── roadmap_result.md
        │   └── rapports/           # Rapports liés à ce projet
        └── projet-beta/
            └── ...
```

---

## 7. Optimisation des Requêtes LLM

### 7.1 Principe directeur

Minimiser le nombre de requêtes LLM et la taille des contextes. Déléguer aux petits modèles les tâches de classification, routing et extraction. Réserver les grands modèles au raisonnement complexe.

### 7.2 Modèle par agent

Chaque agent dispose de son propre modèle LLM, choisi en fonction de la complexité de ses tâches. Un agent de routing léger n'a pas besoin du même modèle que l'agent Cortex.

| Agent | Complexité attendue | Modèle suggéré |
|-------|--------------------|----|
| Pré-qualificateur | Très faible | Phi-3 mini, Llama 3.2 3B |
| Spécialistes simples | Moyenne | Llama 3.1 8B, Mistral 7B |
| Agent Cortex | Élevée | Llama 3.1 70B, Qwen 2.5 72B |

### 7.3 Pré-qualification par petit modèle

Avant même que le Cortex voit la demande, un **modèle léger de routing** pré-qualifie chaque message entrant :
- Classification de l'intent (type de tâche)
- Identification du ou des spécialistes concernés
- Extraction de mots-clés pour le retrieval mémoire initial
- Estimation du besoin HITL
- Détection si c'est une continuation de tâche ou une nouvelle demande

**Impact** : dans les cas simples et répétitifs, le Cortex peut être bypassé ou reçoit une demande déjà pré-digérée, réduisant la longueur de son contexte.

### 7.4 Error-triggered retrieval

Mécanisme de retrieval sémantique déclenché **uniquement sur erreur**, pas à chaque requête :

1. Un tool call échoue (LangGraph dispose de hooks natifs sur les erreurs de tools)
2. Le message d'erreur est utilisé comme requête vectorielle sur la couche 4
3. Les solutions passées similaires sont injectées dans le contexte de l'agent
4. L'agent retente avec ce contexte enrichi

**Pourquoi c'est efficace** : le signal est fort (message d'erreur précis), le retrieval est ciblé, et on n'interroge la mémoire que quand c'est nécessaire. Pas de coût lors des tâches qui se déroulent sans accroc.

### 7.5 Détection de blocage

Trois signaux déterministes, éviter toute analyse NLP du texte de sortie (fragile) :

1. **Tool error** → hook LangGraph, immédiat
2. **Timeout** → si la tâche dépasse une durée configurée par type de tâche, le Dispatcher considère qu'il y a blocage et notifie l'utilisateur
3. **Signal structuré** → le LLM output est forcé dans un format structuré incluant `status: blocked | running | done | trouble` avec un champ `reason`. Déterministe, parseable sans LLM.

---

## 8. Batch de consolidation mémoire (Cron)

### 8.1 Rôle

Le batch nocturne (ou à fréquence configurable) est responsable de la **consolidation de la mémoire procédurale** (couche 4) à partir des journaux quotidiens (couche 2).

### 8.2 Traitement

1. **Chargement des journaux** du jour par agent
2. **Chunking** par session/tâche (découpage sémantique, pas arbitraire)
3. **Extraction LLM** (peut utiliser un modèle moyen) : identifier les faits nouveaux, les procédures découvertes, les erreurs et leurs solutions
4. **Rapprochement** avec la couche 4 existante : est-ce que cette information existe déjà ? Si oui, enrichissement et mise à jour du score de fraîcheur. Si non, création d'une nouvelle entrée.
5. **Extraction couche 3** : faits courts et précis identifiés → insertion en base relationnelle
6. **Archivage** des journaux (gz) et nettoyage

### 8.3 Ce que le batch ne fait PAS

Il ne vectorise pas le flux LLM brut. Il extrait, structure, et synthétise. La différence de qualité entre "vectoriser tout" et "extraire le signal pertinent" est considérable.

---

## 9. Interfaces Utilisateur

### 9.1 Web GUI

- Sélection du projet actif
- Sélection de l'agent à adresser (Cortex par défaut)
- Vue du `roadmap_result.md` en temps réel
- Historique des conversations par projet
- Panneau de gestion des tâches async en cours (avec bouton STOP)
- Visualisation de la mémoire (lecture seule)

### 9.2 Telegram Bot

- Un bot par agent (Cortex + chaque spécialiste)
- Permet d'adresser directement un agent spécifique
- Notifications push pour les fins de tâche async, les blocages, les demandes HITL
- Commandes simples : `/stop`, `/status`, `/memory`

**Décision** : les deux interfaces coexistent. Telegram pour la mobilité et les notifications, GUI web pour le travail approfondi et la visualisation.

---

## 10. Stack Technologique Envisagée

| Composant | Technologie envisagée | Statut |
|---|---|---|
| Orchestration agents | LangGraph | Retenu |
| LLM local | Ollama | Retenu |
| Mémoire relationnelle (C3) | SQLite → PostgreSQL | SQLite pour démarrer |
| Mémoire vectorielle (C4) | ChromaDB ou Qdrant | À décider |
| Dispatcher | Python async (asyncio) | À confirmer |
| Interface web | À définir | Ouvert |
| Telegram | python-telegram-bot | Probable |
| Batch cron | Python + crontab / APScheduler | À définir |

---

## 11. Points Ouverts & Décisions Restantes

Ces points ont été identifiés mais non tranchés lors de cette session :

- **Nom de l'agent Cortex** : à définir
- **Modèle LLM par agent** : choix définitifs à faire lors du setup
- **ChromaDB vs Qdrant** : à évaluer selon les besoins de performance et d'intégration LangGraph
- **Interface web** : technologie à choisir (FastAPI + React ? Streamlit ? Autre ?)
- **Communication inter-agents** : protocole exact (appels directs dans le graph LangGraph ? Message queue ? Shared state ?)
- **Gestion des projets multi-agents simultanés** : si deux projets tournent en parallèle, comment le Dispatcher priorise-t-il ?
- **Sécurité** : les agents ont-ils des permissions différentes sur le filesystem ? Isolation ?

---

## 12. Prochaines Étapes (ébauche Roadmap)

*À formaliser dans roadmap.md une fois validé*

1. **Setup infrastructure de base** : Ollama, LangGraph, structure de dossiers
2. **Dispatcher** : processus léger, file de tâches async, commande STOP
3. **Agent Cortex** : routing, planification, délégation
4. **Système mémoire couche 3** : SQLite, faits basiques, HITL persistant
5. **Premier agent spécialiste** : Java/Spring (car cas d'usage principal)
6. **Système mémoire couche 4** : vectorielle, batch cron, error-triggered retrieval
7. **Gestion projet** : project.md, roadmap.md, roadmap_result.md, injection contexte
8. **Interfaces** : Telegram bot d'abord (plus simple), puis GUI web
9. **Optimisation** : pré-qualificateur, scoring mémoire, tuning modèles
10. **Plugins/Skills** : système d'extensibilité type Cowork

---

*Document généré lors de la session brainstorming du 2026-06-17. À enrichir au fil des décisions d'implémentation.*
