package cn.spectra.gallium.mixin;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.dump.ResourceDumpCompressor;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

	@Shadow
	private Minecraft minecraft;

	/**
	 * 拦截 F3+S（动态资源转储）按键，在原版转储完成后自动压缩为 zip 文件
	 */
	@Inject(method = "handleDebugKeys", at = @At("RETURN"))
	private void onHandleDebugKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (this.minecraft.options.keyDebugDumpDynamicTextures.matches(event)) {
			// F3+S 被触发，延迟一 tick 执行压缩以确保文件写入完成
			this.minecraft.schedule(() -> {
				this.minecraft.schedule(ResourceDumpCompressor::compressLatestDump);
			});
			Gallium.LOGGER.info("F3+S detected, will compress dump on next tick");
		}
	}
}
