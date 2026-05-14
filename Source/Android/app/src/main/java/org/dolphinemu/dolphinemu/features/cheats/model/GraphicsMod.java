package org.dolphinemu.dolphinemu.features.cheats.model;

public final class GraphicsMod {
    public final long pointer;
    public final GraphicsModGroup group;

    public GraphicsMod(long pointer, GraphicsModGroup group) {
        this.pointer = pointer;
        this.group = group;
    }
}
