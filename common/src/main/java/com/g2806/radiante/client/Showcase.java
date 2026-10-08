package com.g2806.radiante.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.entity.EntityTypeTest;

/**
 * {@code /radiante showcase}: builds a gallery of small rooms, each exercising one part of the renderer (glass, light
 * sources, water, foliage, entities, text...), so a new Minecraft version or a renderer change can be checked by
 * walking one corridor instead of hunting for each case in a world. Singleplayer only: the rooms are built by running
 * ordinary commands on the integrated server.
 *
 * <p>The gallery runs east from where the player stands: a walkway with seven rooms on each side. Every room is
 * 9 x 9 with a sign at its entrance saying what to look at.
 */
public final class Showcase {

    private static final String TAG = "radiante_showcase";
    private static final String ORIGIN_TAG = "radiante_showcase_origin";
    private static final int ROOMS_PER_SIDE = 7;
    private static final int PITCH = 11;
    private static final int FIRST = 4;

    private static final String FLOOR = "smooth_stone";
    private static final String WALK = "polished_blackstone";
    private static final String TRIM = "quartz_block";
    private static final String WALL = "light_gray_concrete";

    private Showcase() {
    }

    /** Handles a {@code radiante ...} command typed by the player; true when it was one of ours. */
    public static boolean handle(String command) {
        String[] parts = command.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length == 0 || !parts[0].equals("radiante")) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (parts.length >= 2 && parts[1].equals("biome")) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            if (server == null || minecraft.player == null) {
                say(minecraft, "message.radiante.showcase.singleplayer");
                return true;
            }
            BiomeTour.handle(minecraft, server, java.util.Arrays.copyOfRange(parts, 2, parts.length));
            return true;
        }
        if (parts.length < 2 || !parts[1].equals("showcase")) {
            say(minecraft, "message.radiante.showcase.usage");
            return true;
        }
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            say(minecraft, "message.radiante.showcase.singleplayer");
            return true;
        }
        String action = parts.length > 2 ? parts[2] : "build";
        BlockPos playerPos = minecraft.player.blockPosition();
        var dimension = minecraft.player.level().dimension();
        if (action.equals("blocks")) {
            var categories = BlockGallery.collect(minecraft);
            server.execute(() -> {
                ServerLevel level = server.getLevel(dimension);
                if (level != null) {
                    run(server, level, BlockGallery.build(level, playerPos, categories));
                    minecraft.execute(() -> say(minecraft, "message.radiante.showcase.blocks"));
                }
            });
            return true;
        }
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level != null) {
                String reply = perform(server, level, action, playerPos);
                if (reply != null) {
                    minecraft.execute(() -> say(minecraft, reply));
                }
            }
        });
        return true;
    }

    /** Runs on the server thread; returns the message to show the player, if any. */
    private static String perform(MinecraftServer server, ServerLevel level, String action, BlockPos playerPos) {
        if (action.equals("build")) {
            run(server, level, new Builder(playerPos).build());
            return "message.radiante.showcase.built";
        }
        BlockPos origin = findOrigin(level, playerPos);
        if (origin == null) {
            return "message.radiante.showcase.none";
        }
        if (action.equals("clear")) {
            run(server, level, new Builder(origin).clear());
            return "message.radiante.showcase.cleared";
        }
        // A room number: 1-7 on the north side, 8-14 on the south side.
        int room;
        try {
            room = Integer.parseInt(action);
        } catch (NumberFormatException e) {
            room = 0;
        }
        if (room < 1 || room > ROOMS_PER_SIDE * 2) {
            return "message.radiante.showcase.usage";
        }
        int index = (room - 1) % ROOMS_PER_SIDE;
        boolean south = room > ROOMS_PER_SIDE;
        run(server, level, List.of(String.format(Locale.ROOT, "tp @p %d %d %d %d 0",
            origin.getX() + FIRST + index * PITCH + 4, origin.getY(), origin.getZ(), south ? 0 : 180)));
        return null;
    }

    /**
     * The gallery nearest to the player. Its start is remembered by a marker entity in the world, so a gallery built
     * in an earlier session can still be cleared or walked by room number.
     */
    private static BlockPos findOrigin(ServerLevel level, BlockPos near) {
        BlockPos best = null;
        for (Marker marker : level.getEntities(EntityTypeTest.forClass(Marker.class),
            entity -> entity.entityTags().contains(ORIGIN_TAG))) {
            BlockPos pos = marker.blockPosition();
            if (best == null || pos.distSqr(near) < best.distSqr(near)) {
                best = pos;
            }
        }
        return best;
    }

    private static void say(Minecraft minecraft, String key) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.translatable(key));
        }
    }

    private static void run(MinecraftServer server, ServerLevel level, List<String> commands) {
        CommandSourceStack source = server.createCommandSourceStack().withLevel(level);
        // The output of hundreds of fills would bury the chat; a development run keeps it for checking.
        if (System.getenv("RADIANTE_DEV_SCRIPT") == null) {
            source = source.withSuppressedOutput();
        }
        for (String command : commands) {
            server.getCommands().performPrefixedCommand(source, command);
        }
    }

    /** Collects the commands of the gallery; coordinates are absolute, from the player's position at build time. */
    private static final class Builder {

        private final int ox;
        private final int oy;
        private final int oz;
        private final List<String> out = new ArrayList<>();
        // The room being built: its west edge, and which side of the walkway it is on.
        private int bx;
        private boolean south;

        Builder(BlockPos origin) {
            this.ox = origin.getX();
            this.oy = origin.getY();
            this.oz = origin.getZ();
        }

        private int length() {
            return FIRST + ROOMS_PER_SIDE * PITCH;
        }

        List<String> clear() {
            this.out.add("kill @e[tag=" + TAG + "]");
            cmd("kill @e[tag=%s,x=%d,y=%d,z=%d,distance=..2]", ORIGIN_TAG, this.ox, this.oy, this.oz);
            for (int x = this.ox - 2; x <= this.ox + length() + 1; x += 8) {
                int x2 = Math.min(x + 7, this.ox + length() + 1);
                cmd("fill %d %d %d %d %d %d air", x, this.oy - 4, this.oz - 14, x2, this.oy + 9, this.oz + 14);
            }
            return this.out;
        }

        List<String> build() {
            clear();
            cmd("summon marker %d.5 %d %d.5 {Tags:[\"%s\"]}", this.ox, this.oy, this.oz, ORIGIN_TAG);
            // Ground and walkway.
            for (int x = this.ox - 2; x <= this.ox + length() + 1; x += 16) {
                int x2 = Math.min(x + 15, this.ox + length() + 1);
                cmd("fill %d %d %d %d %d %d %s", x, this.oy - 1, this.oz - 14, x2, this.oy - 1, this.oz + 14, FLOOR);
                cmd("fill %d %d %d %d %d %d %s", x, this.oy - 1, this.oz - 3, x2, this.oy - 1, this.oz + 3, WALK);
                cmd("fill %d %d %d %d %d %d %s", x, this.oy - 1, this.oz - 3, x2, this.oy - 1, this.oz - 3, TRIM);
                cmd("fill %d %d %d %d %d %d %s", x, this.oy - 1, this.oz + 3, x2, this.oy - 1, this.oz + 3, TRIM);
            }

            room(0, false, WALL, false, "Glass", "Clear, stained,", "tinted, panes", "See through both ways");
            glass();
            room(0, true, WALL, false, "Light blocks", "Each one lights", "the wall behind", "Check colours");
            lightBlocks();
            room(1, false, "black_concrete", true, "Dark room", "Coloured lights", "Shadows of pillars", "Noise settles?");
            darkRoom();
            room(1, true, WALL, false, "Water", "Depth colour,", "caustics, reflection", "Waterfall, kelp");
            water();
            room(2, false, "deepslate_bricks", false, "Lava and fire", "Glow on walls", "Fire, campfires", "Magma");
            lava();
            room(2, true, WALL, false, "Foliage", "Leaves, plants,", "cobweb, bars", "Cutout edges");
            foliage();
            room(3, false, WALL, false, "Translucent", "Slime, honey, ice", "Stained rainbow", "Nether portal");
            translucent();
            room(3, true, WALL, false, "Entities", "Mobs, armour,", "item frames", "Shadows + motion");
            entities();
            room(4, false, "black_concrete", true, "Entity lights", "Dropped glowing", "items, end crystal", "Glow item frame");
            entityLights();
            room(4, true, WALL, false, "Block entities", "Chest, bed, banner", "Signs, skull, bell", "End portal");
            blockEntities();
            room(5, false, WALL, false, "Shapes", "Stairs, slabs,", "fences, walls", "Sun shadows");
            shapes();
            room(5, true, WALL, false, "Materials", "Metals, gems,", "polished stone", "PBR packs show here");
            materials();
            room(6, false, WALL, false, "Smoke and rain", "Campfire smoke", "/weather rain", "Wet ground");
            particles();
            room(6, true, WALL, false, "Sky and fog", "Climb the tower", "/time set 0..24000", "Sun, moon, haze");
            vista();
            return this.out;
        }

        private void cmd(String format, Object... args) {
            this.out.add(String.format(Locale.ROOT, format, args));
        }

        // ---- room coordinates: lx 0-8 west to east, ly 0 is the first air level, lz 0 (walkway side) to 8 (back) ----

        private int x(int lx) {
            return this.bx + lx;
        }

        private int y(int ly) {
            return this.oy + ly;
        }

        private int z(int lz) {
            return this.south ? this.oz + 4 + lz : this.oz - 4 - lz;
        }

        private void set(int lx, int ly, int lz, String block) {
            cmd("setblock %d %d %d %s", x(lx), y(ly), z(lz), block);
        }

        private void fill(int lx1, int ly1, int lz1, int lx2, int ly2, int lz2, String block) {
            cmd("fill %d %d %d %d %d %d %s", x(lx1), y(ly1), z(lz1), x(lx2), y(ly2), z(lz2), block);
        }

        private void summon(String entity, double lx, double ly, double lz, String nbt) {
            double wz = this.south ? this.oz + 4 + lz + 0.5 : this.oz - 4 - lz + 0.5;
            String tagged = nbt.isEmpty() ? "{Tags:[\"" + TAG + "\"]}"
                : "{Tags:[\"" + TAG + "\"]," + nbt.substring(1);
            cmd("summon %s %.2f %.2f %.2f %s", entity, this.bx + lx + 0.5, (double) this.oy + ly, wz, tagged);
        }

        /** Which way a block in this room faces to look at the walkway. */
        private String front() {
            return this.south ? "north" : "south";
        }

        /** The shell of a room: floor, three walls, an optional roof, a trim line and its sign on the walkway. */
        private void room(int index, boolean southSide, String wall, boolean roofed, String title, String... lines) {
            this.bx = this.ox + FIRST + index * PITCH;
            this.south = southSide;
            fill(-1, 0, 9, 9, 4, 9, wall);
            fill(-1, 0, 0, -1, 4, 9, wall);
            fill(9, 0, 0, 9, 4, 9, wall);
            fill(-1, 5, 0, 9, 5, 9, TRIM);
            if (roofed) {
                fill(0, 5, 0, 8, 5, 8, wall);
                // A front wall with a doorway keeps the room dark.
                fill(-1, 0, 0, 9, 4, 0, wall);
                fill(3, 0, 0, 5, 2, 0, "air");
                fill(0, -1, 0, 8, -1, 8, "polished_deepslate");
            } else {
                fill(0, 5, 0, 8, 5, 8, "air");
            }
            int signZ = this.south ? this.oz + 2 : this.oz - 2;
            cmd("setblock %d %d %d %s", x(4), this.oy, signZ, "quartz_pillar");
            cmd("setblock %d %d %d oak_sign[rotation=%d]{front_text:{messages:[\"%s\",\"%s\",\"%s\",\"%s\"]}}",
                x(4), this.oy + 1, signZ, this.south ? 8 : 0, (index + 1 + (southSide ? ROOMS_PER_SIDE : 0)) + ". " + title,
                lines[0], lines[1], lines[2]);
        }

        private void glass() {
            String[] colours = {"white", "red", "orange", "yellow", "lime", "cyan", "blue", "purple", "black"};
            // Back row: a coloured wall with lights, seen through each kind of glass in front of it.
            for (int i = 0; i < 9; i++) {
                set(i, 0, 8, colours[i] + "_concrete");
                set(i, 1, 8, colours[i] + "_concrete");
                set(i, 2, 8, "sea_lantern");
            }
            fill(0, 0, 6, 2, 2, 6, "glass");
            for (int i = 3; i < 6; i++) {
                fill(i, 0, 6, i, 2, 6, colours[i - 2] + "_stained_glass");
            }
            fill(6, 0, 6, 8, 2, 6, "tinted_glass");
            fill(0, 0, 3, 3, 2, 3, "glass_pane");
            fill(5, 0, 3, 8, 2, 3, "light_blue_stained_glass_pane");
            // A glass box to look into and out of.
            fill(3, 0, 1, 5, 2, 1, "glass");
            set(4, 0, 1, "air");
        }

        private void lightBlocks() {
            String[] lights = {"torch", "lantern", "soul_lantern", "glowstone", "sea_lantern", "shroomlight",
                "redstone_lamp[lit=true]", "ochre_froglight", "verdant_froglight", "pearlescent_froglight",
                "end_rod", "jack_o_lantern[facing=" + front() + "]", "magma_block", "crying_obsidian",
                "copper_bulb[lit=true]", "soul_torch", "candle[candles=4,lit=true]", "amethyst_cluster"};
            for (int i = 0; i < lights.length; i++) {
                int lx = i % 9;
                int lz = 3 + (i / 9) * 3;
                set(lx, 0, lz, "polished_andesite");
                set(lx, 1, lz, lights[i]);
            }
            fill(0, 0, 8, 8, 3, 8, "white_concrete");
        }

        private void darkRoom() {
            set(1, 0, 7, "ochre_froglight");
            set(7, 0, 7, "verdant_froglight");
            set(4, 3, 4, "pearlescent_froglight");
            set(1, 0, 2, "redstone_lamp[lit=true]");
            set(7, 2, 2, "soul_lantern[hanging=false]");
            for (int[] p : new int[][] {{3, 5}, {5, 5}, {3, 3}, {5, 3}}) {
                fill(p[0], 0, p[1], p[0], 2, p[1], "quartz_pillar");
            }
            fill(0, 0, 8, 8, 0, 8, "smooth_quartz_slab");
        }

        private void water() {
            fill(0, -4, 1, 8, -1, 8, "prismarine_bricks");
            fill(1, -3, 2, 7, -1, 7, "water");
            set(2, -3, 3, "sea_pickle[pickles=4,waterlogged=true]");
            set(6, -3, 6, "sea_lantern");
            fill(3, -3, 5, 3, -2, 5, "kelp_plant");
            set(3, -1, 5, "kelp");
            set(5, -3, 3, "seagrass");
            set(5, 0, 5, "lily_pad");
            // A waterfall down the back wall.
            fill(3, 0, 8, 5, 3, 8, "stone_bricks");
            set(4, 4, 8, "water");
            fill(0, -1, 0, 8, -1, 0, "smooth_stone");
        }

        private void lava() {
            fill(1, -2, 5, 4, -1, 8, "deepslate_bricks");
            fill(2, -1, 6, 3, -1, 7, "lava");
            fill(6, -1, 5, 7, -1, 6, "netherrack");
            fill(6, 0, 5, 7, 0, 6, "fire");
            set(2, 0, 2, "campfire");
            set(6, 0, 2, "soul_campfire");
            fill(4, -1, 2, 4, -1, 3, "magma_block");
            set(7, -1, 8, "soul_sand");
            set(7, 0, 8, "soul_fire");
        }

        private void foliage() {
            fill(0, -1, 0, 8, -1, 8, "grass_block");
            String[] leaves = {"oak", "birch", "spruce", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "azalea"};
            for (int i = 0; i < 9; i++) {
                fill(i, 0, 8, i, 1, 8, leaves[i] + "_leaves[persistent=true]");
            }
            String[] plants = {"short_grass", "fern", "dandelion", "poppy", "blue_orchid", "allium", "oxeye_daisy",
                "cornflower", "dead_bush"};
            for (int i = 0; i < 9; i++) {
                set(i, 0, 6, plants[i]);
            }
            set(1, 0, 4, "tall_grass[half=lower]");
            set(1, 1, 4, "tall_grass[half=upper]");
            set(3, 0, 4, "sunflower[half=lower]");
            set(3, 1, 4, "sunflower[half=upper]");
            fill(5, 0, 4, 5, 2, 4, "bamboo");
            set(7, 0, 4, "sweet_berry_bush[age=3]");
            fill(0, 0, 2, 2, 1, 2, "cobweb");
            fill(4, 0, 2, 5, 1, 2, "iron_bars");
            fill(7, 0, 2, 8, 1, 2, "oak_fence");
            fill(0, 2, 8, 8, 2, 8, "oak_log");
            fill(0, 2, 7, 8, 2, 7, "vine[south=true]");
        }

        private void translucent() {
            set(1, 0, 6, "slime_block");
            set(3, 0, 6, "honey_block");
            set(5, 0, 6, "ice");
            set(7, 0, 6, "packed_ice");
            set(5, 1, 6, "blue_ice");
            String[] rainbow = {"red", "orange", "yellow", "lime", "light_blue", "blue", "purple"};
            for (int i = 0; i < rainbow.length; i++) {
                fill(1 + i, 0, 3, 1 + i, 2, 3, rainbow[i] + "_stained_glass");
            }
            // A lit nether portal at the back.
            fill(2, 0, 8, 6, 4, 8, "obsidian");
            fill(3, 1, 8, 5, 3, 8, "nether_portal[axis=x]");
        }

        private void entities() {
            fill(0, -1, 0, 8, -1, 8, "grass_block");
            String still = "{NoAI:1b,PersistenceRequired:1b,Silent:1b}";
            summon("cow", 1, 0, 6, still);
            summon("sheep", 3, 0, 6, still);
            summon("villager", 5, 0, 6, still);
            summon("iron_golem", 7, 0, 6, still);
            summon("creeper", 1, 0, 3, "{NoAI:1b,PersistenceRequired:1b,Silent:1b,powered:1b}");
            summon("husk", 3, 0, 3, still);
            summon("enderman", 5, 0, 3, still);
            summon("armor_stand", 7, 0, 3, "{ShowArms:1b,equipment:{head:{id:\"minecraft:diamond_helmet\"},"
                + "chest:{id:\"minecraft:golden_chestplate\"},legs:{id:\"minecraft:iron_leggings\"},"
                + "feet:{id:\"minecraft:netherite_boots\"},mainhand:{id:\"minecraft:diamond_sword\"}}}");
            summon("allay", 4, 2, 5, still);
        }

        private void entityLights() {
            String lasting = "Age:-32768,PickupDelay:32767";
            summon("item", 2, 0, 6, "{" + lasting + ",Item:{id:\"minecraft:glowstone\",count:1}}");
            summon("item", 4, 0, 6, "{" + lasting + ",Item:{id:\"minecraft:torch\",count:1}}");
            summon("item", 6, 0, 6, "{" + lasting + ",Item:{id:\"minecraft:sea_lantern\",count:1}}");
            summon("item", 2, 0, 3, "{" + lasting + ",Item:{id:\"minecraft:lava_bucket\",count:1}}");
            summon("item", 6, 0, 3, "{" + lasting + ",Item:{id:\"minecraft:redstone_torch\",count:1}}");
            set(4, 0, 4, "bedrock");
            summon("end_crystal", 4, 1, 4, "{ShowBottom:0b}");
            fill(0, 0, 8, 8, 3, 8, "smooth_quartz");
            cmd("summon glow_item_frame %d %d %d {Tags:[\"%s\"],Facing:%d,Item:{id:\"minecraft:shroomlight\",count:1}}",
                x(1), y(1), z(7), TAG, this.south ? 2 : 3);
            cmd("summon item_frame %d %d %d {Tags:[\"%s\"],Facing:%d,Item:{id:\"minecraft:glowstone\",count:1}}",
                x(7), y(1), z(7), TAG, this.south ? 2 : 3);
        }

        private void blockEntities() {
            String f = front();
            set(0, 0, 7, "chest[facing=" + f + "]");
            // A bed lying along the west wall, its head towards the back.
            set(0, 0, 5, "red_bed[part=head,facing=" + (this.south ? "south" : "north") + "]");
            set(0, 0, 4, "red_bed[part=foot,facing=" + (this.south ? "south" : "north") + "]");
            set(1, 0, 7, "ender_chest[facing=" + f + "]");
            set(2, 0, 7, "shulker_box");
            set(3, 0, 7, "enchanting_table");
            set(4, 0, 7, "lectern[facing=" + f + "]");
            set(5, 0, 7, "bell[attachment=floor,facing=" + f + "]");
            set(6, 0, 7, "skeleton_skull[rotation=" + (this.south ? 8 : 0) + "]");
            set(7, 0, 7, "decorated_pot");
            set(8, 0, 7, "red_banner[rotation=" + (this.south ? 8 : 0) + "]");
            cmd("setblock %d %d %d oak_sign[rotation=%d]{front_text:{has_glowing_text:1b,color:\"yellow\","
                + "messages:[\"Glowing text\",\"0123456789\",\"AaBbCc\",\"\"]}}", x(1), y(0), z(4), this.south ? 8 : 0);
            cmd("setblock %d %d %d birch_sign[rotation=%d]{front_text:{messages:[\"Plain text\",\"0123456789\","
                + "\"AaBbCc\",\"\"]}}", x(3), y(0), z(4), this.south ? 8 : 0);
            // End portal and gateway blocks, framed.
            fill(5, -1, 3, 7, -1, 5, "end_stone_bricks");
            set(6, -1, 4, "end_portal");
            set(8, 1, 3, "end_gateway");
            set(8, 0, 3, "end_stone_bricks");
            // A beacon with its beam.
            fill(0, -2, 0, 2, -2, 2, "iron_block");
            set(1, -1, 1, "beacon");
        }

        private void shapes() {
            String f = front();
            for (int i = 0; i < 5; i++) {
                fill(i, 0, 8 - i, i, i, 8 - i, "stone_bricks");
                set(i, i + 1, 8 - i, "stone_brick_stairs[facing=east]");
            }
            fill(6, 0, 7, 8, 0, 7, "stone_brick_slab");
            fill(6, 1, 8, 8, 1, 8, "stone_brick_slab[type=top]");
            fill(6, 0, 4, 8, 0, 4, "cobblestone_wall");
            fill(6, 0, 2, 8, 0, 2, "oak_fence");
            set(7, 1, 2, "oak_fence_gate[facing=" + f + "]");
            set(1, 0, 2, "oak_trapdoor[half=top]");
            set(2, 0, 2, "anvil");
            set(3, 0, 2, "hopper");
            set(4, 0, 2, "lightning_rod");
            fill(0, 0, 5, 0, 3, 5, "iron_chain");
            set(2, 0, 5, "scaffolding");
            set(4, 0, 5, "pointed_dripstone");
        }

        private void materials() {
            String[] blocks = {"iron_block", "gold_block", "copper_block", "diamond_block", "emerald_block",
                "netherite_block", "lapis_block", "redstone_block", "amethyst_block", "polished_granite",
                "polished_diorite", "polished_andesite", "polished_deepslate", "smooth_basalt", "obsidian",
                "quartz_block", "bricks", "oak_planks", "sandstone", "terracotta", "white_wool", "moss_block",
                "snow_block", "mud_bricks", "exposed_copper", "weathered_copper", "oxidized_copper"};
            for (int i = 0; i < blocks.length; i++) {
                set(i % 9, i / 9, 8, blocks[i]);
            }
            // Polished floor strips, for reflections with a PBR pack.
            fill(0, -1, 2, 8, -1, 3, "polished_blackstone");
            fill(0, -1, 5, 8, -1, 6, "smooth_quartz");
            set(4, 0, 5, "sea_lantern");
        }

        private void particles() {
            fill(0, -1, 0, 8, -1, 8, "stone");
            for (int i = 1; i < 8; i += 2) {
                set(i, -1, 6, "hay_block");
                set(i, 0, 6, "campfire");
            }
            set(2, 0, 2, "soul_campfire[signal_fire=true]");
            set(6, 0, 2, "torch");
            set(4, 0, 3, "cauldron");
        }

        private void vista() {
            // A tower with a ladder to look out over the gallery and the horizon.
            fill(3, 0, 3, 5, 7, 5, "stone_bricks");
            fill(4, 0, 4, 4, 8, 4, "air");
            fill(4, 0, 4, 4, 7, 4, "ladder[facing=" + front() + "]");
            fill(4, 0, 3, 4, 1, 3, "air");
            fill(4, 0, 5, 4, 7, 5, "stone_bricks");
            fill(2, 7, 2, 6, 7, 6, "smooth_stone_slab[type=top]");
            set(4, 7, 4, "air");
            fill(2, 8, 2, 6, 8, 6, "air");
            for (int[] p : new int[][] {{2, 2}, {6, 2}, {2, 6}, {6, 6}}) {
                set(p[0], 8, p[1], "lantern");
            }
        }
    }
}
