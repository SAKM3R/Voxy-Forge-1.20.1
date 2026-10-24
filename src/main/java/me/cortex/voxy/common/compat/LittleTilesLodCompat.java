package me.cortex.voxy.common.compat;

import me.cortex.voxy.common.Logger;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;

/**
 * Lets Voxy's LOD builder represent LittleTiles tile-blocks ({@code BETiles}) instead of rendering them as empty.
 *
 * <p>A LittleTiles block has no baked model, so Voxy's model bakery produces nothing for it and the block is
 * invisible at LOD range. Mirroring how the LittleTiles&times;Distant-Horizons compat works, we extract a single
 * representative vanilla {@link BlockState} from the tile block entity (the tile occupying the most volume) and let
 * Voxy ingest/bake <em>that</em> &mdash; so distant LittleTiles structures show up as their dominant block.</p>
 *
 * <p>Everything here is reflective and self-disabling: Voxy gains no compile dependency on LittleTiles and loads
 * normally when LittleTiles is absent ({@link #PRESENT} is false and {@link #representativeState} returns null).</p>
 */
public final class LittleTilesLodCompat {

    private static final Class<?> BE_TILES;
    private static final Method ALL_TILES;     // BETiles.allTiles() -> Iterable<Pair<IParentCollection, LittleTile>>
    private static final Field PAIR_VALUE;     // Pair.value -> LittleTile
    private static final Method TILE_VOLUME;   // LittleTile.getVolume() -> int
    private static final Method TILE_STATE;    // LittleElement.getState() -> BlockState
    public static final boolean PRESENT;

    static {
        Class<?> beTiles = null;
        Method allTiles = null, volume = null, state = null;
        Field pairValue = null;
        try {
            beTiles = Class.forName("team.creative.littletiles.common.block.entity.BETiles");
            allTiles = beTiles.getMethod("allTiles");
            final Class<?> tile = Class.forName("team.creative.littletiles.common.block.little.tile.LittleTile");
            volume = tile.getMethod("getVolume");
            state = tile.getMethod("getState"); // inherited from LittleElement, public
            final Class<?> pair = Class.forName("team.creative.creativecore.common.util.type.list.Pair");
            pairValue = pair.getField("value");
        } catch (final Throwable t) {
            beTiles = null;
        }
        BE_TILES = beTiles;
        ALL_TILES = allTiles;
        PAIR_VALUE = pairValue;
        TILE_VOLUME = volume;
        TILE_STATE = state;
        PRESENT = beTiles != null;
        if (PRESENT)
            Logger.info("[Voxy] LittleTiles LOD compatibility active - tile-blocks will render at distance.");
    }

    /** @return true if {@code be} is a LittleTiles tile block entity. */
    public static boolean isTileEntity(final BlockEntity be) {
        return PRESENT && be != null && BE_TILES.isInstance(be);
    }

    /**
     * @return the vanilla {@link BlockState} of the largest-volume tile in {@code be}, or {@code null} if it isn't a
     *         (non-empty) LittleTiles block entity or extraction fails. Mirrors LittleTiles' own dominant-tile pick
     *         used by {@code BlockTile.getMapColor}.
     */
    public static BlockState representativeState(final BlockEntity be) {
        if (!isTileEntity(be))
            return null;
        try {
            final Object iterable = ALL_TILES.invoke(be);
            if (!(iterable instanceof Iterable<?> tiles))
                return null;
            BlockState best = null;
            int biggest = -1;
            for (final Iterator<?> it = tiles.iterator(); it.hasNext(); ) {
                final Object pair = it.next();
                final Object tile = PAIR_VALUE.get(pair);
                if (tile == null)
                    continue;
                final int vol = (int) TILE_VOLUME.invoke(tile);
                if (vol > biggest) {
                    final Object s = TILE_STATE.invoke(tile);
                    if (s instanceof BlockState bs) {
                        biggest = vol;
                        best = bs;
                    }
                }
            }
            return best;
        } catch (final Throwable t) {
            return null;
        }
    }

    private LittleTilesLodCompat() {}
}
