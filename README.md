# Nicovers06 Stream Studio

Studio de streaming **Android uniquement** permettant de composer une scène puis de la diffuser vers Twitch, YouTube ou un serveur RTMP(S) personnalisé.

L'application de quickstart as été générée par [android-qs-app-generator](https://github.com/nicolachoquet06250/android-qs-app-generator).

## MVP implémenté

- éditeur de scènes 16:9 avec création, sélection, suppression et persistance locale ;
- interface sombre adaptative : panneaux côte à côte sur fenêtre large et empilés progressivement sur mobile, portrait ou fenêtre Samsung DeX réduite ;
- catalogue de widgets (`WidgetModules`) avec plafond d’instances par scène et dropdown d’ajout ;
- ordre de superposition des widgets (`layerOrder`) : drag & drop par poignée dans la sidebar (haut = devant), appliqué immédiatement à la scène, l’aperçu et le flux ;
- blocs écran, caméra et chat déplaçables/redimensionnables directement dans l’aperçu ;
- partage de l’écran ou d’une application via `MediaProjection`, composé dans le bloc écran ;
- microphone, avec une vraie piste AAC silencieuse lorsqu’il est désactivé ;
- caméra avant/arrière via CameraX ;
- orientation de l’image caméra synchronisée avec le device, rotations à 90° et 180° comprises ;
- segmentation du sujet et flou du décor en temps réel ;

- **Garder le ratio** (partage d??cran / cam?ra) : verrouille le ratio du cadre au resize ; sinon cadre libre et vid?o **center-crop** (jamais d?form?e).
- rendu du chat dans la composition vidéo (prévisualisation + chat réel Twitch IRC / YouTube Live Chat) ;
- encodage H.264/AAC en 1280×720 à 30 FPS ;
- diffusion RTMP/RTMPS via RootEncoder ;
- foreground service typé caméra, microphone et media projection ;
- clé de stream conservée uniquement en mémoire (jamais dans les préférences ni dans les logs).

## Pipeline vidéo

```text
Fond noir → Composition OpenGL RootEncoder
        ├── Écran/application → MediaProjection → SurfaceFilterRender
        ├── Caméra CameraX
        │     → ML Kit Selfie Segmentation
        │     → sujet net + décor flouté
        │     → SurfaceFilterRender
        └── Chat → SurfaceFilterRender
        ↓
H.264 720p / AAC
        ↓
RTMP ou RTMPS
```

La caméra est analysée avec une stratégie `KEEP_ONLY_LATEST`. En mode flou, le masque ML Kit est lissé puis utilisé pour mélanger le sujet original avec une version réellement floutée du décor. Le résultat final est écrit dans la surface du bloc caméra : c’est donc bien le flux modifié, et non la caméra brute, qui est transmis dans la scène.

## Compiler

Prérequis :
- JDK 17 complet ;
- Android SDK 36 et Build Tools 36 ;
- connexion à Google Maven, Maven Central et JitPack.

```bash
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

Sous PowerShell :

```powershell
.\gradlew.bat :app:assembleDebug
```

L’APK de debug est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

## Lancer un stream

1. Ajoutez les widgets via le dropdown **Ajouter un widget** (chaque type a un maximum par scène, actuellement 1), activez/désactivez-les avec les interrupteurs, réordonnez-les via la poignée (haut = devant), puis positionnez les blocs écran/caméra/chat.
2. Dans **Partage d’écran**, appuyez sur **Choisir l’écran ou l’application** et validez la source dans le sélecteur Android.
3. Vérifiez immédiatement le contenu capturé dans le bloc **ÉCRAN** de la scène.
4. Choisissez Twitch, YouTube ou une destination personnalisée.
5. Vérifiez l’URL d’ingestion fournie par la plateforme et collez votre clé de stream.
6. Appuyez sur **Démarrer le stream**, puis accordez les éventuelles autorisations caméra/microphone.

La session de partage reste uniquement en mémoire. Elle démarre dès la validation du sélecteur afin d’alimenter l’aperçu, puis la même session est réutilisée au lancement du stream. Android demande une nouvelle sélection après l’arrêt de cette session ou de la diffusion.

Le partage d’une application isolée est proposé par le sélecteur système à partir d’Android 14. Sur Android 13 et antérieur, `MediaProjection` permet uniquement de sélectionner l’écran complet ; l’application l’indique directement sous la source.

Valeurs proposées par défaut :

- Twitch : `rtmp://live.twitch.tv/app`
- YouTube : `rtmps://a.rtmps.youtube.com/live2`

Le serveur exact peut varier selon le compte ou la région : l’URL affichée par le tableau de bord de la plateforme reste la référence.

## État du bloc de chat

Le bloc est composé dans le flux (aperçu + RTMP). Deux modes :

### Chat plateforme (réel)

- **Twitch** — connexion IRC WebSocket officielle (`wss://irc-ws.chat.twitch.tv:443`, tags `twitch.tv/tags`).  
  - Champ **chaîne** (login) obligatoire.  
  - Lecture **anonyme** (`justinfan…`) par défaut.  
  - Optionnel : token utilisateur OAuth scope `chat:read` + **login** du compte propriétaire du token (exigence IRC Twitch).
- **YouTube** — polling Live Streaming API :  
  1. `videos.list?part=liveStreamingDetails` → `activeLiveChatId`  
  2. `liveChatMessages.list` avec `pollingIntervalMillis`  
  - ID/URL de la **vidéo live** + **jeton OAuth** scope `youtube.readonly` (ou `youtube.force-ssl`).  
  - Le live doit être démarré pour exposer un `activeLiveChatId`.

Les jetons OAuth et la clé de stream restent **uniquement en mémoire** (champs mot de passe, extras Intent) : jamais SharedPreferences, fichiers ni logs. Aucun client secret n’est embarqué dans l’APK ; vous collez un jeton obtenu hors de l’app (console Google / flux OAuth de votre projet Twitch).

Bouton **Connecter le chat plateforme** : démarre l’ingestion dès l’aperçu. Au lancement du stream, la même config est renvoyée au service.

### Prévisualisation

Messages manuels de secours si aucune source live n’est connectée (destination personnalisée, ou champs incomplets).

## Choix techniques

| Élément | Valeur |
|---|---|
| Langage | Kotlin |
| minSdk | 24 |
| targetSdk / compileSdk | 36 / 36 |
| Gradle / AGP | 9.5.0 / 9.3.1 |
| CameraX | 1.6.1 |
| ML Kit Selfie Segmentation | 16.0.0-beta6 |
| RootEncoder | 2.7.2 (branche compatible API 36) |
| Sortie vidéo | H.264, 1280×720, 30 FPS, 4,5 Mbit/s |
| Sortie audio | AAC stéréo, 44,1 kHz, 128 kbit/s |

## Prochaines étapes recommandées

- flux OAuth in-app (AppAuth) pour éviter le collage manuel des jetons ;
- chiffrement local des destinations enregistrées avec Android Keystore ;
- profils qualité 480p/720p/1080p et adaptation du débit ;
- ajout de sources texte, image et navigateur ;
- transitions entre scènes et enregistrement local ;
- tests instrumentés sur plusieurs fabricants et versions Android.
