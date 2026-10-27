package me.cortex.voxy.smoketest;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scene test (-Dvoxy.smoketest.scene): a superflat world with a strip of deep water and identical towers of ice and
 * snow at 15, 130, 220 and 320 blocks. With render distance 5 the first tower is real chunks and the others are LODs,
 * so one screenshot compares the shader pack's look of the same blocks near and far, water included.
 */
public class SceneTest {
    private static final Logger LOG = LoggerFactory.getLogger("VoxySceneTest");
    static final boolean ENABLED = Boolean.getBoolean("voxy.smoketest.scene");
    private static final int Z0 = -24, Z1 = 24, FLOOR = 48, SEA = 62;
    private static final int[] TOWERS = {15, 130, 220, 320};
    private static int view = 0;

    static void tick(Minecraft mc, int t) {
        var server = mc.getSingleplayerServer();
        if (t == 20) {
            mc.player.getAbilities().flying = true;
            server.execute(SceneTest::build);
        }
        // Fly along the strip so every chunk of it is loaded once and taken in by Voxy
        if (t >= 120 && t < 220) {
            mc.player.setPos(-30 + (t - 120) * 4, 90, 0);
        }
        // Off to the side of the strip, so the near tower (real chunks, ~50 blocks) and the far ones (LODs) stand
        // next to each other in the frame with water in front of each
        if (t >= 220 && t < 1100) {
            mc.player.setPos(0, 95, -50);
            mc.player.setXRot(view == 1 ? 40 : 22);
            mc.player.setYRot(-49);
            mc.player.setYHeadRot(-49);
        }
        if (t == 900) SmokeTest.grab(mc, "scene");
        if (t == 920) view = 1;
        if (t == 960) SmokeTest.grab(mc, "scene-water");
        if (t == 980) {
            me.cortex.voxy.client.config.VoxyConfig.debugMode = 1;
            view = 0;
        }
        if (t == 1010) SmokeTest.grab(mc, "scene-notint");
        if (t == 1015) me.cortex.voxy.client.config.VoxyConfig.debugMode = 3;
        if (t == 1040) SmokeTest.grab(mc, "scene-whitetex");
        if (t == 1045) me.cortex.voxy.client.config.VoxyConfig.debugMode = 0;
        if (t == 1080) {
            LOG.info("VOXY_SMOKE_RESULT done");
            mc.stop();
        }
    }

    private static void build() {
        var level = Minecraft.getInstance().getSingleplayerServer().overworld();
        long start = System.nanoTime();
        level.setDayTime(6000);
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int x = -40; x <= 360; x++) {
            for (int z = Z0; z <= Z1; z++) {
                set(level, x, FLOOR, z, Blocks.SAND.defaultBlockState());
                for (int y = FLOOR + 1; y <= SEA; y++) set(level, x, y, z, water);
            }
            // banks of grass either side, for a grass colour reference too
            for (int z : new int[]{Z0 - 1, Z0 - 2, Z1 + 1, Z1 + 2}) {
                for (int y = FLOOR; y <= SEA; y++) set(level, x, y, z, Blocks.DIRT.defaultBlockState());
                set(level, x, SEA + 1, z, Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        BlockState[] columns = {Blocks.PACKED_ICE.defaultBlockState(), Blocks.BLUE_ICE.defaultBlockState(),
                Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.ICE.defaultBlockState()};
        for (int tx : TOWERS) {
            for (int c = 0; c < columns.length; c++) {
                for (int dx = 0; dx < 6; dx++) for (int dz = 0; dz < 6; dz++) {
                    int x = tx + dx, z = -14 + c * 7 + dz;
                    for (int y = FLOOR + 1; y <= SEA + 14; y++) set(level, x, y, z, columns[c]);
                    set(level, x, SEA + 15, z, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1));
                }
            }
        }
        LOG.info("VOXY_SMOKE scene built in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    private static void set(net.minecraft.server.level.ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, 2 | 16);
    }
}
