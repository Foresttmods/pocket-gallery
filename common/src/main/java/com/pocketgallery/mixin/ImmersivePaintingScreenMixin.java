package com.pocketgallery.mixin;

import com.pocketgallery.PocketGallery;
import com.pocketgallery.ip.YoursScreenAccess;
import com.pocketgallery.ip.YoursSourceFilter;
import net.conczin.immersive_paintings.client.gui.ImmersivePaintingScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Adds the Source filter to Immersive Paintings' "Yours" page: right before IP pages its filtered list, paintings
 * from other sources are removed. Optional - if IP changes, the injection is skipped and the button stays hidden.
 */
@Mixin(value = ImmersivePaintingScreen.class, remap = false)
public abstract class ImmersivePaintingScreenMixin implements YoursScreenAccess {
    @Shadow
    @Final
    private List<Identifier> filteredPaintings;

    @Shadow
    private ImmersivePaintingScreen.Page page;

    @Shadow
    private void updateSearch() {
    }

    @Inject(method = "updateSearch", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/conczin/immersive_paintings/client/gui/ImmersivePaintingScreen;setSelectionPage(I)V"))
    private void paintingGallery$filterBySource(CallbackInfo ci) {
        try {
            if (page == ImmersivePaintingScreen.Page.YOURS) YoursSourceFilter.apply(filteredPaintings);
        } catch (LinkageError | RuntimeException e) {
            PocketGallery.LOG.warn("Source filter failed", e);
        }
    }

    @Override
    public boolean paintingGallery$isYoursPage() {
        return page == ImmersivePaintingScreen.Page.YOURS;
    }

    @Override
    public void paintingGallery$refresh() {
        updateSearch();
    }
}
