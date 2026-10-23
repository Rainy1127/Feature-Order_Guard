# Feature Order Guard

Stops the **"Feature order cycle found"** crash that happens when worldgen mods or datapacks disagree on the order
of placed features. Instead of crashing, the mod finds the cycle, removes the smallest possible set of ordering rules,
lets the world load and writes a report that names the features, biomes and mods involved.

* Minecraft 26.1.2 / NeoForge 26.1.2.107 / Java 25 / ModDevGradle 2.0.148
* Mod id: `feature_order_guard` - package `dev.featureorderguard` - version 1.0.0 - MIT
* One tiny mixin, no ticking, no blocks/items/packets. Zero cost when there is no cycle.

## The problem

Every biome lists its placed features per generation step, in order. Vanilla builds one global order per step from all
biomes of a dimension. If biome A lists `x, y` and biome B lists `y, x`, there is no global order, and vanilla throws
`IllegalStateException: Feature order cycle found` - usually from a worldgen mod combination, often only after a
datapack or biome modifier is added. The world does not load.

## What the mod does

1. Vanilla's `FeatureSorter.buildFeaturesPerStep` runs exactly as before. If it succeeds, nothing else happens.
2. If it fails, the mod rebuilds the same graph vanilla uses (node = placed feature inside one step, edge = "biome lists
   b right after a"), finds every cycle and removes the **cheapest** set of edges. The cost of an edge is the number of
   biomes that ask for it, so the fewest biomes are affected.
   * Knots of up to 16 features (practically all real cases) are solved **exactly** (minimum total cost).
   * Larger knots use a deterministic greedy pass plus a clean-up pass: every removed edge is still necessary.
3. The resolved order is handed back to vanilla as one already consistent source, so vanilla itself builds the final
   step data. Features that were never in conflict keep the exact order vanilla would have given them.
4. The result is written to the log and to `logs/feature_order_guard-report.txt`.

The result is **deterministic**: every choice is made by weight and then by registry id, never by load or hash order.
Same mods + same datapacks = same order = same report (the report has a fingerprint line for quick comparison).

What actually changes in the world: in the biomes listed next to a removed edge, those two features run in the opposite
order to the one that biome asked for. Every feature still generates in every biome that lists it.

## Config

`config/feature_order_guard-startup.toml` (a STARTUP config: it is read while the mod loads, before any world exists;
changes need a game restart; also in Mods -> Feature Order Guard -> Config)

| Key | Default | Meaning |
|---|---|---|
| `strategy` | `RESOLVE` | `RESOLVE`: fix and keep loading. `REPORT_ONLY`: write the report, then crash like vanilla. `CRASH`: do nothing at all (pure vanilla). Case-insensitive. |
| `write_report_file` | `true` | Write `logs/feature_order_guard-report.txt`. The log always gets a short summary. |

## Tag

| Tag | Default | Meaning |
|---|---|---|
| `#feature_order_guard:pinned` (placed features, `data/<ns>/tags/worldgen/placed_feature/pinned.json`) | empty | Ordering edges that touch a pinned feature are only removed when there is no other way to break the cycle (the report then shows a WARNING). |

The tag is read when the features are sorted. If tags are not bound at that moment, it is ignored and the report says so.

## Command

`/featureorderguard report` (permission level: game master) - a short summary in chat: strategy, number of cycles, the
removed edges and the path of the full report.

## The report

```
Cycle 1 in step "underground_ores" (2 features, minimum removal)
  Features:
    minecraft:ore_ancient_debris_large   [minecraft]
    minecraft:ore_debris_small   [minecraft]
  Conflicting orders (which biomes ask for what):
    minecraft:ore_ancient_debris_large -> minecraft:ore_debris_small
        asked by 1 biome: minecraft:plains
    minecraft:ore_debris_small -> minecraft:ore_ancient_debris_large
        asked by 1 biome: minecraft:forest
  Removed (1):
    minecraft:ore_debris_small -> minecraft:ore_ancient_debris_large   (biomes affected: 1)
        effect: in minecraft:forest, these two may now run in the opposite order.
  Biomes involved: minecraft:forest, minecraft:plains
  Mods involved: features from minecraft; biomes from minecraft
```

The file is rewritten at every server start (with "no cycles" when everything is fine), so it never shows stale data.

## What is deliberately left alone

* Worlds without a cycle: vanilla's code runs unchanged, the mod adds one method call.
* The **content** of biomes: no feature is added, removed or moved to another step. Only the relative order inside the
  cycle changes.
* Other worldgen problems (missing features, broken registries, "Feature placement" errors): not this mod's job.
* `CRASH` strategy: the mod is completely passive (not even the analysis runs).

## Performance

Normal start: one extra method frame around vanilla's sorter, nothing else. With a cycle: the graph is built once per
biome source (linear in the number of biome/feature entries) and the knots are solved in milliseconds (a test with
440 nodes and a cycle takes about 5 ms). Vanilla's own slow "find the guilty biomes" retry loop is skipped in that case.
No ticking, no state per chunk.

## Installing

Server only. In single player the integrated server is the server, so just put it in the mods folder. Players joining
a dedicated server do not need it (it registers no blocks, items or network payloads). Installing it on a client too
only adds the config screen.

## Build

```
./gradlew build          # jar in build/libs
./gradlew runClient
./gradlew runServer      # accept the EULA in run/eula.txt first
./gradlew test           # CycleResolver / report unit tests (pure Java)
```
`../../Users/mindo/Downloads/feature-order-guard-1.0.0/feature-order-guard/settings.gradle` uses the foojay resolver, so a Java 25 toolchain is downloaded automatically if missing.

## Layout

```
src/main/java/dev/featureorderguard/
  FeatureOrderGuard.java        common entrypoint: STARTUP config, command, server lifecycle listeners
  FeatureOrderGuardClient.java  client-only entrypoint: config screen
  GuardConfig.java / Strategy.java
  SorterBridge.java             Minecraft side: fast path, graph building from biome holders, hand-back to vanilla
  ReportStore.java              session reports, log output, report file
  ReportCommand.java            /featureorderguard report
  mixin/FeatureSorterMixin.java the only mixin (@WrapMethod on FeatureSorter.buildFeaturesPerStep)
  core/                         pure Java, unit tested:
    FeatureGraph, FeatureGraphBuilder, CycleResolver, Resolution, SourceReport, ReportFormatter
src/main/resources/feature_order_guard.mixins.json
src/main/resources/data/feature_order_guard/tags/worldgen/placed_feature/pinned.json
src/main/resources/assets/feature_order_guard/lang/{en_us,ru_ru}.json
src/main/templates/META-INF/neoforge.mods.toml
src/test/java/dev/featureorderguard/core/{CycleResolverTest,ReportFormatterTest}.java
testpack/foguard_cycle_test/   datapack that reproduces the crash (see TESTING.md)
testpack/foguard_pinned_test/  pins one of the two test features
art/                           icon_400.png (A), icon_400_name.png (B) and their SVG sources
```

This mod was written from scratch. No code of other mods (including Feature Recycler, which is All Rights Reserved)
was looked at or used.
