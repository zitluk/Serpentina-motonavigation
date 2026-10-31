# Loga výrobců

Ikony aplikace pro jednotlivé výrobce (`app/src/main/res/drawable-nodpi/ic_brand_*.png`) vznikly z log
na Wikimedia Commons. Soubory jsou tam vedené jako volné dílo (obvykle jako textové logo pod prahem
autorskoprávní ochrany). **Loga jsou ale ochranné známky svých vlastníků** – v aplikaci slouží jen
k označení motocyklu uživatele, nejde o spolupráci ani podporu ze strany výrobců. Před zveřejněním
aplikace (např. na Google Play) je vhodné použití log s výrobci ověřit.

| Výrobce | Zdroj | Licence na Commons |
|---|---|---|
| Aprilia | [Aprilia-logo.svg](https://commons.wikimedia.org/wiki/File:Aprilia-logo.svg) | Public domain |
| BMW | [BMW logo (gray).svg](https://commons.wikimedia.org/wiki/File:BMW_logo_(gray).svg) | Public domain |
| CFMOTO | [CFMOTO Logo.svg](https://commons.wikimedia.org/wiki/File:CFMOTO_Logo.svg) | Public domain |
| Ducati | [Ducati red logo.svg](https://commons.wikimedia.org/wiki/File:Ducati_red_logo.svg) | Public domain |
| Harley-Davidson | [Harley-Davidson logo.svg](https://commons.wikimedia.org/wiki/File:Harley-Davidson_logo.svg) | Public domain |
| Honda | [Honda Logo.svg](https://commons.wikimedia.org/wiki/File:Honda_Logo.svg) | Public domain |
| Husqvarna | [Husqvarna logo ArtWW.svg](https://commons.wikimedia.org/wiki/File:Husqvarna_logo_ArtWW.svg) | Public domain |
| Indian | [Indian Motorcycle logo.svg](https://commons.wikimedia.org/wiki/File:Indian_Motorcycle_logo.svg) | Public domain |
| Kawasaki | [Kawasaki-logo.svg](https://commons.wikimedia.org/wiki/File:Kawasaki-logo.svg) | Public domain |
| KTM | [KTM-Logo.svg](https://commons.wikimedia.org/wiki/File:KTM-Logo.svg) | Public domain |
| MV Agusta | [MV Agusta Logo.svg](https://commons.wikimedia.org/wiki/File:MV_Agusta_Logo.svg) | Public domain |
| Piaggio | [Piaggio-logo.svg](https://commons.wikimedia.org/wiki/File:Piaggio-logo.svg) | Public domain |
| Royal Enfield | [Royal Enfield logo.svg](https://commons.wikimedia.org/wiki/File:Royal_Enfield_logo.svg) | Public domain |
| Suzuki | [Suzuki logo 2025 (symbol).svg](https://commons.wikimedia.org/wiki/File:Suzuki_logo_2025_(symbol).svg) | Public domain |
| Triumph | [Triumph Motorcycles logo and claim 2015.svg](https://commons.wikimedia.org/wiki/File:Triumph_Motorcycles_logo_and_claim_2015.svg) | Public domain |
| Vespa | [Vespa-logo.svg](https://commons.wikimedia.org/wiki/File:Vespa-logo.svg) | Public domain |
| Yamaha | [Yamaha Motor logo.svg](https://commons.wikimedia.org/wiki/File:Yamaha_Motor_logo.svg) | Public domain |

Benelli, Jawa a Moto Guzzi na Commons volně použitelné logo nemají – používají zástupnou ikonu
s názvem značky (`app/src/main/res/drawable/ic_brand_*.xml`, písmo Roboto, Apache 2.0).

Ikony se skládají skriptem `tools/compose_icons.py` (Pillow) ze stažených PNG
(`tools/download_logos.py`).
