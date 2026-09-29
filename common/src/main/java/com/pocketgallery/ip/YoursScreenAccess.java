package com.pocketgallery.ip;

/**
 * Implemented by Immersive Paintings' painting screen through {@code ImmersivePaintingScreenMixin}. When the mixin
 * could not be applied the screen simply doesn't implement it and the Source filter button stays hidden.
 */
public interface YoursScreenAccess {
    /** True while the screen shows the "Yours" page. */
    boolean paintingGallery$isYoursPage();

    /** Re-runs IP's search so a changed Source filter takes effect. */
    void paintingGallery$refresh();
}
