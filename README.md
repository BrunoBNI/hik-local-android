# HikLocal — application Android

Application autonome pour enregistreur Hikvision. Elle parle **directement à
l'appareil** en RTSP et ISAPI : aucun PC ni service intermédiaire n'est
nécessaire, seulement le téléphone et l'enregistreur sur le même réseau.

Compatible **Android 8.0 et ultérieur** (minSdk 26).

---

## Ce que fait l'application

**Connexion** — adresse de l'appareil, identifiant, mot de passe. Les
identifiants ne sont conservés que si vous cochez la case, et restent sur le
téléphone.

**Direct** — les caméras sont listées avec les noms configurés dans
l'enregistreur (« Salon (4) »), les entrées sans caméra branchée sont
masquées. Flèches de part et d'autre de l'image pour changer de caméra, son
coupé par défaut, photo enregistrée dans la galerie, bascule entre flux
principal et secondaire, pavé d'orientation pour les caméras motorisées.

**Lecture** — choix de la date, frise horaire sur 24 heures, boutons de
déplacement de ±1 et ±5 minutes.

---

## Compiler l'APK

### Solution 1 — Android Studio

1. Ouvrir Android Studio, `File > Open`, choisir ce dossier.
2. Laisser la synchronisation Gradle se terminer (elle télécharge les
   dépendances au premier lancement).
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.

L'APK apparaît dans `app/build/outputs/apk/debug/app-debug.apk`.

### Solution 2 — sans rien installer, via GitHub

1. Créer un dépôt sur GitHub et y envoyer ce dossier.
2. Ouvrir l'onglet **Actions** : la compilation démarre toute seule.
3. À la fin, télécharger l'artefact **HikLocal-debug-apk**.

Le fichier `.github/workflows/build-apk.yml` s'occupe du reste.

### Solution 3 — en ligne de commande

Avec un JDK 17 et le SDK Android installés :

```
./gradlew assembleDebug          # Linux, macOS
gradlew.bat assembleDebug        # Windows
```

---

## Installer sur le téléphone

L'APK n'étant pas signé pour le Play Store, Android demandera d'autoriser
l'installation depuis une source inconnue : c'est normal pour une application
que vous compilez vous-même. Copiez le fichier sur le téléphone et ouvrez-le.

---

## Points techniques

**Trafic en clair.** Depuis Android 9, le HTTP non chiffré est bloqué par
défaut. Un enregistreur de réseau local n'a pas de certificat TLS :
`res/xml/network_security_config.xml` l'autorise donc. L'application ne
contacte que l'appareil dont vous saisissez l'adresse.

**RTSP en TCP.** Le transport UDP passe mal sur beaucoup de réseaux Wi-Fi.
L'application force le TCP, plus fiable, au prix d'une latence légèrement
supérieure.

**Authentification Digest.** Les appareils Hikvision refusent le Basic.
OkHttp ne fournit pas le Digest en standard : il est implémenté dans
`DigestAuthenticator.kt` plutôt que d'ajouter une dépendance externe.

**Horodatage de lecture.** Les heures choisies sont envoyées telles quelles à
l'enregistreur, sans conversion de fuseau : il les interprète dans son propre
temps, ce qui correspond à ce qui est incrusté sur les images.

---

## Limites connues

- **Pas de vitesse de lecture accélérée.** Le flux RTSP d'archive ne permet ni
  déplacement ni changement de cadence côté téléphone ; se déplacer relance la
  lecture au nouvel instant. La version PC contourne cela en réencodant le
  flux, ce qu'un téléphone ferait au prix de la batterie.
- **Pas de mosaïque.** Afficher six flux simultanés décode six vidéos à la
  fois : c'est lourd pour un téléphone et gourmand en batterie. À ajouter
  ensuite si le besoin se confirme, en flux secondaire et en limitant le
  nombre de vignettes.
- **Pas d'extraction d'extraits.** Elle suppose de réencoder la vidéo, donc
  d'embarquer un encodeur.

---

## Structure

```
app/src/main/java/com/hiklocal/
  DigestAuthenticator.kt   authentification HTTP Digest
  HikApi.kt                appels ISAPI et construction des URL RTSP
  Prefs.kt                 réglages persistants
  LoginActivity.kt         écran de connexion
  MainActivity.kt          direct, navigation, orientation, photo
  PlaybackActivity.kt      relecture des enregistrements
```
