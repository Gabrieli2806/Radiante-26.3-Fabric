package com.g2806.radiante.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Passes every vertex through to another consumer; subclasses override what they change. The chained setters
 * return this wrapper, so a whole vertex goes through it, including the ones VertexConsumer builds from its
 * defaults (addVertex with every attribute at once calls these).
 */
class VertexConsumerWrapper implements VertexConsumer {

    private final VertexConsumer delegate;

    VertexConsumerWrapper(VertexConsumer delegate) {
        this.delegate = delegate;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        this.delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        this.delegate.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        this.delegate.setColor(color);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        this.delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        this.delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        this.delegate.setUv3(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        this.delegate.setNormal(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        this.delegate.setLineWidth(width);
        return this;
    }
}
