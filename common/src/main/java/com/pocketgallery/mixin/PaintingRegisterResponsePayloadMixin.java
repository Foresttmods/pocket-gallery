package com.pocketgallery.mixin;

import com.pocketgallery.upload.UploadResponses;
import net.conczin.immersive_paintings.network.payload.ImmersivePayload;
import net.conczin.immersive_paintings.network.payload.s2c.PaintingRegisterResponsePayload;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Immersive Paintings only reports upload results to its own screen. This hook forwards the server's answer
 * (new painting id or error key such as {@code limit_reached}) to our upload queue while one of our uploads is in
 * flight. Optional: without it, the queue falls back to watching IP's painting list.
 */
@Mixin(value = PaintingRegisterResponsePayload.class, remap = false)
public abstract class PaintingRegisterResponsePayloadMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, require = 0)
    private void paintingGallery$captureResponse(Player player, ImmersivePayload.Runner runner, CallbackInfo ci) {
        try {
            PaintingRegisterResponsePayload self = (PaintingRegisterResponsePayload) (Object) this;
            String id = self.identifier().map(Object::toString).orElse(null);
            if (UploadResponses.offer(id, self.error())) {
                ci.cancel();
            }
        } catch (LinkageError | RuntimeException e) {
            // never break IP's own packet handling; the upload queue falls back to watching the painting list
        }
    }
}
