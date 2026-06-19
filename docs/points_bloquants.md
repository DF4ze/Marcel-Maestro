# Registre des points bloquants et zones d'ombre
**Statut : Document de travail — Partie la plus importante**
**Légende** : 🔴 Bloquant (décision requise avant implémentation) | 🟡 Zone d'ombre (à clarifier avant le jalon concerné)

---

## PB-01 — SaaS vs installation locale
**Criticité** : 🔴 Bloquant — doit être tranché avant M0

**Description** : Le document Python parle d"agents IA locaux" comme philosophie. La cible réelle (artisans TPE non-techniques) est structurellement incompatible avec une installation locale non assistée.

**Pourquoi c'est bloquant** : Ce choix conditionne en cascade :
- Le schéma de données (multi-tenant dès le départ vs absent)
- L'authentification et la gestion des sessions
- Le modèle de déploiement (VPS/cloud partagé vs machine artisan)
- Le coût d'acquisition et de support
- Le traitement des données personnelles (RGPD : factures, contacts clients)
- La viabilité du LLM local (voir PB-02)

**Option A — SaaS hébergé** :
- Tu héberges sur un VPS/cloud
- Multi-tenant actif dès le départ — `tenant` key est opérationnelle, pas juste déclarée
- L'artisan accède via Telegram ou web, sans rien installer
- Modèle économique : abonnement mensuel
- Impact architecture : Row-Level Security PostgreSQL, Spring Security + JWT, isolation des données entre artisans
- Impact RGPD : les données artisans (factures, contacts clients) transitent et séjournent chez toi → obligations DPO, contrats de sous-traitance, registre des traitements

**Option B — Installation locale chez l'artisan** :
- L'artisan installe sur son PC / NAS (ou tu livres un appareil pré-configuré)
- Pas de multi-tenant (une instance = un artisan)
- Problème matériel : LLM local sérieux requiert GPU (voir PB-02)
- Problème support : mises à jour, debug à distance, artisan non-technique. Pour un plombier, "relancer le serveur Ollama" est une opération impossible sans assistance.

**Option C — Hybride : app locale, LLM cloud** :
- Installation locale pour les données, LLM cloud (API)
- Résout le problème matériel
- Résout partiellement le RGPD (les données restent locales, seuls les prompts partent en cloud)
- Complexifie le déploiement (l'artisan doit gérer une app locale + une clé API)

**Ce qu'il faut décider** : Choisir l'une des trois options. L'architecture du walking skeleton diverge dès M1 selon ce choix (multi-tenant activé ou non, Spring Security ou non, Telegram seul ou UI web obligatoire pour l'onboarding).

**Recommandation** : Option A (SaaS) est la seule viable à court terme pour une cible TPE non-technique. Option C peut être une évolution vers la souveraineté des données quand le produit est mature.

---

## PB-02 — LLM local irréaliste pour la cible artisan TPE
**Criticité** : 🔴 Bloquant — conditionne le choix technologique LLM et le business model

**Description** : Les modèles cités dans le document Python (Llama 3.1 70B pour le Cortex) requièrent ~40GB de VRAM. Un artisan plombier ou électricien n'a pas ce matériel, et n'a pas les compétences pour gérer Ollama.

**Options réalistes** :

| Option | Modèle Cortex | Hardware requis | Latence/req | Coût opérationnel | Confidentialité données |
|--------|------------|-----------------|-------------|-------------------|------------------------|
| A — Cloud pur | Claude 3.5 Haiku / GPT-4o mini | Aucun | 2-5s | ~0,01€/req | Données chez Anthropic/OpenAI |
| B — Local CPU quantisé | Llama 3.2 3B ou 7B quantisé | 8-16GB RAM, CPU moderne | 15-60s | Nul | Total |
| C — Hybride | Cloud pour Cortex, local 7B pour batch | 8-16GB RAM + CPU | Cortex: 2-5s, Batch: lent | Cloud uniquement sur le Cortex | Données sensibles restent locales pour le batch |
| D — Ollama hébergé par toi | N'importe quel open source | VPS avec GPU | 2-10s | Coût GPU (fixe) | Données chez toi (RGPD à gérer) |

**Avantage Spring AI** : Le provider LLM est interchangeable via configuration uniquement (`spring.ai.openai.api-key` vs `spring.ai.ollama.base-url`). Le code du moteur ne change pas.

**Recommandation** : Démarrer avec Option A (cloud pur, ex: OpenAI API ou Anthropic API) pour le MVP. Garder Ollama comme option documentée pour les utilisateurs avancés ou les tests locaux. Ne pas optimiser pour le local avant que la valeur du produit soit validée.

**Ce qu'il faut décider** :
1. Quel provider LLM pour le MVP ? (Impacte le `application.properties` de base)
2. Latence acceptable pour l'artisan ? (< 10s ? < 30s ? Devis en quelques secondes vs en 2 minutes ?)
3. Test empirique obligatoire en M1 : même prompt avec Llama 7B local vs GPT-4o mini → comparer fiabilité JSON output + latence

---

## PB-03 — Facturation électronique 2027 ≠ un AgentTool
**Criticité** : 🔴 Bloquant pour le produit facturation — impose des contraintes d'architecture dès maintenant même si le développement est reporté

**Description** : La réforme française de facturation électronique impose :
- **Format Factur-X** (norme EN16931 + extension française) : fichier PDF/A-3 avec XML structuré embarqué
- **Transmission** via Portail Public de Facturation (PPF, DGFiP) OU via une Plateforme de Dématérialisation Partenaire (PDP) immatriculée
- **Piste d'audit fiable (PAF)** : traçabilité complète et immuable de création à archivage, opposable en contrôle fiscal
- **Conservation** : 10 ans minimum (Code de commerce L.123-22)

**Pourquoi le modèle agentique actuel est inadapté pour la facturation légale** :

**Problème 1 — Hallucination à valeur légale** : Si le LLM compose librement le contenu d'une facture (montant, TVA, mention légale), et qu'il se trompe, la facture est nulle voire engage ta responsabilité. La génération Factur-X doit être déterministe, pas stochastique.

**Problème 2 — Immuabilité** : Une facture émise ne peut pas être modifiée par le système agentique (mémoire C3/C4 potentiellement mis à jour, journaux archivés). Elle doit aller dans un stockage immuable séparé, hors du moteur.

**Problème 3 — Intégration PDP/PPF** : Ce n'est pas un `AgentTool`. C'est un sous-système avec authentification propre, SLA de disponibilité, retry avec backoff, accusé de réception légal. Un tool call sans fiabilité (BlockingQueue in-memory, pas de retry garanti) ne suffit pas.

**Frontière recommandée** :

Ce que le moteur agentique **peut** faire (sans risque légal) :
- Collecter les informations de facturation (client, prestations, montants, taux TVA)
- Afficher un brouillon pour validation HITL (l'artisan valide avant toute émission)
- Déclencher, avec confirmation explicite HITL, un appel vers le service de génération Factur-X

Ce que le moteur agentique **ne doit pas** faire seul :
- Générer le fichier Factur-X final (déléguer à une librairie certifiée : `mustang`, `factur-x-java`)
- Soumettre à une PDP sans couche de fiabilité (idempotence, retry avec backoff, accusé de réception)
- Archiver légalement (le stockage immuable certifié est hors du scope du moteur)

**Ce qu'il faut décider** :
1. Le produit facturation sera-t-il une implémentation maison de Factur-X ou une intégration avec un acteur PDP existant (Pennylane, Sellsy, Chorus Pro pour les marchés publics) ?
2. Qui est responsable légalement de la génération Factur-X ? Un microservice dédié à responsabilité légale isolée du moteur agentique ?
3. Ce service Factur-X est-il un `AgentTool` qui s'intègre dans `mm-app` ou un système externe appelé par API avec des garanties supplémentaires ?

**Attention** : cette décision doit être prise avant de commencer à concevoir le produit facturation, même si l'implémentation est reportée. Elle peut impacter le contrat `AgentTool` si des garanties transactionnelles doivent être ajoutées.

---

## PB-04 — Frontière exacte mm-core / starter / hôte
**Criticité** : 🟡 Zone d'ombre — à clarifier avant M1

**Description** : Les frontières sont définies en théorie mais ambiguës sur des cas concrets.

**Q1 — Qui possède le system prompt du Cortex ?**
Le format JSON de sortie (champ `status`, etc.) est une contrainte du moteur → défini dans `mm-core`.
Le contexte métier (termes artisan, domaine facturation) est une contrainte de l'hôte → injectable via `project.md` ou une configuration hôte.
**Décision à prendre** : Le system prompt de base (format JSON + instructions de boucle) est dans `mm-core`. L'hôte peut l'étendre (pas le remplacer) via un bean `SystemPromptExtension`.

**Q2 — Le journal C2 (JSONL) est-il dans mm-core ou starter ?**
Le format de journal est un détail d'implémentation → `starter`.
L'interface `Journal.append(JournalEntry)` → `mm-core` (port).
**Décision suggérée** : Port `Journal` dans `mm-core`, implémentation `FileJournal` dans `starter`.

**Q3 — Qui enregistre les agents spécialistes ?**
`mm-core` connaît le concept de `SpecialistAgent` mais pas le domaine "java-spring" ou "facturation".
**Interface suggérée** :
```java
public record SpecialistRegistration(
    String id,
    String systemPrompt,
    List<AgentTool> tools
) {}
```
L'hôte déclare ses spécialistes via des beans `SpecialistRegistration`. Le starter les découvre et les enregistre auprès du Dispatcher.

**Q4 — TelegramHumanInteraction va dans starter ou dans app ?**
Starter = réutilisable par tout consommateur qui veut Telegram.
App = spécifique au premier déploiement.
**Recommandation** : Dans `mm-app` pour le MVP. Déplacer dans `starter` si un second produit utilise Telegram.

---

## PB-05 — Contrat AgentTool confronté aux cas réels
**Criticité** : 🟡 Zone d'ombre — à valider lors du Jalon M6

**Description** : `AgentTool` est défini abstraitement. Les deux premiers cas réels (devis, éventuellement stock) peuvent révéler des abstractions inadaptées.

**Q1 — Comment le LLM passe-t-il les paramètres structurés ?**
Le Cortex doit fournir des données à `QuoteTool` (client, lignes de prestation, taux horaire). La signature `execute(Map<String, Object> params)` fonctionne si le `inputSchema()` est correctement déclaré et si le modèle LLM respecte le schema.

Test empirique obligatoire avant M6 : un LLM (le même que le Cortex) produit-il correctement un `tool_call` avec 5 champs dont des types imbriqués (liste de lignes de devis) ? La fiabilité varie fortement selon le modèle.

**Q2 — Un outil long-running bloque-t-il la boucle ?**
`execute()` est synchrone. Un outil qui prend 30 secondes (appel PDP, génération PDF complexe) bloque le thread de l'agent.
Options : timeout par outil (`maxExecutionTimeMs` dans `AgentTool`) + statut `TROUBLE` si timeout atteint, ou `execute()` retourne un `CompletableFuture<ToolResult>` (complique l'interface).
**Recommandation** : Synchrone pour le MVP avec timeout configurable. Ne pas ajouter `CompletableFuture` tant qu'aucun outil réel ne dépasse 10 secondes.

**Q3 — Un outil peut-il créer d'autres tâches ?**
Non — violerait SRP. `QuoteTool` génère le PDF. L'envoi par email est un outil séparé. Le Cortex ordonne la séquence.

**Q4 — Comment le moteur présente-t-il les outils disponibles au LLM ?**
Spring AI `ChatClient.tools(...)` prend des `FunctionCallback`. La liste d'outils disponibles dépend du contexte (les outils du Cortex ≠ les outils d'un spécialiste).
**Décision à prendre** : Le Dispatcher injecte la liste d'outils correcte à l'instanciation de chaque agent. `mm-core` ne connaît pas les outils — il les reçoit à l'instanciation.

---

## PB-06 — Multi-tenant : propagation du tenant et isolation
**Criticité** : 🟡 Zone d'ombre — à clarifier avant le premier déploiement multi-artisan

**Description** : Le champ `tenant` est déclaré dès J1, mais son usage opérationnel est non défini.

**Q1 — Comment le tenant est-il injecté dans les requêtes entrantes ?**
- Si SaaS : via JWT → `AgentContext` construit à partir du token par Spring Security
- Si local : `tenant = "default"` en configuration
- Recommandé : `TenantContextHolder` (ThreadLocal) alimenté par Spring Security, transparent pour `mm-core`

**Q2 — Isolation DB : Row-Level Security ou schémas séparés ?**
- Row-Level Security (RLS) PostgreSQL : une seule base, filtre automatique par `tenant_id` → performant, maintenance simple
- Schémas séparés : plus isolé, coût de migration élevé, requiert un routing de connexion par tenant
- **Recommandé** : RLS pour le premier déploiement multi-tenant, avec une colonne `tenant_id` sur toutes les tables et une policy PostgreSQL activée par défaut.

**Q3 — Comment le Dispatcher priorise-t-il plusieurs artisans simultanés ?**
- FIFO globale : premier arrivé, premier servi. Simple, équitable si les requêtes sont courtes.
- Queue par tenant avec round-robin : évite qu'un artisan monopolise le pool avec des tâches longues.
- **Recommandé** : FIFO globale pour le MVP avec un pool borné. Priorisation par tenant si besoin SLA différencié.

---

## PB-07 — Gestion d'erreur, reprise sur interruption, idempotence
**Criticité** : 🟡 Zone d'ombre — à clarifier avant M4 (Dispatcher + STOP)

**Description** : Sans LangGraph, la reprise sur erreur et l'idempotence doivent être implémentées manuellement.

**Cas 1 — STOP en milieu d'opération**
Le flag `AtomicBoolean stopped` doit être vérifié uniquement entre deux opérations atomiques (avant un appel LLM, après la réception d'un résultat tool). Jamais au milieu d'une écriture fichier, jamais en milieu de transaction JPA. Si ce n'est pas respecté : fichiers corrompus, transactions à moitié écrites.

**Cas 2 — Idempotence des tool calls**
Si `QuoteTool` plante après avoir créé le PDF mais avant d'avoir mis à jour `roadmap_result.md`, relancer la tâche crée un doublon.
**Solution recommandée** : Chaque tool call reçoit un `idempotencyKey` composé de `taskId + toolName + iterationIndex`. Le tool vérifie si ce résultat existe déjà (via MemoryStore ou un fichier marqueur) avant d'exécuter.
**Décision à prendre** : Le moteur fournit-il l'`idempotencyKey` nativement dans `AgentContext`, ou l'implémentation de chaque tool en est-elle responsable ? (Recommandé : natif dans le moteur, transparent pour l'hôte.)

**Cas 3 — Perte de tâches si crash process**
`BlockingQueue` in-memory → toutes les tâches `PENDING` sont perdues. Pour le MVP avec un artisan : acceptable. Pour des actions légales (facturation) : inacceptable. Ce cas d'usage force une migration vers une file durable (voir ADR-015).

**Cas 4 — Boucle infinie**
Même `status: RUNNING` sur N itérations consécutives = le LLM tourne en rond. La machine à états doit détecter ce pattern et passer à `KO` avec un message explicite. La borne `maxIterations` est nécessaire mais insuffisante : un LLM peut alterner entre `RUNNING` et `TROUBLE` indéfiniment.
**Recommandé** : Compteur par status (`troubleCount`, `runningCount`) en plus du compteur global.

---

## PB-08 — Modèles LLM : taille, fiabilité, latence
**Criticité** : 🟡 Zone d'ombre liée à PB-02

**Description** : Les choix de modèle du document Python doivent être validés expérimentalement, pas théoriquement.

**Le problème du structured output avec les petits modèles** : Les modèles < 13B suivent moins bien les instructions de format JSON strict. Sur un test naïf avec Llama 3.2 3B, on peut obtenir :
- `{"status": "Done"}` au lieu de `{"status": "done"}` → l'enum ne matche pas
- Du texte avant le JSON → parsing échoue
- Des champs manquants → NPE si non géré

**La machine à états DOIT gérer ces cas dégradés** (voir PB-09).

**Tests empiriques à faire obligatoirement en M1** :
- Même prompt de structured output avec 3 modèles différents (ex: 3B, 7B, GPT-4o mini)
- Mesurer : taux de JSON valide / taux de bon enum / taux de bonne inférence de tool call
- Mesurer la latence sur le hardware cible (pas sur un MacBook Pro M3)

**Ce qu'il faut décider** : Quel modèle minimum garantit un taux de JSON valide > 95% pour le system prompt du Cortex ? En dessous de ce seuil, la machine à états passe son temps à gérer des erreurs de format plutôt que de la logique métier.

---

## PB-09 — Robustesse du parsing JSON du structured output
**Criticité** : 🟡 Zone d'ombre — à résoudre en M1

**Description** : La machine à états repose sur le parsing déterministe du JSON produit par le LLM. En pratique, ce parsing échoue régulièrement.

**Modes de défaillance courants** :
- JSON avec trailing comma ou guillemets non échappés
- Texte avant ou après le JSON (`"Here is my response: {...}"`)
- Champ `status` absent ou avec une valeur inattendue (`"Status: Done"`)
- JSON tronqué (token limit atteinte)
- Response vide (timeout réseau, modèle surchargé)

**Stratégie de robustesse recommandée** :
1. Utiliser le JSON mode de Spring AI si disponible pour le provider (force le modèle à produire du JSON)
2. Fallback : regex `\{.*?\}` (mode DOTALL) pour extraire le premier bloc JSON du texte
3. Si parsing échoue → `status: TROUBLE`, `reason: "JSON parse error"`, retry avec prompt renforcé
4. Si 3 retries échouent → `status: KO`
5. Sur token limit : détecter via la `finishReason` de Spring AI (`STOP` vs `LENGTH`) et passer en `TROUBLE`

**Ce qu'il ne faut pas faire** : interpréter le texte libre du LLM avec du NLP pour deviner l'intent. Si le JSON est invalide, c'est une erreur → traitement d'erreur, pas d'interprétation créative.

---

## PB-10 — Sécurité et isolation des outils
**Criticité** : 🟡 Zone d'ombre — à adresser avant tout déploiement avec données réelles

**Description** : Les outils (`AgentTool`) exécutent des actions concrètes (écriture fichier, appel API externe, génération de documents). Le moteur n'a aucun sandbox.

**Risques concrets** :

**Filesystem** : Un outil peut lire ou écrire n'importe où si le path n'est pas contrôlé. Un LLM mal prompté ou adversariel pourrait construire un path de traversal (`../../etc/passwd`). Les tools doivent valider que les paths restent dans un répertoire workspace configurable.

**Appels API externes** : Un tool qui appelle une API externe (PDP, email) peut être invoqué en boucle par un LLM halluciné et générer des coûts ou des envois non désirés. Le HITL sur `RiskLevel.HIGH` est la première ligne de défense, mais pas suffisant seul. Recommandé : rate limiting par tool + par tenant.

**Données de facturation** : Les factures contiennent des données à valeur légale. Elles ne doivent pas transiter dans les logs JSONL (C2) en clair. Les tool results contenant des données sensibles doivent être masqués ou référencés par ID dans les journaux.

**Sandboxing de l'exécution** : Pas de sandbox JVM natif. Si un outil exécute du code dynamique (ex: script Groovy, requête SQL construite par le LLM), le risque est réel. **Décision recommandée** : les outils n'exécutent jamais de code dynamique construit par le LLM. Les paramètres sont des données, pas des instructions.

---

## PB-11 — Spring AI : maturité et gaps fonctionnels
**Criticité** : 🟡 Zone d'ombre — à vérifier en M0/M1

**Description** : Spring AI est en évolution rapide. Certaines fonctionnalités peuvent ne pas être stables ou disponibles.

**Points à vérifier impérativement avant de s'y engager** :

1. **VectorStore pgvector** : migrations de schema gérées ? Comportement en cas de downtime PostgreSQL ?
2. **Ollama + function calling** : Spring AI supporte le function calling avec Ollama ? Avec quels modèles ? (Llama 3.1 supporte function calling, Llama 3.2 aussi, mais c'est model-dependent)
3. **JSON mode / structured output** : disponible pour Ollama ? Pour OpenAI ? Compatibilité entre providers ?
4. **ChatMemory Spring AI** : peut-elle gérer la mémoire de conversation (C1) à notre place, ou doit-on gérer manuellement les `Message` passés au `ChatClient` ?

**Test à faire en M0** : créer un test d'intégration Spring AI + provider LLM choisi, avec function calling et structured output JSON. Si ce test échoue de façon fiable, l'ensemble de la machine à états est remis en question et une alternative (appel API HTTP direct sans Spring AI) doit être envisagée.
