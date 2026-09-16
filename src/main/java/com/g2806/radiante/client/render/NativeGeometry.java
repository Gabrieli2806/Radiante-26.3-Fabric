package com.g2806.radiante.client.render;

/** Mirrors the enums the native renderer indexes geometry with (see native core/render/world.hpp). */
public final class NativeGeometry {

    public static final int VERTEX_FORMAT_PBR = 12;

    public static final int GEOMETRY_TYPE_SHADOW = 0;
    public static final int GEOMETRY_TYPE_WORLD_SOLID = 1;
    public static final int GEOMETRY_TYPE_WORLD_TRANSPARENT = 2;
    public static final int GEOMETRY_TYPE_WORLD_NO_REFLECT = 3;
    public static final int GEOMETRY_TYPE_WORLD_CLOUD = 4;
    public static final int GEOMETRY_TYPE_BOAT_WATER_MASK = 5;
    public static final int GEOMETRY_TYPE_END_PORTAL = 6;
    public static final int GEOMETRY_TYPE_END_GATEWAY = 7;

    public static final int COORDINATE_WORLD = 0;
    public static final int COORDINATE_CAMERA = 1;
    public static final int COORDINATE_CAMERA_SHIFT = 2;

    public static final int DRAW_MODE_QUADS = 7;

    private NativeGeometry() {
    }
}
