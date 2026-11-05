# {C} Char's Game Logger (`clogger`)

Type `/clogger start`, play or let the server run, then `/clogger stop`. You get a plain text report about how the game ran:
what is wrong or slow comes first (biggest problem on top, with numbers and a suggested fix), details after.
Nothing runs until you start it.

## Commands
- `/clogger start [name]`, `/clogger stop`, `/clogger status`
- On your PC this is a client command: it works on any server, even one without the mod, and measures YOUR game.
  In singleplayer it measures client and the built-in server together.
- On a dedicated server (console or op level 2) the same command measures the server.
  `/cloggerserver ...` does the same everywhere (use it from inside a game that has the mod on the server).

## Files
`<game folder>/clogger/<date_time>[_name].txt` (the report) and `.json` (a few numbers, for comparing two runs).
While logging a `.partial.txt` is rewritten every minute so a crash still leaves data. If the game closes while logging, the report is finished by a shutdown hook.

## What it measures
- Server: TPS, tick time (avg/median/95%/99%/max), lag spikes with what the profiler caught inside them.
- Client: FPS, frame time percentiles, client tick time, lag spikes.
- A sampling profiler (about 1% of a core): stack samples of the server thread (only while a tick runs) and the render thread, grouped in a tree,
  every sample blamed on the deepest MOD in the call stack (class -> mod by reading the mod jars; Bukkit plugins too; mixin handlers by name).
  Method names are turned back from Minecraft's scrambled names (`tools/make_srg_table.py`).
- Entities by type, busiest chunks with coordinates, block entities by type, per dimension; entity and block entity code cost estimated from the samples.
- Memory, GC pauses, threads, per-thread CPU, process and system CPU, packet counts per type (not bytes).
- Events: joins, leaves, deaths, dimension changes, commands, chat, advancements, kicks, blocks broken, items picked up, spawns, explosions.
- Warnings and errors from the game log grouped by logger and mod, crash reports written during the run.
- System: OS, CPU, RAM, GPU and driver, Java and flags, Forge/Minecraft versions, every mod with version and jar, Bukkit/Arclight plugins, resource packs, shader packs, settings.

Chat text is written into the report (shortened to 100 characters); delete the file if that matters.
Nothing is sent anywhere.

## Limits (honest)
- Entity cost per TYPE is a guess from which class's code was running; shared base classes (Mob, LivingEntity) show as themselves.
- Packet counts, not bytes (bytes would need a mixin).
- The profiler samples about every 20-250 ms (it slows itself down to stay near 1% CPU), so very short spikes may have no sample.
- Optional on either side (`displayTest` ignores versions): friends do not need it on their PC for a server to be logged.

## Build and test
`gradlew build` (11 unit tests for the number crunching and the report text). `gradlew build -Psmoke` also compiles `src/smoke` (a self test used by
`tools/run_real_client.py`, which starts the real game in `run-real/`, logs 60 seconds and quits). Never part of the normal jar.
