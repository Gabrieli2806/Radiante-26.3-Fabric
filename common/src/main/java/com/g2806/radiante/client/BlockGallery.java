package com.g2806.radiante.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code /radiante showcase blocks}: every block of the game laid out on a floor, one per spot with air around it,
 * grouped as the creative inventory groups them (building, coloured, natural, functional, redstone) and the blocks
 * no tab lists last. For checking how each block looks under the renderer and a resource pack.
 *
 * <p>Rows run east from the player, a category after the other going south, each started by a sign. Blocks are put
 * down without updates, so plants stand on stone, sand does not fall and doors keep one half.
 */
final class BlockGallery {

    private static final int PER_ROW = 24;
    private static final int PITCH = 4;
    private static final int HEIGHT = 6;
    /** Put down as they are: no neighbour updates, no shape updates, no onPlace (falling, fluid ticks). */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

    // By id: the CreativeModeTabs constants are private in vanilla (only Fabric's access widener opens them).
    private static final List<ResourceKey<CreativeModeTab>> TABS = List.of(tab("building_blocks"),
        tab("colored_blocks"), tab("natural_blocks"), tab("functional_blocks"), tab("redstone_blocks"));

    private static ResourceKey<CreativeModeTab> tab(String name) {
        return ResourceKey.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB,
            net.minecraft.resources.Identifier.withDefaultNamespace(name));
    }

    private BlockGallery() {
    }

    /** The blocks by category, read on the client thread (the creative tabs are built from the client's registries). */
    static Map<String, List<Block>> collect(Minecraft minecraft) {
        Map<String, List<Block>> categories = new LinkedHashMap<>();
        Set<Block> listed = new LinkedHashSet<>();
        if (minecraft.level != null) {
            CreativeModeTabs.tryRebuildTabContents(minecraft.level.enabledFeatures(), true,
                minecraft.level.registryAccess());
        }
        for (ResourceKey<CreativeModeTab> key : TABS) {
            List<Block> blocks = new ArrayList<>();
            for (ItemStack stack : BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(key).getDisplayItems()) {
                if (stack.getItem() instanceof BlockItem item && placeable(item.getBlock()) && listed.add(item.getBlock())) {
                    blocks.add(item.getBlock());
                }
            }
            categories.put(key.identifier().getPath(), blocks);
        }
        List<Block> other = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (placeable(block) && !listed.contains(block)) {
                other.add(block);
            }
        }
        categories.put("other", other);
        return categories;
    }

    private static boolean placeable(Block block) {
        BlockState state = block.defaultBlockState();
        return !state.isAir() && !(block instanceof LiquidBlock);
    }

    /** Runs on the server thread. Returns the commands for the signs, which are easier to write as commands. */
    static List<String> build(ServerLevel level, BlockPos origin, Map<String, List<Block>> categories) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int depth = 0;
        for (List<Block> blocks : categories.values()) {
            depth += (Math.max(1, (blocks.size() + PER_ROW - 1) / PER_ROW) + 1) * PITCH;
        }
        int width = 4 + PER_ROW * PITCH;

        // Floor and clear air above it.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = ox - 2; x <= ox + width; x++) {
            for (int z = oz - 2; z <= oz + depth; z++) {
                level.setBlock(pos.set(x, oy - 1, z), Blocks.SMOOTH_STONE.defaultBlockState(), FLAGS);
                for (int y = oy; y < oy + HEIGHT; y++) {
                    level.setBlock(pos.set(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
                }
            }
        }

        List<String> signs = new ArrayList<>();
        int z = oz;
        for (Map.Entry<String, List<Block>> category : categories.entrySet()) {
            List<Block> blocks = category.getValue();
            String title = category.getKey().replace('_', ' ') + " (" + blocks.size() + ")";
            signs.add(String.format(Locale.ROOT,
                "setblock %d %d %d oak_sign[rotation=4]{front_text:{messages:[\"%s\",\"\",\"\",\"\"]}} replace",
                ox - 1, oy, z, title));
            for (int i = 0; i < blocks.size(); i++) {
                int x = ox + 2 + (i % PER_ROW) * PITCH;
                int rowZ = z + (i / PER_ROW) * PITCH;
                level.setBlock(pos.set(x, oy, rowZ), blocks.get(i).defaultBlockState(), FLAGS);
            }
            z += (Math.max(1, (blocks.size() + PER_ROW - 1) / PER_ROW) + 1) * PITCH;
        }
        return signs;
    }
}
