# PM Patcher

Android app to build the **Pokémon Platinum Multiplayer (Project PM)** ROM directly on your phone, optionally **in French**.
*Français plus bas.*

The **Project PM 0.4.7 patch is built in**, included with the permission of its authors. So are the optional **Visual+** extras (battle backgrounds and 3D battle camera), applied exactly like ProjectPMSetup does. You can still load another `.xdelta` file for a newer version. Those patches use LZMA secondary compression, which mobile patchers such as Rom Patcher JS or UniPatcher can't read; PM Patcher handles it natively.

## Download

Get the APK from the [Releases](../../releases) page. Android 7.0 or newer is required, and you need to allow installing apps from unknown sources.

## What you need

- A clean **Pokémon Platinum (USA) (Rev 1)** ROM (game code `CPUE`). The app checks its SHA-1 (`0862ec35b24de5c7e2dcb88c9eea0873110d755c`), which is the dump the patch was made from.
- For the French version only: a **Pokémon Version Platine (France)** ROM (game code `CPUF`).
- About 400 MB of free storage while patching.

**No ROM is included.** Use your own dumps.

## How to use

1. Choose the USA ROM. A ✓ appears when it is the right dump.
2. Keep the built-in Project PM 0.4.7 patch, or choose *Another .xdelta file…* for a newer version.
   *(Optional)* Tick **Visual+ battle backgrounds** and/or **Visual+ 3D battle camera**.
3. *(Optional)* Turn on **French version**, choose the French ROM, and keep or untick the French graphics.
4. Tap **Create the ROM** and pick where to save it.
5. Play it with a DS emulator. For online play against PC players, see [melonDS-android (Project PM online)](https://github.com/Nekodow/melonDS-android).

## Visual+ in Project PM 0.4.7+

Since 0.4.7 there is a single `.xdelta`; Visual+ is no longer a separate patch. ProjectPMSetup (and this app) adds it afterwards:
- **battle backgrounds**: replaces `battle/graphic/pl_batt_bg.narc` with the Visual+ NARC (same number of backgrounds);
- **3D camera**: finds the vanilla 440-byte battle camera table (it must appear exactly once) and swaps in the Visual+ table.

Applied to 0.4.4, this gives exactly the same ROM contents as the official `0.4.4-VisualPlusCamera.xdelta`.

## How the French version works

The app replaces the game text with the official French text taken from *your* French ROM. For the ~700 lines the mod adds or changes, it uses a hand translation that keeps the official French names (Hélio, Flo, Kiméra, Ecaillecoeur…). It also swaps in 25 French graphics that contain text: title screen, bag, summary, Pokétch, and others. A few things stay in English: proper names, debug menus, and graphics the mod itself modified.

## Building

```
./gradlew :app:assembleRelease
```

The core in `app/src/main/java/.../core` is plain Kotlin with no Android dependency:
- `Vcdiff.kt`: VCDIFF/xdelta3 decoder.
- `NdsRom.kt`: NDS filesystem and NARC handling.
- `GameText.kt`: Gen 4 text codec.
- `Francizer.kt`: French conversion.

It can be tested on a PC against real ROMs:

```
./gradlew :app:testDebugUnitTest -Dpm.dir=<folder with the ROMs and .xdelta files> -Dpm.out=<output folder>
```

### Notes on the xdelta format used by Project PM

- The secondary compressor is LZMA (id 2). Each section type (data, instructions, addresses) uses **one xz stream that continues across windows**: only the first window carries the xz header.
- These streams are flushed but never terminated. Read exactly the announced number of bytes and do not wait for an end-of-stream marker.

## Credits

- Project PM / Platinum Multiplayer by nUt, ComicartOlie and MottledAbyss. Their patch (`app/src/main/assets/patches`) is redistributed with their permission and remains their work.
- Visual+ (battle backgrounds and 3D camera) is the work of **MinT ChoK**; the backdrops were drawn by **Young** for *Pokemon Another Red*. The files in `app/src/main/assets/visualplus` come from the Project PM build.
- `charmap.txt` (Gen 4 character table) comes from the [pret/pokeplatinum](https://github.com/pret/pokeplatinum) decompilation project.
- LZMA decoding by [XZ for Java](https://tukaani.org/xz/java.html) (public domain / 0BSD).

Pokémon is a trademark of Nintendo, Game Freak and Creatures. This project is not affiliated with them.

---

# PM Patcher (français)

Application Android pour créer la ROM **Pokémon Platinum Multiplayer (Project PM)** directement sur ton téléphone, **en français** si tu veux.

**Le patch Project PM 0.4.7 est intégré**, avec l’accord de ses auteurs, ainsi que les options **Visual+** (fonds de combat et caméra 3D), appliquées comme le fait ProjectPMSetup. Tu peux aussi charger un autre `.xdelta` pour une version plus récente. Ces patchs utilisent une compression LZMA que les patchers mobiles (Rom Patcher JS, UniPatcher…) ne savent pas lire. PM Patcher la gère.

## Ce qu'il faut

- **Les ROMs :**
  - une ROM **Pokémon Platinum (USA) (Rev 1)** propre (code `CPUE`), vérifiée par son SHA-1 ;
  - pour la version française : une ROM **Pokémon Version Platine (France)** (code `CPUF`).
- **Environ 400 Mo** d'espace libre pendant la création.

**Aucune ROM n'est fournie.**

## Utilisation

1. Choisis la ROM USA. Un ✓ apparaît si c'est la bonne.
2. Garde le patch intégré Project PM 0.4.7, ou prends *Un autre fichier .xdelta…* pour une version plus récente.
   *(Optionnel)* Coche **Fonds de combat Visual+** et/ou **Caméra de combat 3D Visual+**.
3. *(Optionnel)* Active **Version française** et choisis la ROM FR.
4. Appuie sur **Créer la ROM** et choisis où l'enregistrer.
5. Joue avec un émulateur DS. Pour jouer en ligne avec des joueurs PC : [melonDS-android (Project PM online)](https://github.com/Nekodow/melonDS-android).

Les textes viennent de *ta* ROM française. Les ~700 textes ajoutés par le mod sont traduits à la main, avec les noms officiels FR.
