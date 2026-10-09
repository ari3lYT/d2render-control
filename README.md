# D2 Render Control

Fabric server mod for bounded render requests. It writes requests to `config/d2gate/render-queue` for a separate NAS worker; it does not run a renderer or shell command in Minecraft.

The recovered 1.21.11 source was compiled and its remapped class was byte identical to the active production JAR. It was migrated to Mojang mappings and ported to Minecraft 26.3. This 0.1.1 candidate also handles missing path names safely.

Run `./gradlew build` for tests and SpotBugs. A local Fabric 26.3 smoke test confirmed startup, `/render` help, status, and rejection of an oversized area. The candidate is staged only; production remains on 1.21.11. Queue integration with the NAS worker and a real player session remain to be verified before deployment.
