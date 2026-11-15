package work.nekow.particledrawing.lighting;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper;

public final class TerrainVertexConsumer extends VertexConsumerWrapper {
    private final int[] data;
    private final int stride;
    private final BlockPos pos;
    private final Vec3 offset;
    private int vertex;

    public TerrainVertexConsumer(VertexConsumer consumer, int[] data, BlockPos pos, Vec3 offset) {
        super(consumer);
        this.data = data;
        this.stride = data.length / 4;
        this.pos = pos;
        this.offset = offset;
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v,
                          int overlay, int light, float nx, float ny, float nz) {
        int i = vertex++ * stride;
        long lit = DynamicLightManager.shadeTerrainVertex(color, light,
                pos.getX() + offset.x + Float.intBitsToFloat(data[i]),
                pos.getY() + offset.y + Float.intBitsToFloat(data[i + 1]),
                pos.getZ() + offset.z + Float.intBitsToFloat(data[i + 2]));
        parent.addVertex(x, y, z, (int) (lit >>> 32), u, v, overlay, (int) lit, nx, ny, nz);
    }
}
