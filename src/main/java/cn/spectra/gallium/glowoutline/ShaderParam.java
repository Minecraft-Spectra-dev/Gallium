package cn.spectra.gallium.glowoutline;

//#if MC>=1_21_06
import com.mojang.blaze3d.buffers.Std140Builder;
//#endif

public sealed interface ShaderParam {

    String name();
    //#if MC>=1_21_06
    void pack(Std140Builder builder);
    //#endif

    record Float(String name, float value) implements ShaderParam {
        //#if MC>=1_21_06
        @Override public void pack(Std140Builder builder) { builder.putFloat(value); }
        //#endif
    }

    record Vec2(String name, float x, float y) implements ShaderParam {
        //#if MC>=1_21_06
        @Override public void pack(Std140Builder builder) { builder.putVec2(x, y); }
        //#endif
    }

    record Vec3(String name, float x, float y, float z) implements ShaderParam {
        //#if MC>=1_21_06
        @Override public void pack(Std140Builder builder) { builder.putVec3(x, y, z); }
        //#endif
    }

    record Vec4(String name, float x, float y, float z, float w) implements ShaderParam {
        //#if MC>=1_21_06
        @Override public void pack(Std140Builder builder) { builder.putVec4(x, y, z, w); }
        //#endif
    }
}
