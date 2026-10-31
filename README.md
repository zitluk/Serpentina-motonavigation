# Serpentina-motonavigation

Pod ikonou v telefonu se aplikace jmenuje **Serpentina** (plný název by launcher zkrátil). Interní identifikátor balíčku zůstává `cz.motonavigace`.

Offline motonavigace pro Android inspirovaná aplikací [Cruiser](https://github.com/devemux86/cruiser).

- **Mapy:** [VTM](https://github.com/mapsforge/vtm) 0.25 (OpenGL engine od Mapsforge) nad offline `.map` soubory Mapsforge, téma MOTORIDER bez rušivých vrstev (parkoviště, obchody, MHD, restaurace…), 3D budovy
- **Zobrazení mapy:** 3D (nakloněná, po směru jízdy), 2D (po směru jízdy), Sever nahoře – tlačítko pod stahováním
- **Routing:** [BRouter](https://github.com/abrensch/brouter) 1.7.9 přímo v aplikaci, vlastní profil `app/src/main/assets/brouter/moto.brf`
  s typy tras Nejrychlejší / Nejkratší / Ekonomická / Více zatáček / Maximum zatáček a volbami bez dálnic / bez šotoliny / bez trajektů
- **Vyhledávání:** offline v POI databázích Mapsforge (`*.poi`) – obce, místa, body zájmu, nebo souřadnice
- **Navigace:** pokyny z BRouteru, české hlasové hlášky (TextToSpeech), přepočet po sjetí z trasy, mapa otočená ve směru jízdy
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
Výstupy buildu jsou mimo OneDrive (`~/Library/Caches/Motonavigace`), protože synchronizace kolidovala s Gradle cache.

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
