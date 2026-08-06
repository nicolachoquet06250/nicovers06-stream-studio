# Nicovers06 Stream Studio

Studio de streaming **Android uniquement** permettant de composer une scène puis de la diffuser vers Twitch, YouTube ou un serveur RTMP(S) personnalisé.

L'application de quickstart as été générée par [android-qs-app-generator](https://github.com/nicolachoquet06250/android-qs-app-generator).

## MVP implémenté

- éditeur de scènes 16:9 avec création, sélection, suppression et persistance locale ;
- blocs écran, caméra et chat déplaçables/redimensionnables directement dans l’aperçu ;
- partage de l’écran ou d’une application via `MediaProjection`, composé dans le bloc écran ;
- microphone, avec une vraie piste AAC silencieuse lorsqu’il est désactivé ;
- caméra avant/arrière via CameraX ;
- orientation de l’image caméra synchronisée avec le device, rotations à 90° et 180° comprises ;
- segmentation du sujet et flou du décor en temps réel ;
- rendu du chat dans la composition vidéo ;
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

1. Activez les sources de la scène et positionnez les blocs écran/caméra/chat.
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

Le bloc est entièrement composé dans le flux et accepte des messages de prévisualisation. La lecture des messages réels Twitch/YouTube n’est pas activée dans ce premier MVP, car elle requiert l’enregistrement d’applications, OAuth et les identifiants propres au projet. L’intégration suivante devra alimenter `ChatComponent.messages` depuis les API Twitch EventSub/IRC et YouTube LiveChatMessages ; aucun secret OAuth ne doit être embarqué en dur dans l’APK.

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

- OAuth Twitch et Google/YouTube puis fournisseurs de chat réels ;
- chiffrement local des destinations enregistrées avec Android Keystore ;
- profils qualité 480p/720p/1080p et adaptation du débit ;
- ajout de sources texte, image et navigateur ;
- transitions entre scènes et enregistrement local ;
- tests instrumentés sur plusieurs fabricants et versions Android.
