# Serpentina-motonavigation

Pod ikonou v telefonu se aplikace jmenuje **Serpentina** (plný název by launcher zkrátil). Interní identifikátor balíčku zůstává `cz.motonavigace`.

Offline motonavigace pro Android 

## Stažení do telefonu

**[Stáhnout Serpentina.apk](https://github.com/zitluk/Serpentina-motonavigation/releases/latest/download/Serpentina.apk)** – vždy nejnovější verze, Android 8.0 a novější.

Odkaz otevři v telefonu, stažený soubor spusť a povol instalaci z prohlížeče (Android se zeptá sám). Novější verze se instaluje přes starší, data a mapy zůstanou. Přehled verzí: [Releases](https://github.com/zitluk/Serpentina-motonavigation/releases).

- **Mapy:** [VTM](https://github.com/mapsforge/vtm) 0.25 (OpenGL engine od Mapsforge) nad offline `.map` soubory Mapsforge, téma MOTORIDER bez rušivých vrstev (parkoviště, obchody, MHD, restaurace…), 3D budovy
- **Zobrazení mapy:** 3D (nakloněná, po směru jízdy), 2D (po směru jízdy), Sever nahoře – tlačítko pod stahováním
- **Routing:** [BRouter](https://github.com/abrensch/brouter) 1.7.9 přímo v aplikaci, vlastní profil `app/src/main/assets/brouter/moto.brf`
  s typy tras Nejrychlejší / Nejkratší / Ekonomická / Více zatáček / Maximum zatáček a volbami bez dálnic / bez šotoliny / bez trajektů
- **Vyhledávání:** offline v POI databázích Mapsforge (`*.poi`) – obce, místa, body zájmu, nebo souřadnice
- **Navigace:** pokyny z BRouteru, české hlasové hlášky (TextToSpeech), přepočet po sjetí z trasy, mapa otočená ve směru jízdy Před manévrem na vícepruhové silnici ukáže doporučené pruhy a řekne je hlasem – odhad podle počtu pruhů a směru odbočení (offline data BRouteru neobsahují `turn:lanes`).
- **Více tras:** ke každému cíli až 3 varianty (BRouter alternativy) s délkou a časem; vybraná červeně, ostatní šedě
- **Ikona polohy:** šipka nebo silueta motocyklu (sportovní, cestovní, naked, enduro, chopper, skútr) v 8 barvách, natočená po směru jízdy
- **Projížďka po okolí:** okruh zvolené délky (20–300 km) z aktuální polohy, náhodný směr, tlačítko „Jiný okruh“
- **Záznam jízdy:** služba na popředí (běží i se zhasnutou obrazovkou), ukládá GPX se statistikami (vzdálenost, čas, Ø a max. rychlost, stoupání)
- **Náklon motocyklu:** menu → Náklon motocyklu – ukazatel náklonu, max. náklon vlevo/vpravo, rychlost a max. rychlost, zrychlení, max. zrychlení a brzdění (v g). Za jízdy se náklon počítá z gyroskopu a rychlosti GPS (sin φ = v·ω/g), při stání z polohy telefonu; maxima se ukládají a dají se vynulovat
- **GPX:** import (menu, obrazovka Trasy a jízdy, nebo otevření .gpx z jiné aplikace), zobrazení, navigace po stopě, sdílení
- **Ovládání v rukavicích:** tlačítka na mapě 64 dp, přiblížení tlačítky +/− (i během navigace), menu a volby jako velké položky přes celou šířku, tlačítka v celé aplikaci vysoká 64 dp, volby trasy 48 dp
- **Nastavení:** vzhled (podle systému / světlý / tmavý), výrobce a typ motocyklu (výrobce změní ikonu aplikace, typ přednastaví typ trasy, šotolinu, dálnice a strop rychlosti pro odhad času), jazyk aplikace i hlasové navigace (čeština / angličtina / podle systému), O aplikaci s licencemi
- **Data:** stahují se v aplikaci (Stažení map) do úložiště aplikace – mapa a POI z download.mapsforge.org, routovací dlaždice 5×5° z brouter.de. U stažené mapy se ukazuje verze (datum dat) a při novější verzi na serveru tlačítko Aktualizovat

## Ovládání
- Panel trasy dole se ukáže až po výběru cíle
- Lišta „Kam jedeme?“ (nebo menu ☰ → Hledat cíl) → vyber cíl → trasa z aktuální polohy
- Dlouhý stisk do mapy → Start / Průjezdní bod / Cíl (bez startu se vyjíždí z aktuální polohy)
- Panel dole: délka, čas, typ trasy, vyhýbání, **Navigovat** – volby se zalamují, takže jsou vidět na každém displeji; na šířku a na tabletu je panel vlevo
- Menu ☰ → Projížďka po okolí → zvol délku (posuvník nebo rychlá volba) → Vytvořit; „Jiný okruh“ vylosuje jiný směr, Obrátit pojede okruh opačně
- Menu ☰ → Spustit záznam jízdy (červený štítek REC na mapě, ťuknutím záznam zastavíš)
- Menu ☰ → Trasy a jízdy: seznam jízd a importovaných tras → Zobrazit / Navigovat po trase / Sdílet / Smazat
- Navigace po GPX: trasa se naplánuje po silnicích přes body vybrané ze stopy (max. 40), takže má pokyny i přepočet
- Tlačítko „3D / 2D / N“ vpravo nahoře přepíná zobrazení mapy (pamatuje se). Při navigaci se mapa natáčí podle směru silnice v místě jezdce, mimo trasu podle kurzu z GPS
- Během navigace: další manévr nahoře, příjezd a zbývající km dole, **Ukončit** (nebo tlačítko Zpět)

## Sestavení
Výstupy buildu jsou mimo OneDrive (`~/Library/Caches/Motonavigace`, na Windows `%USERPROFILE%\Library\Caches\Motonavigace`), protože synchronizace kolidovala s Gradle cache.

### macOS

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew --project-cache-dir ~/Library/Caches/Motonavigace/gradle assembleDebug
```

APK: `~/Library/Caches/Motonavigace/build/app/outputs/apk/debug/app-debug.apk`

Instalace do telefonu (USB ladění zapnuté):

```bash
~/Library/Android/sdk/platform-tools/adb install -r ~/Library/Caches/Motonavigace/build/app/outputs/apk/debug/app-debug.apk
```

Testy (statistiky jízdy):

```bash
./gradlew --project-cache-dir ~/Library/Caches/Motonavigace/gradle testDebugUnitTest
```

### Vydání

Vydání se podepisuje vlastním klíčem, který v repozitáři není. Na počítači vývojáře leží v `~/.android/serpentina-release.jks` a přihlašovací údaje v `~/.gradle/gradle.properties` (`serpentinaStoreFile`, `serpentinaStorePassword`, `serpentinaKeyAlias`, `serpentinaKeyPassword`). Bez nich se vydání sestaví nepodepsané. Klíč je nutné zálohovat, bez něj nepůjdou další verze nainstalovat přes stávající.

Postup: zvýšit `versionCode` a `versionName` v `app/build.gradle.kts`, sestavit `assembleRelease` a APK nahrát jako `Serpentina.apk` do nového vydání:

```bash
gh release create v0.2.0 "Serpentina.apk#Serpentina 0.2.0 (APK)" --title "Serpentina 0.2.0" --notes "…"
```

### Windows

Nástroje (bez instalátorů, stačí rozbalit):

| | Kam | Odkud |
|---|---|---|
| JDK 17 (Temurin) | `%LOCALAPPDATA%\Programs\Java\jdk-17…` | zip z adoptium.net |
| Android SDK | `%USERPROFILE%\Android\Sdk` | `commandlinetools-win-*.zip` do `Sdk\cmdline-tools\latest`, pak `android sdk install platform-tools platforms/android-37.0 build-tools/37.0.0` |

Uživatelské proměnné prostředí: `JAVA_HOME` na JDK, `ANDROID_HOME` na SDK, do `Path` přidat `%JAVA_HOME%\bin` a `%ANDROID_HOME%\platform-tools`.

> SDK nedávej do nové složky v `%LOCALAPPDATA%`, pokud ho instaluje Claude desktop. Aplikace ji přesměruje do svého sandboxu (`AppData\Local\Packages\AnthropicPBC.Claude_…`) a Průzkumník, cmd ani Android Studio ji pak nevidí.

`local.properties` se přes OneDrive sdílí s Macem a `sdk.dir` je na každém počítači jiné. Po přechodu na jiný počítač ho přepiš:

```properties
sdk.dir=C\:\\Users\\<uživatel>\\Android\\Sdk
```

Git hlásí všechny soubory jako změněné kvůli příznaku spustitelnosti z Macu. Jednou v repozitáři vypni sledování příznaku:

```bash
git config core.fileMode false
```

Build a testy (`gradlew.bat` v repozitáři není, spouští se přes Git Bash):

```bash
bash ./gradlew --project-cache-dir "$HOME/Library/Caches/Motonavigace/gradle" assembleDebug testDebugUnitTest
```

Instalace APK z PowerShellu:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r "$HOME\Library\Caches\Motonavigace\build\app\outputs\apk\debug\app-debug.apk"
```

#### Emulátor na Windows

1. V BIOSu/UEFI zapnout **Intel Virtualization Technology (VT-x)**. Ověření: `(Get-CimInstance Win32_Processor).VirtualizationFirmwareEnabled` musí být `True`.
2. Nainstalovat akceleraci AEHD: `android sdk install extras/google/Android_Emulator_Hypervisor_Driver`, pak v PowerShellu jako správce:
   ```powershell
   RUNDLL32.EXE SETUPAPI.DLL,InstallHinfSection DefaultInstall 132 $env:ANDROID_HOME\extras\google\Android_Emulator_Hypervisor_Driver\aehd.Inf
   sc.exe start aehd
   ```
   Ověření: `emulator -accel-check` vypíše `AEHD … is installed and usable`. Driver se spouští s Windows sám. AEHD nefunguje, když běží Hyper-V, Windows Hypervisor Platform nebo WSL2. Pokud je zapneš, emulátor musí přejít na WHPX.
3. Systémové obrazy a AVD:
   ```powershell
   android sdk install emulator system-images/android-26/google_apis/x86_64 system-images/android-35/google_apis/x86_64 system-images/android-37.0/google_apis/x86_64
   avdmanager create avd -n Android8 -k "system-images;android-26;google_apis;x86_64" --device pixel_6
   avdmanager create avd -n Motonavigace -k "system-images;android-35;google_apis;x86_64" --device pixel_6
   avdmanager create avd -n Android17 -k "system-images;android-37.0;google_apis;x86_64" --device pixel_6
   ```
4. Spuštění a poloha GPS (Praha):
   ```powershell
   & "$env:ANDROID_HOME\emulator\emulator.exe" -avd Motonavigace
   adb emu geo fix 14.4378 50.0755
   ```

## Kompatibilita s verzemi Androidu

| | |
|---|---|
| Nejstarší podporovaná verze | Android 8.0 (API 26) |
| Překlad a cílení (`compileSdk` / `targetSdk`) | Android 17 (API 37) |
| Nástroje | Android Gradle Plugin 9.5, Gradle 9.8, Kotlin vestavěný v AGP, JDK 17 |

Co je pro nové verze připravené:

- **Kreslení pod systémové lišty** (od Androidu 15 povinné, od cílení na API 36 nejde vypnout) – všechny obrazovky si odsazení řeší samy (`EdgeToEdge.kt`, `applyWindowInsets` v `MainActivity`), stejně i na starých verzích.
- **Úvodní obrazovka** – `androidx.core:core-splashscreen`: Android 12+ ukazuje ikonu spouštěcího aliasu (logo výrobce), Android 8–11 stejné logo ze stylu `Theme.Motonavigace.Starting.*`.
- **Gesto zpět s náhledem** (Android 16+ pro aplikace cílící na API 36) – jen `OnBackPressedDispatcher`, žádné zastaralé `onBackPressed`.
- **Velké displeje a otáčení** (Android 16+ ignoruje zámky orientace na tabletech) – aplikace žádnou orientaci nezamyká a rozložení se přizpůsobuje šířce.
- **16 KB stránky paměti** (Google Play pro Android 15+) – nativní knihovna VTM je zarovnaná na 16 KB.
- **Služba na popředí** pro záznam jízdy má typ `location` a oprávnění `FOREGROUND_SERVICE_LOCATION` (Android 14+); oznámení si říká o `POST_NOTIFICATIONS` (Android 13+).
- **Jazyk aplikace** – systémový výběr jazyka pro aplikaci (Android 13+, `localeConfig`), na starších verzích ho ukládá AppCompat.
- **Tematické ikony** (Android 13+) – všechny ikony mají monochromatickou vrstvu.
- Android Lint (`./gradlew lintDebug`) bez chyb a bez volání API, které na Androidu 8 neexistuje.

Ověřeno v emulátoru:

| Verze | Co se ověřilo |
|---|---|
| Android 8.0 (API 26) | instalace, všechny obrazovky (mapa, hledání, stažení map, trasy a jízdy, náklon, nastavení), záznam jízdy jako služba na popředí; bez pádů |
| Android 9 | mapa ve 3D, trasa, stahování, nastavení, jazyk, náklon, vyhledávání |
| Android 15 | aplikace běží |
| Android 17 (API 37) | totéž co Android 8.0 a navíc kreslení pod systémové lišty; bez pádů |

Na Androidu 8.0 a 17 se testovalo bez stažené mapy a routovacích dat, takže vykreslení mapy a plánování trasy jsou ověřené jen na Androidu 9.

## Známá omezení
- Čas se odhaduje podle třídy silnice a rychlostních limitů z OSM, ne podle skutečného provozu.
- Zatáčkové trasy se řídí třídami silnic, ne skutečnou geometrií zatáček.
- Data BRouteru neobsahují názvy silnic, takže pokyny jsou bez názvů ulic.
- Vyhledávání nezná adresy (ulice, čísla popisná) – jen obce, místa a body zájmu.
- Navigace běží jen s aplikací v popředí (obrazovka zůstává zapnutá).
- Délka okruhu se trefí zhruba na ±12 % (až 3 pokusy). Delší okruh může zasáhnout mimo stažené routovací dlaždice – pak je potřeba stáhnout sousední region.
- Navigace po GPX nekopíruje stopu bod po bodu – mezi vybranými body si BRouter volí cestu podle zvoleného typu trasy.
- Zatím bez stínování reliéfu a oblíbených míst.
- Náklon předpokládá telefon na řídítkách na výšku a vyrovnaný kalibrací; ověřeno jen v emulátoru simulovanými senzory, ne na skutečné jízdě
- Tmavý vzhled mění obrazovky aplikace, mapa zůstává světlá (VTM 0.25 nemá tmavou variantu stylu MOTORIDER)

## Ikona aplikace podle výrobce

Výběr výrobce v Nastavení přepne ikonu aplikace i logo na úvodní obrazovce (Android 12+ ji kreslí z ikony spuštěné aktivity). Android ikonu neumí změnit přímo – každý výrobce má `activity-alias` v manifestu a zapnutý je vždy jeden (`Settings.applyLauncherIcon`).

Loga 17 výrobců pocházejí z Wikimedia Commons, zdroje a licence jsou v [LOGOS.md](LOGOS.md). Benelli, Jawa a Moto Guzzi mají zástupnou ikonu s názvem značky. Vlastní logo: smaž `app/src/main/res/drawable/ic_brand_<značka>.xml` (pokud existuje) a vlož `app/src/main/res/drawable-nodpi/ic_brand_<značka>.png` – 432 × 432 px, průhledné pozadí, logo uprostřed v kruhu o průměru ~260 px.
