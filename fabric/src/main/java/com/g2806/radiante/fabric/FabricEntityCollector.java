package com.g2806.radiante.fabric;

import com.g2806.radiante.client.render.EntityCollector;
import com.g2806.radiante.client.render.PBRVertexWriter;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.ARGB;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** The collector plus the mesh overload Fabric's rendering API adds to the submission interface. */
public final class FabricEntityCollector extends EntityCollector {

    /**
     * Fabric replaces the storage behind a block model with a mesh of its own and submits it through this overload,
     * cancelling the vanilla one. Its default implementation forwards to the vanilla call with the mesh dropped,
     * which leaves nothing at all for models Fabric keeps entirely in the mesh - an item frame's frame is one of
     * them, and that is why it traced as empty air while the item inside it showed.
     */
    @Override
    public void submitBlockModel(PoseStack poseStack, Function<ChunkSectionLayer, RenderType> renderTypeFunction,
        boolean translucent, List<BlockStateModelPart> parts, @Nullable Mesh mesh, int[] tintLayers, int lightCoords,
        int overlayCoords, int outlineColor) {
        RenderType renderType = renderTypeFunction.apply(
            translucent ? ChunkSectionLayer.TRANSLUCENT : ChunkSectionLayer.CUTOUT);
        this.submitBlockModel(poseStack, renderType, parts, tintLayers, lightCoords, overlayCoords, outlineColor);

        // Only models Fabric keeps entirely in its mesh need this: those are the ones vanilla hands over empty, an
        // item frame among them. Where vanilla parts exist they have already been written above, and adding the mesh
        // on top of them would trace the same model twice.
        if (mesh == null || mesh.size() == 0 || !parts.isEmpty()) {
            return;
        }

        PBRVertexWriter buffer = this.writer(renderType);
        PoseStack.Pose pose = poseStack.last();
        mesh.forEach(quad -> putMeshQuad(quad, pose, tintLayers, lightCoords, overlayCoords, buffer));
    }

    /** One quad out of a Fabric mesh, written with the same values the vanilla quad path writes. */
    private static void putMeshQuad(QuadView quad, PoseStack.Pose pose, int[] tintLayers, int lightCoords,
        int overlayCoords, PBRVertexWriter buffer) {
        int tintIndex = quad.tintIndex();
        int tint = tintIndex >= 0 && tintIndex < tintLayers.length ? ARGB.opaque(tintLayers[tintIndex]) : -1;
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();

        for (int vertex = 0; vertex < 4; vertex++) {
            pose.pose().transformPosition(quad.copyPos(vertex, position), position);
            if (quad.hasNormal(vertex)) {
                normal.set(quad.normalX(vertex), quad.normalY(vertex), quad.normalZ(vertex));
            } else {
                normal.set(quad.faceNormal());
            }
            pose.transformNormal(normal, normal);

            int light = quad.lightmap(vertex);
            buffer.addVertex(position.x(), position.y(), position.z())
                .setColor(ARGB.multiply(quad.color(vertex), tint))
                .setUv(quad.u(vertex), quad.v(vertex))
                .setOverlay(overlayCoords)
                .setLight(light == 0 ? lightCoords : light)
                .setNormal(normal.x(), normal.y(), normal.z());
        }
    }
}
