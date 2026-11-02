# Building Ore Radar

`DESIGN.md` is the contract; this file covers the build only.

## Quick start

```bash
export JAVA_HOME=/opt/graalvm-21      # Gradle 9.5 and Loom 1.17 run on Java 21 (build.sh's default if it exists)
./build.sh                            # core tests, all five jars, dist/ + dist/SHA256SUMS
./build.sh --offline                  # same, once every dependency is cached
./build.sh core mc1201 paper          # only these targets (core, mc1201, mc1211, paper)
```

Each target is its own Gradle build, so a broken one does not hold up the others: name the
ones to build. Only the named targets' jars are checked and replaced in `dist/` (a full build
empties it first), and `SHA256SUMS` covers every jar there. `build.sh` passes any other
argument to every Gradle call. It runs Gradle through
`tools/limited.sh` (CPU weight 20, `nice 19`, 9 GiB cap, no swap; plain `nice` where the user's
systemd is unavailable), because a live game server shares this machine. Use that wrapper for
any heavy command you run by hand too.

| Target | Gradle output | `dist/` name |
|---|---|---|
| Forge 1.20.1 | `mc1201/forge/build/libs/` | `oreradar-2.0.0+1.20.1-forge.jar` |
| Fabric 1.20.1 | `mc1201/fabric/build/libs/` | `oreradar-2.0.0+1.20.1-fabric.jar` |
| NeoForge 1.21.1 | `mc1211/neoforge/build/libs/` | `oreradar-2.0.0+1.21.1-neoforge.jar` |
| Fabric 1.21.1 | `mc1211/fabric/build/libs/` | `oreradar-2.0.0+1.21.1-fabric.jar` |
| Paper | `paper/build/libs/` | `oreradar-2.0.0-paper.jar` |

## Layout: four independent Gradle builds, one wrapper

```
gradlew, gradle/wrapper/     the only wrapper; pass -p <build>
gradle.properties            mod identity + Loom/ModDevGradle versions, shared by all builds
gradle/conventions.gradle    every module: identity, Java level, metadata expansion, jar manifest
gradle/common.gradle         a common module: adds core's and shared/java's sources, publishes them to loaders
gradle/loader.gradle         a loader module: compiles common + core into its jar, seeds run dirs
core/                        pure Java + JUnit (its own build: ./gradlew -p core test)
mc1201/   common, forge, fabric          (settings.gradle, gradle.properties: 1.20.1 pins)
mc1211/   common, neoforge, fabric       (settings.gradle, gradle.properties: 1.21.1 pins)
paper/                                   (settings.gradle, gradle.properties: Paper pins)
shared/java/                 vanilla code identical on 1.20.1 and 1.21.1 (both commons compile it)
shared/gametest-java/        GameTest code identical on both versions
shared/resources/            assets (textures, models, blockstates, lang) for both versions
mc1201/gametest.gradle       the 1.20.1 GameTest mod (below); mc1211/gametest.gradle the 1.21.1 one
tools/limited.sh, tools/smoke-server.sh
tools/test/                  1.20.1 client rig (Xvfb :81, dev server/client, screenshots)
tools/test-1211/             1.21.1 client rig (Xvfb :82), reusing tools/test/'s helpers
tools/test-paper/            Paper test servers and mineflayer bot suites
runs/<build>-<module>/<run>  dev game directories (git-ignored)
```

Each Minecraft build follows the MultiLoader pattern (jaredlll08's MultiLoader-Template):
`common` compiles the shared code, plus `core/src/main/java`, against plain Minecraft with
Mojang's names (ModDevGradle vanilla mode: `legacyForge.mcpVersion` for 1.20.1,
`neoForge.neoFormVersion` for 1.21.1). That proves common uses no loader API. A class that is
byte-identical on both Minecraft versions lives once in `shared/java/` (tests: `shared/gametest-java/`),
which both commons add as a source folder; `<build>/common/src` keeps only the classes that differ.
A fix to a shared class is made once and must compile on both versions: build both after editing.
To change a shared class for one version only, move it back into both commons first. Each loader module
then compiles the same sources into its own jar against its own Minecraft, through the
`commonJava` / `commonResources` configurations. Common is also a `compileOnly` dependency of the
loaders so IDEs resolve it. Paper adds core's sources directly.

**Why not one root build.** Loom and ModDevGradle coexist fine in one build: each Minecraft build
uses both. Putting both Minecraft versions in one build is the problem:

- Every Gradle call would configure both Minecraft versions, each with its own Loom and MDG setup.
  That is slower and tight under the 2 GiB Gradle heap.
- The 1.20.1 and 1.21.1 work runs in parallel. Separate builds have separate `.gradle/` state and
  separate Loom project locks, so a dev client running in one never blocks a build of the other.
- A broken 1.21.1 build cannot block a 1.20.1 release.
- `core/` is already a standalone build for its unit tests. One wrapper at the root serves all
  four builds through `-p`, so core needs no wrapper of its own.

The cost is one indirection: the shared `gradle.properties` at the root is read by each build's
`settings.gradle` (plugin versions) and by `gradle/conventions.gradle` (identity), not by Gradle
itself. Gradle's own switches (`org.gradle.jvmargs=-Xmx2G`, `daemon=false`, `parallel=false`)
are repeated in each build's `gradle.properties`, because Gradle reads them only from the build's
root. Metadata files (`mods.toml`, `neoforge.mods.toml`, `fabric.mod.json`, `plugin.yml`,
`pack.mcmeta`) may use any key from the shared file or from the build's own `gradle.properties`
as `${key}`.

## Commands

```bash
./gradlew -p mc1201 build                    # common, forge, fabric
./gradlew -p mc1201 :forge:runServer         # dev server (see Runs)
./gradlew -p mc1201 :forge:runGameTestServer # 22 GameTests, then exits
./gradlew -p mc1201 :fabric:runGametest      # the same 22; JUnit report in fabric/build/gametest/junit.xml
./gradlew -p mc1201 :forge:runBenchServer    # times scans on the dev world around spawn, then halts
./gradlew -p mc1211 :neoforge:runGameTestServer  # 26 GameTests, then exits
./gradlew -p mc1211 :fabric:runGametest      # the same 26
./gradlew -p paper build
./gradlew -p core test
tools/smoke-server.sh mc1201 forge           # boot, check the mod loaded, "stop", exit 0/1
tools/test-paper/run-all.sh 1.20.1 1.21.1 26.3   # the Paper suite (no argument prints its usage)
```

## Runs

Every loader module has a client, a dedicated server, a GameTest server and `benchServer`. Their game directories live in `runs/<build>-<module>/{client,server,gametest}`,
outside `build/`, so `clean` keeps the worlds.

| Module | Client | Dedicated server | GameTest server | Server port |
|---|---|---|---|---|
| `mc1201/forge` | `runClient` | `runServer`, `runBenchServer` | `runGameTestServer` | 25611 |
| `mc1201/fabric` | `runClient` | `runServer`, `runBenchServer` | `runGametest` | 25612 |
| `mc1211/neoforge` | `runClient` | `runServer`, `runBenchServer` | `runGameTestServer` | 25621 |
| `mc1211/fabric` | `runClient` | `runServer`, `runBenchServer` | `runGametest` | 25622 |

- **Ports and displays.** DESIGN.md §12 gives each vertical a range: 1.20.1 25611–25619 and X display
  `:81`, 1.21.1 25621–25629 and `:82`, Paper 25631–25639 and `:83` (`tools/test-paper/server.sh`:
  1.20.1 on 25631, 1.21.1 on 25632, 26.3 on 25633). The DeceasedCraft compat rig uses `:77` and
  127.0.0.1:25601. Never use 25565, 25566, 25575 or UDP 24454.
- **Seeding.** Before a server or GameTest run, `seedServerRunDirs` writes `eula.txt`
  (`eula=true`) and `server.properties` into `server/` and `gametest/` if they are missing. The
  properties bind to `127.0.0.1`, use offline mode, disable RCON and query, and set the port from
  `dev_server_port_<module>` in the build's `gradle.properties`. The files are never overwritten
  (Minecraft adds its defaults on first boot and keeps these values), so after changing the
  property, edit `server-port` in the existing run directories too.
- **Memory.** Servers and GameTests get `-Xmx2G`, clients `-Xmx3G` (DESIGN.md §12). Before starting
  a game process, check that `MemAvailable` covers its heap plus 10 GiB: a live server shares the box.
- **Console.** Run tasks forward the terminal to the game's stdin, so `stop` typed into
  `./gradlew … runServer` stops the server cleanly. `tools/smoke-server.sh` relies on this.
- **The GameTest mod.** The tests are a second mod, `oreradar_gametest`, built from the `gametest`
  source set (`<build>/gametest.gradle`: `common/src/gametest` holds the shared tests,
  `shared/gametest-java` the code that is the same on both versions (`ScanBenchmark`), each loader's `src/gametest` its glue, test data and mod metadata). Only the
  GameTest and bench runs load it, so no test code ships in a release jar. It also generates the
  empty structure templates the tests are placed in (`oreradar:empty`, `oreradar:tall`).
  - Forge enables the `oreradar` namespace (`forge.enabledGameTestNamespaces`). Forge runs
    GameTests in the dedicated server's own level, a normal world full of ore at the tests' y −60,
    so the task `flatGameTestWorld` (run before `runGameTestServer`) sets `level-name=flat` and
    `level-type=minecraft:flat` in `runs/mc1201-forge/gametest/server.properties`. NeoForge runs
    them on vanilla's GameTest server, in a flat world of its own, so it needs no such task.
  - Fabric's run sets `-Dfabric-api.gametest` and writes a JUnit report to
    `build/gametest/junit.xml`; the tests are registered through the test mod's
    `fabric-gametest` entrypoint.
  - **Read the log, not the exit status.** Success is `All 22 required tests passed :)` on both
    1.20.1 loaders and `All 26 required tests passed :)` on both 1.21.1 loaders. With zero tests
    registered the runs disagree:

  | Run | With zero tests |
  |---|---|
  | Forge 1.20.1 | `All 0 required tests passed :)`, exit 0 |
  | Fabric 1.20.1 | `IllegalArgumentException: No test batches were given!`, exit 1 |
  | NeoForge 1.21.1 | `Failed to start the minecraft server … No test functions were given!` logged at ERROR, **exit 0** |
  | Fabric 1.21.1 | `IllegalArgumentException: No test functions were given!`, exit 1 |
- **`runBenchServer`** starts the dedicated server (in `runs/<build>-<module>/server`, so on its
  generated world) with the test mod and `-Doreradar.bench=true`. `ScanBenchmark` times scans
  under the default rules (`ores`) and under `allowAll` from three heights at spawn, whole and
  budgeted as the server runs them, logs lines starting `Ore Radar bench:` and halts the server.
- Do not launch dev clients without a display. `-Poreradar_client_args=...` passes extra game
  arguments to `runClient` (the test rigs use it for quick-play, username and window size).
- Paper has no run task: the plugin is tested on real Paper server jars with
  `tools/test-paper/` (DESIGN.md §12). Its kit lives in `runs/paper-test/` (git-ignored; set
  `OR_SCRATCH` to use another folder). Nothing fetches it for you except the vanilla client:
  - `paper-servers/paper-<version>-<build>.jar`: Paper 1.20.1, 1.21.1 and 26.3 from papermc.io;
  - `jdk25/`: a JDK 25 (Temurin) for Paper 26.3;
  - `bot/node_modules/`: mineflayer (`npm install mineflayer` in `bot/`; Node is `/opt/ci/node`);
  - `paper-runs/26.3/plugins/`: the ViaVersion and ViaBackwards jars, so mineflayer's 26.1 bots
    can join 26.3;
  - `vanilla/mc/`: the vanilla client for `client.sh`, which downloads it on first use.

## Versions pinned, and why

| What | Version | Why |
|---|---|---|
| Gradle | 9.5.0 (wrapper, sha256-checked) | the Gradle Loom 1.17 is built against; cached on this machine |
| Java running Gradle | 21 (`/opt/graalvm-21`) | Loom 1.17 needs 21 |
| Java toolchains | 17 (1.20.1, Paper), 21 (1.21.1) | what each Minecraft ships with |
| Fabric Loom | 1.17.21, plugin id `net.fabricmc.fabric-loom-remap` | newest line on Java 21; 1.18+ needs Java 25 and Gradle 9.7+. `-remap` is the id for obfuscated (pre-26.x) versions |
| ModDevGradle | 2.0.148 (`net.neoforged.moddev`, `net.neoforged.moddev.legacyforge`) | newest 2.0.x; one plugin for Forge 1.20.1, NeoForge 1.21.1 and both commons. ForgeGradle 6 does not run on Gradle 9 |
| foojay-resolver-convention | 1.0.0 | lets Gradle download a missing JDK |
| Mappings | Mojang official everywhere, no Parchment | common code compiles unchanged on both loaders of a version |
| Forge | 1.20.1-47.4.0 | the version the live DeceasedCraft server runs, so nothing from a later 47.4.x slips in (latest is 47.4.28). `mods.toml` accepts `[47,)`, so NeoForge 47.1 for 1.20.1 also loads it |
| MCP config (1.20.1 common) | 1.20.1 | vanilla mode of MDG's legacy plugin |
| NeoForge | 21.1.265 | latest 21.1.x; `neoforge.mods.toml` requires `[21.1.0,)` |
| NeoForm (1.21.1 common) | 1.21.1-20240808.144430 | the 1.21.1 release NeoForm |
| Fabric Loader | 0.20.1 (dev); jars accept `>=0.15` | latest stable; DESIGN.md §1 sets the floor |
| Fabric API | 0.92.12+1.20.1, 0.116.17+1.21.1 | latest for each version |
| Team Reborn Energy | 3.0.0 (1.20.1), 4.1.0 (1.21.1), jar-in-jar | 3.0.0 needs MC ≥1.19.4 and Java 17; 4.1.0 is the last for 1.21–1.21.4 (4.2.0 needs 1.21.5, 5.0.0 needs 26.1) |
| Paper API | 1.20.1-R0.1-20230921.165944-178 | the final build of `1.20.1-R0.1-SNAPSHOT`, pinned so a cached build never asks the network whether the snapshot moved |

## Known quirks

- **First builds are slow, later ones are not.** ModDevGradle decompiles and recompiles
  Minecraft once per version and loader (ForgeFlower for 1.20.1), then caches the result in
  `~/.gradle/caches`. Loom remaps Minecraft once per version. The first dev run of each loader
  downloads the game's assets (about 3,500 files). See Timings.
- **JDK 17 on this machine.** Gradle 9.5 did not recognise the JDK 17 that Gradle 8.8 had
  provisioned (`~/.gradle/jdks/eclipse_adoptium-17-amd64-linux`, older directory layout) and
  provisioned the same 17.0.21 again as `eclipse_adoptium-17-amd64-linux.2` on the first build.
  Both are Temurin 17.0.21; the `.2` one is used.
- **Forge jar names.** MDG's legacy plugin moves the Mojang-named `jar` output to
  `build/devlibs/` and `reobfJar` writes the SRG-named release jar to `build/libs/` under the
  same name. Only `build/libs/` is a release. The 1.20.1 common module also runs a `reobfJar`;
  its output is never shipped.
- **Fabric jar names.** Loom writes the dev jar to `build/devlibs/*-dev.jar` and the
  intermediary-mapped release jar (`remapJar`) to `build/libs/`.
- **Core is compiled three ways:** by its own build (`-p core`, tests), into each common module
  (against vanilla, Java 17 or 21), and into Paper. Core must stay free of Minecraft and of any
  library; only its own build proves that, because the common modules have Minecraft on the
  classpath.
- **Reproducible jars.** Two builds from clean trees produced byte-identical jars (same
  `SHA256SUMS`).
- **Harmless output.** Loom prints the Mojang mappings licence banner on every configuration.
  The 1.21.1 `remapJar` prints `duplicate input class net/fabricmc/fabric/mixin/tag/package-info`
  (two Fabric API modules ship the same `package-info`). Forge and NeoForge dev servers log
  `Assets URL 'union:…' uses unexpected schema`. Forge logs `fml.toml is not correct.
  Correcting` on a fresh run directory.
- Gradle suggests the configuration cache; it stays off because Loom and MDG are not fully
  compatible with it.
- **IDEs.** Open `mc1201/`, `mc1211/`, `paper/` and `core/` as separate Gradle projects, with
  Gradle 9.5.0 and JDK 21. Only the root holds a wrapper.

## Timings and verification (development machine, 2026-10-29)

Measured under `tools/limited.sh`, with the live server and other agents' work running.

| Step | Time |
|---|---|
| `mc1201` first build, cold caches (MDG decompiles and recompiles vanilla 1.20.1 and Forge 47.4.0, Loom remaps 1.20.1, Gradle provisions JDK 17) | 3 min 37 s |
| `mc1211` first build, cold caches (NeoForm and NeoForge 21.1.265, Loom 1.21.1), run alongside the one above | 3 min 1 s |
| `paper` first build | 27 s |
| `./build.sh` from clean trees, warm caches (core tests + 5 jars) | 54 s |
| `./build.sh --offline` from clean trees (no network at all) | 53 s |
| `./build.sh`, incremental | 17–27 s |
| First Forge dev server run, including the 1.20.1 asset download | 51 s |

`tools/smoke-server.sh` boots a dedicated dev server on its port (Runs), checks that the mod logged
`Ore Radar initialising on <loader>`, types `stop` and expects a clean exit; a run takes 20–50 s.
Its log is `runs/<build>-<module>/smoke-server.log`.
