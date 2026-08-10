# Guidelines pour les agents — Nicovers06 Stream Studio

Ce document oriente les assistants de code (Junie, etc.) travaillant sur ce dépôt.  
**Même contenu que** `.junie/guidelines.md`.

---

## Vue d’ensemble

**Nicovers06 Stream Studio** est une application **Android uniquement** (pas de multiplateforme) qui permet de composer une scène 16:9 puis de la diffuser en RTMP/RTMPS vers Twitch, YouTube ou un serveur personnalisé.

- **Package / applicationId** : `fr.nicovers06.streamstudio`
- **Module Gradle** : `:app` (projet racine multi-module minimal)
- **Langage** : Kotlin
- **UI** : Views + View Binding (pas de Jetpack Compose dans le MVP)
- **Activité principale** : `MainActivity` (hérite de `android.app.Activity`, pas d’`AppCompatActivity`)
- **Service de diffusion** : `stream.StreamService` (`LifecycleService`, foreground service `camera|microphone|mediaProjection`)

---

## Structure du dépôt

```text
/
├── AGENTS.md                 # Ce fichier (guidelines agents)
├── .junie/guidelines.md      # Copie identique pour Junie
├── README.md                 # Doc utilisateur / produit
├── Makefile                  # install → assembleDebug
├── build.gradle.kts          # plugins AGP (apply false)
├── settings.gradle.kts       # include(":app"), repos Google/Maven/JitPack
├── gradle.properties
├── app/
│   ├── build.gradle.kts      # module Android application
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/fr/nicovers06/streamstudio/
│       │   ├── MainActivity.kt
│       │   ├── data/         # persistance scènes + médias importés
│       │   ├── model/        # scènes, calques, widgets natifs + JSON
│       │   ├── stream/       # composition GL, Canvas/média, encodage, service
│       │   └── ui/           # bounds, ratio et éditeurs de widgets natifs
│       └── res/              # layouts, drawables, values
└── src/                      # miroir / legacy éventuel — la source de vérité est app/src
```

**Source de vérité du code** : `app/src/main/kotlin/...`.  
Si un dossier `src/` existe à la racine en parallèle, ne pas le traiter comme module Gradle actif sauf indication contraire dans `settings.gradle.kts` (seul `:app` est inclus).

---

## Architecture applicative

### Couches / packages

| Package | Rôle |
|---|---|
| `fr.nicovers06.streamstudio` | `MainActivity` : UI, permissions, binding au service, sélection scènes / destinations |
| `...model` | `StreamScene`, composants, `NativeWidgetComponent`, ordre des calques, limites d’instances et sérialisation JSON |
| `...data` | `SceneRepository` et stockage interne des images / médias importés |
| `...stream` | Pipeline vidéo/audio, RTMP, MediaProjection, CameraX, ML Kit, chat, images, médias et overlays Canvas natifs |
| `...ui` | `ComponentBoundsView`, `AspectRatioFrameLayout`, contrôles d’images et de widgets natifs |

### Flux de diffusion (ne pas casser)

```text
Fond noir → Composition OpenGL (RootEncoder GenericStream)
        ├── Écran/app → MediaProjection → ScreenOverlayPipeline → SurfaceFilterRender
        ├── Caméra CameraX → (option) ML Kit Selfie Segmentation + flou → CameraOverlayPipeline → SurfaceFilterRender
        ├── Chat → ChatOverlayRenderer → SurfaceFilterRender
        ├── Images / média local → SurfaceFilterRender
        └── Widgets natifs Canvas → SurfaceFilterRender
        ↓
H.264 1280×720 @ 30 FPS + AAC stéréo 44,1 kHz
        ↑
Micro (ou silence) + piste PCM du widget Média rééchantillonnée
        ↓
RTMP ou RTMPS
```

- La composition repose sur **RootEncoder** (`com.pedro.library.generic.GenericStream`) et des **`SurfaceFilterRender`**.
- Aperçu et stream partagent la même scène appliquée via `StreamService.applyScene`.
- `layerOrder` est exprimé du premier plan vers l’arrière-plan ; le widget `BACKGROUND`, s’il existe, est toujours normalisé en dernière position et installé en premier dans la pile OpenGL.
- La session **MediaProjection** est préparée dès la sélection système (aperçu), puis **réutilisée** au démarrage du stream — ne pas forcer une nouvelle sélection inutilement sauf après arrêt session/diffusion.
- Micro désactivé → vraie piste **AAC silencieuse** (`SilenceAudioSource`), pas l’absence totale d’audio si le pipeline l’exige.

### Constantes d’encodage typiques (StreamService)

Conserver la cohérence produit sauf demande explicite de profils qualité :

- Vidéo : H.264, **1280×720**, **30 FPS**, ~**4,5 Mbit/s**
- Audio : AAC stéréo, **44,1 kHz**, **128 kbit/s**

### Destinations par défaut (UI)

- Twitch : `rtmp://live.twitch.tv/app`
- YouTube : `rtmps://a.rtmps.youtube.com/live2`

L’URL du tableau de bord plateforme reste la référence utilisateur.

---

## Stack technique (versions de référence)

| Élément | Valeur |
|---|---|
| minSdk | 24 |
| targetSdk / compileSdk | 36 / 36 |
| Java / bytecode | 17 |
| AGP | 9.3.1 (racine) |
| CameraX | 1.6.1 |
| ML Kit Selfie Segmentation | 16.0.0-beta6 |
| RootEncoder | 2.7.2 (JitPack, branche compatible API 36) |
| View Binding | activé |
| Repos Maven | Google, Maven Central, JitPack |

Prérequis build : **JDK 17 complet**, Android SDK / Build-Tools **36**, accès réseau aux dépôts ci-dessus.

---

## Commandes utiles

```bash
# Build debug
./gradlew :app:assembleDebug
# ou
make install

# Lint
./gradlew :app:lintDebug
```

APK debug : `app/build/outputs/apk/debug/app-debug.apk`.

Windows : `.\gradlew.bat :app:assembleDebug`.

---

## Conventions de code

1. **Kotlin idiomatique** : `data class`, `enum class`, `companion object`, `runCatching` / `getOrDefault` déjà utilisés — rester cohérent.
2. **Nommage** : packages bas de casse ; classes PascalCase ; constantes en `UPPER_SNAKE` dans les services.
3. **UI strings** : l’interface utilisateur est en **français** (statuts « EN DIRECT », « PRÊT », toasts, labels). Préserver la langue FR pour les textes utilisateur.
4. **View Binding** : layouts XML + `ActivityMainBinding` ; pas d’introduction de Compose sans demande explicite.
5. **Modèle de scène** : coordonnées en **rectangles normalisés** `[0,1]` via `NormalizedRect` (contrainte taille min ~0.12). Toute nouvelle source visuelle doit s’intégrer à ce modèle + sérialisation JSON.
6. **Persistance** : via `SceneRepository` et JSON (`org.json`). Prévoir des fallbacks / champs legacy (ex. `screenEnabled`) lors d’évolutions de schéma.
7. **Threading** : callbacks service → UI via `runOnUiThread` / `Handler(Looper.getMainLooper())`. Pipelines caméra/écran : respecter les stratégies existantes (`KEEP_ONLY_LATEST` pour l’analyse caméra).
8. **Permissions** : CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS, FOREGROUND_SERVICE_* , MediaProjection. Toute nouvelle capability doit déclarer manifest + flux runtime cohérent avec `MainActivity` / `StreamService`.
9. **Foreground service** : types `camera|microphone|mediaProjection` — toute modification du service de stream doit rester alignée avec le manifest et les exigences Android 14+.
10. **Commentaires** : le codebase en commente peu ; ne pas sur-commenter. Expliquer uniquement le non-évident (OpenGL, MediaProjection, cycle de vie FGS).
11. **« Garder le ratio » (widgets)** : sauf précision explicite du product owner, tout widget qui expose ce paramètre doit, lorsqu’il est activé, **rogner (crop / cover)** le contenu pour remplir le cadre **sans le déformer** (ni stretch, ni letterbox/contain). Désactivé → étirement dans le cadre autorisé **sauf pour les widgets Image et Média**, dont le contenu est **toujours cropté** (cover) ; le switch ne verrouille alors que le ratio du cadre. À l’import d’un média vidéo, conserver la hauteur du cadre et adapter sa largeur au ratio d’affichage de la vidéo. Appliquer le même contrat au resize du cadre sur la scène, à l’aperçu et au pipeline de composition.
12. **Ordre de l’arrière-plan** : `WidgetType.BACKGROUND` n’est jamais déplaçable et reste toujours le dernier élément de l’ordre front→back, dans la sidebar, la persistance et la composition du stream.

---

## Sécurité et secrets (non négociable)

- **Clé de stream** : uniquement en **mémoire** pendant la session. **Jamais** dans SharedPreferences, fichiers, logs, analytics, crash reports ou commits.
- **OAuth / tokens** chat Twitch/YouTube : **ne pas** embarquer de secrets en dur dans l’APK. L’intégration chat réelle doit passer par un flux OAuth hors build hardcodé.
- `usesCleartextTraffic="true"` est présent pour RTMP non TLS : ne pas élargir inutilement la surface ; préférer RTMPS quand possible.
- Ne pas logger endpoints complets avec clé, ni dumps de `Intent` MediaProjection sensibles.

---

## État du MVP et limites connues

**Implémenté**

- Éditeur de scènes (création, sélection, suppression, persistance)
- Blocs écran, caméra, chat déplaçables / redimensionnables dans l’aperçu
- MediaProjection (écran ou app selon version Android)
- Caméra avant/arrière CameraX + orientation device
- Segmentation sujet + flou décor (ML Kit)
- Chat composé dans le flux (messages de **prévisualisation**)
- Images locales composées dans la scène (max 10)
- Widgets natifs Canvas / média : minuteur, formes, arrière-plan, bandeau, média vidéo, alertes, sondage / question et texte / lower third
- Routage audio du widget Média : sortie multimédia de l’appareil en aperçu, puis décodage/rééchantillonnage PCM et mix avec le micro dans la piste AAC du stream
- Ordre de calques partagé entre sidebar, aperçu et flux, avec arrière-plan verrouillé au fond
- Encodage + RTMP/RTMPS RootEncoder
- Foreground service typé

**Chat live (implémenté)**

- Twitch IRC WebSocket (`stream/chat/TwitchIrcChatClient`) : lecture anonyme `justinfan` ou OAuth `chat:read` + login
- YouTube Live Chat polling (`stream/chat/YouTubeLiveChatClient`) : `videos.list` → `liveChatMessages.list` avec jeton OAuth collé en mémoire
- Coordinateur dans `StreamService` ; pas de secrets OAuth en dur ni persistés

**Non implémenté (ne pas prétendre le contraire)**

- Flux OAuth in-app (AppAuth) — jetons collés manuellement pour YouTube / optionnel Twitch
- Chiffrement Keystore des destinations
- Profils 480p/1080p / ABR
- Déclenchement automatique des alertes et sondages via les API événementielles Twitch / YouTube
- Transitions de scènes, enregistrement local
- Suite de tests instrumentés multi-OEM

---

## Règles de modification pour les agents

### À faire

- Lire `README.md` + ce fichier avant un changement large.
- Préférer des diffs **minimaux** et localisés au package concerné.
- Mettre à jour sérialisation `toJson`/`fromJson` et UI si le modèle de scène change.
- Vérifier manifest + types FGS si le service ou les permissions changent.
- Après changements non triviaux : `./gradlew :app:assembleDebug` (et lint si pertinent).
- Documenter dans le README les comportements utilisateur visibles (pipeline, permissions, destinations).

### À éviter

- Introduire Compose, Hilt/Koin, Navigation Component, ou une clean architecture lourde sans demande.
- Remplacer RootEncoder sans analyse de faisabilité API 36 / MediaProjection / filters GL.
- Stocker la stream key ou des tokens OAuth.
- Dupliquer la logique de composition hors `StreamService` + pipelines `stream/`.
- Casser la compat minSdk 24 sans discussion (comportements conditionnels déjà présents, ex. partage d’app isolée dès Android 14).
- Modifier `local.properties` (machine-local) ou committer des secrets SDK.
- « Réparer » en affaiblissant la sécurité, les permissions ou la qualité d’encode par défaut.

### Fichiers sensibles / points d’attention

- `stream/StreamService.kt` — cycle de vie encodeur, bind, FGS, RTMP
- `stream/CameraOverlayPipeline.kt` — CameraX + ML Kit + orientation
- `stream/ScreenOverlayPipeline.kt` — MediaProjection → surface
- `MainActivity.kt` — permissions, binding service, UX scènes
- `model/Scene.kt` — contrat de persistance
- `model/WidgetModule.kt` — catalogue, limites et invariant d’ordre des calques
- `AndroidManifest.xml` — permissions et `foregroundServiceType`

---

## Style de communication agent

- Répondre dans la **langue de la demande utilisateur** (souvent le français sur ce projet).
- Résumer les changements de façon concise ; citer les chemins de fichiers pertinents.
- Signaler explicitement les risques MediaProjection / OEM / API level quand un fix n’est pas vérifiable sur device.

---

## Checklist rapide avant soumission

- [ ] Build `:app:assembleDebug` OK si code Kotlin/manifest/Gradle touché
- [ ] Pas de secret / stream key persistée ou loggée
- [ ] Scène JSON rétrocompatible ou migration douce
- [ ] UI FR cohérente
- [ ] Manifest aligné avec le service et les permissions
- [ ] README / guidelines mis à jour si comportement produit change
