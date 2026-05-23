package cn.spectra.gallium.glowoutline;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;

public sealed interface ShaderParam {

    String name();
    void pack(Std140Builder builder);
    int std140Size();

    record Float(String name, float value) implements ShaderParam {
        @Override public void pack(Std140Builder builder) { builder.putFloat(value); }
        @Override public int std140Size() { return new Std140SizeCalculator().putFloat().get(); }
    }

    record Vec2(String name, float x, float y) implements ShaderParam {
        @Override public void pack(Std140Builder builder) { builder.putVec2(x, y); }
        @Override public int std140Size() { return new Std140SizeCalculator().putVec2().get(); }
    }

    record Vec3(String name, float x, float y, float z) implements ShaderParam {
        @Override public void pack(Std140Builder builder) { builder.putVec3(x, y, z); }
        @Override public int std140Size() { return new Std140SizeCalculator().putVec3().get(); }
    }

    record Vec4(String name, float x, float y, float z, float w) implements ShaderParam {
        @Override public void pack(Std140Builder builder) { builder.putVec4(x, y, z, w); }
        @Override public int std140Size() { return new Std140SizeCalculator().putVec4().get(); }
    }
}
