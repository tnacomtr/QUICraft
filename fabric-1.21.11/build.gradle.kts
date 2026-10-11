// SPDX-License-Identifier: GPL-3.0-or-later
//
// Fabric client + dedicated server for 1.21.11, the last obfuscated release: the shared source
// (fabric/, plus src/client/java for the calls that differ from 26.x) uses Mojang names, the jar is remapped to Intermediary. Java 21.
plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("quicraft.fabric-conventions")
}
