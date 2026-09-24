package com.g2806.radiante.client.render;

import net.minecraft.gizmos.SimpleGizmoCollector;

/**
 * Implemented on {@code LevelRenderer} by a mixin to reach the gizmo collector that F3+B hitboxes and F3+G chunk
 * borders (and anything else that calls {@code Gizmos.line}/{@code .cuboid}/etc) land in. Vanilla only drains it
 * from inside {@code LevelRenderer.render}, which the ray tracer replaces entirely, so nothing ever emptied it;
 * {@link com.g2806.radiante.client.render.EntityManager} drains it the same way instead.
 */
public interface LevelRendererGizmoAccess {

    SimpleGizmoCollector radiante$renderThreadGizmos();

    /** The cloud renderer, whose cells (loaded from clouds.png) the ray traced clouds are built from. */
    net.minecraft.client.renderer.CloudRenderer radiante$cloudRenderer();
}
