package app.grapheneos.camera.capturer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded public Camera2 residual shading maps, separate from immutable sensor samples. */
public final class RawShadingData {
    public static final int MAGIC = 0x3143534c;
    public static final int MAX_BYTES = 24 + 8 * (24 + 64 * 64 * 4 * 4);
    private static final int[][] CHANNELS = {{0, 1, 2, 3}, {1, 0, 3, 2}, {1, 3, 0, 2}, {3, 1, 2, 0}};

    public static final class Map {
        final int columns, rows, left, top, right, bottom;
        final float[] gains;
        public Map(int columns, int rows, int left, int top, int right, int bottom, float[] rggbGains) {
            if (columns < 1 || columns > 64 || rows < 1 || rows > 64 || rggbGains == null ||
                    rggbGains.length != columns * rows * 4) throw new IllegalArgumentException("Invalid shading grid");
            for (float gain : rggbGains) if (!Float.isFinite(gain) || gain < 1 || gain > 32)
                throw new IllegalArgumentException("Invalid shading gain");
            this.columns = columns; this.rows = rows;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            this.gains = rggbGains.clone();
        }
    }

    /** A missing optional map disables correction for the whole burst. */
    public static ByteBuffer encode(Map[] maps, int width, int height, int cfa) {
        if (maps == null || maps.length < 2 || maps.length > 8 || width < 8 || height < 8 ||
                (long) width * height > 16_000_000 || cfa < 0 || cfa > 3)
            throw new IllegalArgumentException("Invalid shading capture");
        for (Map map : maps) if (map == null) return null;
        int bytes = 24;
        for (Map map : maps) {
            if (map.left < 0 || map.top < 0 || map.left >= width || map.top >= height || map.right > width || map.bottom > height ||
                    map.right <= map.left + 1 || map.bottom <= map.top + 1)
                throw new IllegalArgumentException("Invalid shading region");
            bytes = Math.addExact(bytes, 24 + map.gains.length * 4);
        }
        if (bytes > MAX_BYTES) throw new IllegalArgumentException("Shading input too large");
        ByteBuffer output = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC).putInt(1).putInt(maps.length).putInt(width).putInt(height).putInt(cfa);
        for (Map map : maps) {
            output.putInt(map.columns).putInt(map.rows).putInt(map.left).putInt(map.top).putInt(map.right).putInt(map.bottom);
            for (int cell = 0; cell < map.gains.length; cell += 4)
                for (int site = 0; site < 4; site++) output.putFloat(map.gains[cell + CHANNELS[cfa][site]]);
        }
        output.flip();
        return output;
    }
}
