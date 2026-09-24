package cn.spectra.gallium.glowoutline.mixin.accessor;
import com.mojang.blaze3d.vertex.BufferBuilder;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(BufferBuilder.class)
public interface LegacyBufferBuilderAccessor {
    @Accessor("buffer") ByteBuffer gallium$buffer();
}
