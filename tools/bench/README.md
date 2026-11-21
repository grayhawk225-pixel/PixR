# Banc de test du moteur de fusion

Mesure objective de la qualité du moteur natif (`pixr_engine.cpp`) **sans téléphone** : scène synthétique connue,
bruit de capteur réaliste (Poisson + lecture), décalages sous-pixel de la main, objet en mouvement.

```bash
tools/bench/run.sh 400    # 400 = électrons à saturation du capteur (petit = peu de lumière)
```

Prérequis : `g++`, un JDK. Le moteur est compilé pour le PC avec `-DPIXR_BENCH` (les variables d'environnement de réglage
`PIXR_*` ne sont lues que dans cette configuration, jamais dans l'application).

Ce qui est mesuré (PSNR contre la vraie scène, plus haut = mieux) :
1. zone plate (réduction du bruit) et zone très détaillée (résolution), selon le nombre d'images et le débruitage ;
2. super-résolution ×2 (détail, contour, zone plate) ;
3. objet en mouvement (fantômes) ;
et, en option, `Perf` (temps de fusion) et la comparaison de la fusion de galerie.

Les mesures du PC ne remplacent pas un test sur le téléphone : elles servent à comparer deux versions du moteur.
