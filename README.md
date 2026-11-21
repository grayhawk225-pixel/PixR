# PixR

Application Android de photographie et d'agrandissement **fidèle** : 100 % local, hors-ligne, **0 % génératif**. PixR n'invente aucun détail : chaque pixel de sortie provient d'échantillons réels de l'image ou de la rafale.

## Fonctionnalités

- **Atelier** : agrandissement ×2 / ×4 d'une photo existante, par tuiles avec chevauchement (mémoire maîtrisée), en trois modes :
  - *Standard* : interpolation fidèle préservant les contours ;
  - *Photo compressée* : réduction des artefacts JPEG par filtre bilatéral avant agrandissement ;
  - *Texte & Documents* : netteté locale douce des contours.
  - Dosage du **grain d'origine** (0–100 %), **recadrage HD** ciblé, comparateur Avant / Après, export JPEG 98 % ou PNG avec conservation des EXIF.
- **Multi & Lot** :
  - fusion de 3 à 6 photos similaires (« rafale a posteriori ») : alignement par blocs, rejet des zones en mouvement, fusion ×2 ;
  - traitement par lot de 2 à 20 photos en tâche de fond (WorkManager), avec pause si batterie < 15 % ou chauffe sévère.
- **Caméra** : rafale de 6 à 8 trames via Camera2, en `RAW_SENSOR` quand l'appareil le permet (repli `YUV_420_888` sinon), indicateur de stabilité par gyroscope, zoom 1x à 4x, mode « Texte / Loin ». Enregistrement unique dans `Pictures/PixR` avec EXIF complets.

## Architecture

- **Kotlin + Jetpack Compose (Material 3)** : interface, Camera2, WorkManager, export MediaStore / ExifInterface.
- **C++ (NDK, JNI)** : moteur `pixr_engine` (agrandissement, filtre bilatéral, fusion multi-photos, fusion RAW Bayer avec normalisation du niveau de noir, alignement, démosaïçage, matrice couleur et gamma sRGB).
- Logique pure (dimensions, tuiles, règles de stabilité, choix RAW/YUV, zoom, Bayer) isolée et couverte par des tests unitaires.

Le profil Camera2 de référence (Tecno Camon 40 Pro : niveau matériel `FULL`, capacités `RAW` et `BURST_CAPTURE`, `RAW_SENSOR` 4096×3072) est documenté dans `docs/`.

## Compilation

Prérequis : JDK 17, Android SDK (plateforme 35), NDK 28.2.13676358, CMake 3.22.1. Renseigner `sdk.dir` dans `local.properties` (non versionné).

```bash
./gradlew testDebugUnitTest   # tests unitaires
./gradlew assembleDebug       # APK debug : app/build/outputs/apk/debug/
```

`minSdk` 28, `targetSdk` 35.
