# DuelPlugin V7

Source project for the Practice plugin, targeting Paper 1.21.11.

## Build

Requirements: JDK 21 or newer and Maven 3.9 or newer.

From this directory, run:

```sh
mvn clean package
```

The plugin JAR is created at `target/Practice.jar`. Copy that file into the
server's `plugins` directory. The build includes SQLite JDBC and HikariCP;
Paper, PlaceholderAPI, and DecentHolograms are provided by the server.

## Build issues fixed

- The plugin declared `api-version: 1.21.11`, but was compiled against Paper
  API 1.21.4. The Paper API dependency now targets 1.21.11.
- Maven could not resolve the transitive `de.tr7zw:item-nbt-api:2.13.1`
  dependency required by DecentHolograms. The CodeMC Maven repository is now
  configured so Maven can resolve it.
- The source used API names and method signatures that do not compile against
  Paper 1.21.11. These include the luck enchantment constant, max-health
  attribute, and resource-pack setter. They have been updated for the target
  API. Missing arena deletion and spectating methods, a visibility issue, and
  a missing `UUID` import were also fixed because they blocked compilation.
- SQLite JDBC uses JNI native methods. Relocating its `org.sqlite` Java classes
  to `com.duel.libs.sqlite` made their names disagree with the native library,
  causing `UnsatisfiedLinkError` for `NativeDB._open_utf8`. SQLite is no longer
  relocated; keep its package name unchanged in future shading changes.
- The project targets Java 21 bytecode using Maven's `release` setting. This is
  compatible with the Java version used by Paper 1.21.x servers.

## Replacing an installed plugin

Stop the server before replacing the plugin. Remove old copies such as
`Practice (2).jar` from the server's `plugins` directory, then install the new
`target/Practice.jar`. Do not leave multiple copies of this plugin in that
directory, or the server may load an outdated JAR.
## Country flags (no resource pack)

Flags are player heads with textures from `plugins/DuelPlugin/flags.yml`
(country code → Base64 texture). The resource-pack server and `flags-pack.zip`
were removed. Tab list flags use the player-head object component and need
1.21.9+ clients; GUI flags are normal `PLAYER_HEAD` items and work for everyone.
Set `tab-flag.mode` in `config.yml`: `PAPER` (plugin sets the tab name) or
`PLACEHOLDER` (use `%duel_flaghead%` in the TAB plugin config).

## Spectator fly speed

Fly spectators get a feather (`✈ Fly Speed`, hotbar slot from `spectator.speed-item-slot`,
default 8) that opens a GUI with 0.5x–10x speeds and a Reset button. The speed is cleared
automatically as soon as the player leaves spectator mode.
