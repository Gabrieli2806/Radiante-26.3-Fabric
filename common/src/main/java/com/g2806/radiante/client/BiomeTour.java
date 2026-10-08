package com.g2806.radiante.client;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * {@code /radiante biome}: walks through every biome the current dimension can generate, one at a time, for checking
 * fog, water and sky colour per biome. Singleplayer only (the biome is found on the integrated server).
 *
 * <ul>
 * <li>{@code /radiante biome} or {@code /radiante biome next}: the next biome in the list, nearest place first.
 * <li>{@code /radiante biome back}: the previous one.
 * <li>{@code /radiante biome <name>}: a given biome, e.g. {@code swamp} or {@code minecraft:cherry_grove}.
 * <li>{@code /radiante biome list}: the biomes of this dimension, in the order the tour takes.
 * </ul>
 */
final class BiomeTour {

    /** How far from the player a biome is searched for, in blocks. */
    private static final int SEARCH_RADIUS = 6400;
    private static final int HORIZONTAL_STEP = 32;
    private static final int VERTICAL_STEP = 64;

    private static int index = -1;

    private BiomeTour() {
    }

    /** Runs on the client thread with the arguments after {@code biome}. */
    static void handle(Minecraft minecraft, MinecraftServer server, String[] args) {
        BlockPos playerPos = minecraft.player.blockPosition();
        var dimension = minecraft.player.level().dimension();
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                return;
            }
            List<ResourceKey<Biome>> biomes = biomes(level);
            if (biomes.isEmpty()) {
                return;
            }
            String action = args.length > 0 ? args[0] : "next";
            ResourceKey<Biome> target;
            switch (action) {
                case "list" -> {
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < biomes.size(); i++) {
                        names.append(i == 0 ? "" : ", ").append(i + 1).append(' ')
                            .append(biomes.get(i).identifier().getPath());
                    }
                    say(minecraft, Component.translatable("message.radiante.biome.list", biomes.size(),
                        names.toString()));
                    return;
                }
                case "next" -> {
                    index = (index + 1) % biomes.size();
                    target = biomes.get(index);
                }
                case "back" -> {
                    index = (index - 1 + biomes.size()) % biomes.size();
                    target = biomes.get(index);
                }
                default -> {
                    target = find(biomes, action);
                    if (target == null) {
                        say(minecraft, Component.translatable("message.radiante.biome.unknown", action));
                        return;
                    }
                    index = biomes.indexOf(target);
                }
            }
            ResourceKey<Biome> wanted = target;
            Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(holder -> holder.is(wanted), playerPos,
                SEARCH_RADIUS, HORIZONTAL_STEP, VERTICAL_STEP);
            String name = wanted.identifier().toString();
            if (found == null) {
                say(minecraft, Component.translatable("message.radiante.biome.not_found", name, index + 1,
                    biomes.size()));
                return;
            }
            BlockPos pos = found.getFirst();
            int y = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4)
                .getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX() & 15, pos.getZ() & 15) + 1;
            // Underground biomes (caves) are reached where they were found, not on the surface above them.
            if (pos.getY() < y - 8 && !level.canSeeSky(pos)) {
                y = pos.getY();
            }
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level)
                    .withSuppressedOutput(),
                String.format(Locale.ROOT, "tp @p %d %d %d", pos.getX(), y, pos.getZ()));
            say(minecraft, Component.translatable("message.radiante.biome.at", name, index + 1, biomes.size(),
                pos.getX(), y, pos.getZ()));
        });
    }

    private static List<ResourceKey<Biome>> biomes(ServerLevel level) {
        List<ResourceKey<Biome>> keys = new ArrayList<>();
        for (Holder<Biome> holder : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
            holder.unwrapKey().ifPresent(keys::add);
        }
        keys.sort(Comparator.comparing(key -> key.identifier().toString()));
        return keys;
    }

    private static ResourceKey<Biome> find(List<ResourceKey<Biome>> biomes, String name) {
        for (ResourceKey<Biome> key : biomes) {
            if (key.identifier().toString().equals(name) || key.identifier().getPath().equals(name)) {
                return key;
            }
        }
        return null;
    }

    private static void say(Minecraft minecraft, Component message) {
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(message);
            }
        });
    }
}
