package cn.spectra.gallium.glowoutline;

import com.mojang.blaze3d.buffers.Std140Builder;

public sealed interface ShaderParam {

    String name();
    void pack(Std140Builder builder);

    record Float(String name, float value) implements ShaderParam {
        @Override public void pack(Std140Builder builder) { builder.putFloat(value); }
    }

    record Vec2(String name, float x, float y) implements ShaderParam {
        @Override public void pack(Std140Builder builder) { builder.putVec2(x, y); }
    }

    record Vec3(String name, float x, float y, float z) implements ShaderParam {
        @Override public void pack(Std140Builder builder) { builder.putVec3(x, y, z); }
    }

    record Vec4(String name, float x, float y, float z, float w) implements ShaderParam {
        @Override public void pack(Std140Builder builder) { builder.putVec4(x, y, z, w); }
    }
}
