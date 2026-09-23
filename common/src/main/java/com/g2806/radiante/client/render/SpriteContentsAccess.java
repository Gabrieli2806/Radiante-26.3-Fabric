package com.g2806.radiante.client.render;

import java.nio.ByteBuffer;

/** Implemented on {@code SpriteContents} by a mixin to reach the CPU-side mip images. */
public interface SpriteContentsAccess {

    ByteBuffer[] radiante$mipImages();

    int radiante$mipWidth(int level);
}
