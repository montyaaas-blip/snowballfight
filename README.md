# ❄ SnowFight – vánoční minihra pro Paper

Sněhová bitva pro jednu arénu a pár hráčů. Každý dostane čepici Santy a sněhové koule,
zásah koulí = 1 bod. Vyhrává ten, kdo první dosáhne cílového skóre (výchozí 15),
nebo kdo má nejvíc bodů po uplynutí času (výchozí 3 minuty).

- Zasažený hráč je na 2 s zamrzlý a zpomalený (nejde ho „farmit" zásahy)
- Koule se automaticky doplňují
- Sidebar se skóre, bossbar s časem, ohňostroj pro vítěze
- Hráčovi se po hře vrátí inventář, pozice, gamemode i zdraví

Cílová verze: **Paper 26.2**, Java 25.

## Sestavení

Potřebuješ JDK 25 a Gradle 9.1+:

```
gradle build
```

Výsledek: `build/libs/SnowFight-1.0.0.jar` → nahraj do `plugins/` na serveru.

Bez JDK 25 lokálně: nahraj složku na GitHub, workflow `.github/workflows/build.yml`
jar sestaví sám (záložka Actions → Artifacts).

## Nastavení arény (jednorázově, jako OP)

1. Stoupni si do čekárny: `/snowfight setlobby`
2. Postav arénu a na různých místech v ní: `/snowfight addspawn` (aspoň 1, ideálně pro každého hráče jeden)
3. Hotovo – hráči píšou `/snowfight join`

## Příkazy

| Příkaz | Kdo | Co dělá |
|---|---|---|
| `/snowfight join` | všichni | připojí se do hry |
| `/snowfight leave` | všichni | odejde ze hry |
| `/snowfight status` | všichni | stav hry |
| `/snowfight setlobby` | OP | nastaví čekárnu |
| `/snowfight addspawn` | OP | přidá spawn v aréně |
| `/snowfight clearspawns` | OP | smaže spawny |
| `/snowfight start` | OP | vynutí start i s 1 hráčem |
| `/snowfight stop` | OP | zruší hru |

Aliasy: `/snow`, `/snehovka`. Oprávnění: `snowfight.play` (default všichni), `snowfight.admin` (default OP).

Vše ostatní (počet hráčů, délka, cílové skóre, rádius arény…) je v `plugins/SnowFight/config.yml`.
