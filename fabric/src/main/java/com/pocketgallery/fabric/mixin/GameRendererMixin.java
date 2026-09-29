package com.pocketgallery.fabric.mixin;

import com.pocketgallery.ClientHooks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame hook for GIF paintings, at the same point where NeoForge fires {@code RenderFrameEvent.Pre}: right before
 * the game renders the frame. Optional - without it, animations simply don't advance.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), require = 0)
    private void pocketGallery$beforeFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        ClientHooks.onRenderFrame();
    }
}
