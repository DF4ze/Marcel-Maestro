# Règles de codage — Marcel Maestro (MM)

Règles **transverses et obligatoires** pour tout le code produit, à TOUTES les étapes,
en plus des contraintes propres à chaque étape (et de la discipline de pureté de `mm-core`).

## 1. Lombok partout

Utiliser **Lombok** systématiquement pour éliminer le boilerplate : `@Getter`/`@Setter`,
`@RequiredArgsConstructor`, `@Builder`, `@Value` (types immuables), `@Slf4j`, etc.

- Ajouter la dépendance `org.projectlombok:lombok` (version gérée par le BOM Spring Boot)
  en scope **provided/optional** dans chaque module qui produit du code, **y compris
  `mm-core`**. Lombok est *compile-time only* : il ne viole pas la pureté du noyau
  (ni web, ni data, ni métier) et le litmus `maven-enforcer` reste vert.
- Les `record` déjà en place ne sont **pas** à convertir de force : Lombok s'applique au
  nouveau code et aux classes (non-record). Choisir `record` ou classe Lombok selon le cas
  (record pour un DTO immuable simple, classe Lombok dès qu'il faut builder/logique).

## 2. Logging SLF4J par annotation

- Annoter les classes avec **`@Slf4j`** (Lombok).
- Déposer un **`log.info(...)` à chaque étape importante du process** : entrée/sortie d'une
  opération significative, décision de routage, transition de statut, appel d'outil,
  consentement HITL, persistance, démarrage/arrêt de boucle, etc.
- Mettre des **`log.debug(...)` sur les éléments clés à vérifier** : valeurs de variables
  déterminantes, clés de cache, contenu parsé, requêtes, compteurs de garde-fous, etc.
- **Jamais** de secret, clé d'API ou donnée sensible en clair dans les logs.
- Messages paramétrés (`log.info("... {}", valeur)`), jamais de concaténation de String.

## 3. JavaDoc sur les méthodes

- Toute méthode **publique et protégée** porte un JavaDoc décrivant son rôle, ses
  paramètres (`@param`), son retour (`@return`) et les exceptions (`@throws`) le cas échéant.
- JavaDoc également au niveau **classe** pour expliquer sa responsabilité.
- Le JavaDoc explique le *pourquoi* / le contrat, pas la paraphrase ligne à ligne du code.

## 4. Gestion de la configuration sensible

`application.yml` contient des credentials (tokens, clés API, identifiants) et ne doit
**jamais** être versionné dans Git.

- `mm-app/src/main/resources/application.yml` est dans le `.gitignore` — **ne jamais
  forcer son ajout** (`git add -f` interdit sur ce fichier).
- Le fichier versionné de référence est `application-template.yml`, dans le même
  répertoire. Il contient la structure complète mais **uniquement des placeholders**
  (`${MA_VARIABLE}`) à la place de toute valeur sensible.
- **À chaque ajout dans `application.yml`** (nouvelle clé, nouveau bloc) : répercuter
  immédiatement la modification dans `application-template.yml`. Si la valeur est
  sensible, remplacer par un placeholder `${NOM_VARIABLE}` et documenter la variable
  dans l'en-tête du template. Si la valeur est non-sensible (chemin local, flag, timeout),
  elle peut rester telle quelle dans le template.
- Un nouveau développeur clone le dépôt, copie le template et renseigne ses propres
  valeurs :
  ```bash
  cp application-template.yml application.yml
  # puis édite application.yml avec ses credentials
  ```

## 5. Timeouts — build Maven et outils longs

Le build `mvn verify` de Marcel Maestro est **très long** sur l'environnement cible (Windows,
Java 21). Un timeout insuffisant provoque des échecs silencieux difficiles à diagnostiquer.

**Règles obligatoires :**

- Tout test JUnit qui invoque un processus externe (ex. `MavenBuildTool`) doit configurer
  un timeout d'au moins **10 minutes** (600 000 ms) :
  ```java
  @Test
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void monTest() { ... }
  ```
- La propriété de timeout de `MavenBuildTool` (et de tout outil d'exécution longue) doit
  avoir une valeur par défaut **≥ 600 000 ms** dans `application.yml` / `application-template.yml`.
- Ne jamais laisser un timeout par défaut hérité (souvent 30 s à 2 min) sur une opération Maven.
- Pour la validation manuelle, compter **5 à 15 minutes** pour un `mvn verify` complet selon
  les conditions réseau (téléchargement de dépendances) et la charge machine.
- Pour les agents qui exécutent `mvn verify` en fin de milestone : prévoir explicitement
  ce délai dans les instructions ; ne pas interpréter une absence de résultat rapide comme
  un échec.
