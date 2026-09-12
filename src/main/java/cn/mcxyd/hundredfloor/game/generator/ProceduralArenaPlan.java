package cn.mcxyd.hundredfloor.game.generator;

import cn.mcxyd.hundredfloor.game.model.ArenaBounds;
import cn.mcxyd.hundredfloor.game.model.ArenaDefinition;
import cn.mcxyd.hundredfloor.game.model.ArenaPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Creates a deterministic vertical course without touching Bukkit. A generated plan can therefore be
 * tested and prepared off-thread before block writes enter their owning regions.
 */
public final class ProceduralArenaPlan {

    private static final int HALF_SIZE = 21;
    private static final int PLATFORM_RADIUS = 11;
    private static final int TOP_Y = 300;
    private static final int FLOOR_SPACING = 3;

    private final ArenaDefinition definition;
    private final List<BlockPlacement> placements;
    private final long seed;

    private ProceduralArenaPlan(ArenaDefinition definition, List<BlockPlacement> placements, long seed) {
        this.definition = definition;
        this.placements = List.copyOf(placements);
        this.seed = seed;
    }

    public static ProceduralArenaPlan create(String arenaName, String worldName, int floorCount, long seed) {
        if (arenaName == null || arenaName.isBlank()) {
            throw new IllegalArgumentException("竞技场名称不能为空");
        }
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("世界名称不能为空");
        }
        if (floorCount < 1 || floorCount > 100) {
            throw new IllegalArgumentException("楼层数量必须在 1-100 之间");
        }

        Random random = new Random(seed);
        Map<Long, BlockPlacement> blocks = new LinkedHashMap<>();
        List<ArenaPoint> gates = new ArrayList<>(floorCount);
        int x = 0;
        int z = 0;
        for (int floor = 0; floor < floorCount; floor++) {
            int y = TOP_Y - floor * FLOOR_SPACING;
            if (floor > 0) {
                x = clamp(x + random.nextInt(3) - 1, -8, 8);
                z = clamp(z + random.nextInt(3) - 1, -8, 8);
            }
            gates.add(new ArenaPoint(worldName, x + 0.5, y + 1.05, z + 0.5, 0, 0));
            addFloor(blocks, x, y, z, floor, random);
        }

        int finishY = TOP_Y - floorCount * FLOOR_SPACING - 2;
        addFinish(blocks, x, finishY, z);
        addWaitingAndStart(blocks);
        ArenaBounds bounds = new ArenaBounds(worldName, -HALF_SIZE, finishY - 1, -HALF_SIZE,
                HALF_SIZE, TOP_Y + 5, HALF_SIZE);
        ArenaDefinition definition = new ArenaDefinition(arenaName,
                new ArenaPoint(worldName, 3.5, TOP_Y + 3.1, 0.5, 90, 0),
                new ArenaPoint(worldName, 0.5, TOP_Y + 3.1, 0.5, 0, 0),
                new ArenaPoint(worldName, x + 0.5, finishY + 1.1, z + 0.5, 0, 0),
                bounds, gates);
        return new ProceduralArenaPlan(definition, List.copyOf(blocks.values()), seed);
    }

    public ArenaDefinition definition() {
        return definition;
    }

    public List<BlockPlacement> placements() {
        return placements;
    }

    public long seed() {
        return seed;
    }

    private static void addFloor(Map<Long, BlockPlacement> blocks, int centerX, int y, int centerZ,
                                 int floor, Random random) {
        String platform = switch (floor % 5) {
            case 0 -> "CYAN_STAINED_GLASS";
            case 1 -> "LIME_STAINED_GLASS";
            case 2 -> "ORANGE_STAINED_GLASS";
            case 3 -> "MAGENTA_STAINED_GLASS";
            default -> "BLUE_STAINED_GLASS";
        };
        for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
            for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    continue;
                }
                put(blocks, centerX + dx, y, centerZ + dz, platform);
            }
        }
        for (int side = -PLATFORM_RADIUS; side <= PLATFORM_RADIUS; side++) {
            put(blocks, centerX + side, y + 1, centerZ - PLATFORM_RADIUS, "BLACKSTONE");
            put(blocks, centerX + side, y + 1, centerZ + PLATFORM_RADIUS, "BLACKSTONE");
            put(blocks, centerX - PLATFORM_RADIUS, y + 1, centerZ + side, "BLACKSTONE");
            put(blocks, centerX + PLATFORM_RADIUS, y + 1, centerZ + side, "BLACKSTONE");
        }
        for (int dx = -2; dx <= 2; dx++) {
            put(blocks, centerX + dx, y, centerZ - 2, "GOLD_BLOCK");
            put(blocks, centerX + dx, y, centerZ + 2, "GOLD_BLOCK");
        }
        for (int dz = -1; dz <= 1; dz++) {
            put(blocks, centerX - 2, y, centerZ + dz, "GOLD_BLOCK");
            put(blocks, centerX + 2, y, centerZ + dz, "GOLD_BLOCK");
        }
        // A small side route changes its visual rhythm while leaving the required opening clear.
        int bridgeZ = random.nextBoolean() ? PLATFORM_RADIUS - 3 : -PLATFORM_RADIUS + 3;
        for (int dx = -4; dx <= 4; dx++) {
            put(blocks, centerX + dx, y + 1, centerZ + bridgeZ, "OAK_PLANKS");
            if (Math.abs(dx) % 3 == 0) {
                put(blocks, centerX + dx, y + 2, centerZ + bridgeZ, "LANTERN");
            }
        }
    }

    private static void addWaitingAndStart(Map<Long, BlockPlacement> blocks) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                    continue;
                }
                put(blocks, x, TOP_Y + 2, z, "SMOOTH_QUARTZ");
                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    put(blocks, x, TOP_Y + 3, z, "GLASS");
                }
            }
        }
    }

    private static void addFinish(Map<Long, BlockPlacement> blocks, int x, int y, int z) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                put(blocks, x + dx, y, z + dz, "EMERALD_BLOCK");
            }
        }
    }

    private static void put(Map<Long, BlockPlacement> blocks, int x, int y, int z, String material) {
        blocks.put(pack(x, y, z), new BlockPlacement(x, y, z, material));
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
