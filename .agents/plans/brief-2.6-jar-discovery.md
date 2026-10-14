GOAL: Answer docs/updater.md section 10 blocker 16 with evidence: how does a running plugin/mod reliably find the path of ITS OWN installed jar, on (1) Velocity 4.2.0 and (2) Fabric Loader 0.19.5 (dedicated server), and which directories does each loader scan for jars (so backup/staging files can live outside them)?
SCOPE: read-only research. Do not edit any file. Do not run Gradle or servers.
WHERE TO LOOK:
- Velocity: the pinned proxy jar /home/tna/QUICraft/velocity/build/velocity-proxy/velocity-4.2.0-30.jar (list classes with `unzip -l`; you may NOT decompile; read class/method names only) and the velocity-api jar + sources jar under ~/.gradle/caches/modules-2/files-2.1/com.velocitypowered/ (sources are readable). Key API: PluginContainer / PluginDescription.getSource() (Optional<Path>?).
- Fabric: fabric-loader 0.19.5 jar and its -sources jar under ~/.gradle/caches/modules-2/files-2.1/net.fabricmc/fabric-loader/ (unzip -p the sources). Key API: FabricLoader.getModContainer(id) -> ModContainer.getOrigin() (ModOrigin.Kind PATH / NESTED / UNKNOWN, getPaths()), getRootPaths(); mods dir config (fabric.addMods system property, getGameDir()/"mods"), and whether loader scans subdirectories of mods/ or files with other extensions (.jar only? hidden files?).
- Also read /home/tna/QUICraft/docs/updater.md section 5 ("Installed jar discovery and placement") for the open question.
QUESTIONS (answer each with file:line in the sources, or "not found"):
1. Velocity: exact API call giving our plugin's jar Path; can it be absent; is it absolute/real path.
2. Velocity: which directory is scanned (plugins/ only? recursive? which file names/extensions?) Is a file named `.quicraft-staging.jar` or `quicraft.jar.bak` in plugins/ loaded?
3. Fabric: exact API giving our mod's jar Path on a dedicated server; cases where it's NESTED or not a plain file (dev env, jar-in-jar, fabric.addMods, directory mods).
4. Fabric: which directories/files are scanned (mods/ recursive? hidden files? non-.jar files? "mods/<version>/"?). Is `mods/.quicraft-staging.jar` or `mods/quicraft.jar.bak` loaded?
5. Recommendation: safe backup/staging locations per loader (e.g. plugins/quicraft/update/ for Velocity, config/quicraft/update/ for Fabric) and why they're not scanned.
REPORT: answers 1-5, each with evidence lines, max 40 lines. Mark guesses as GUESS.
