package com.pocketgallery.tabs;

import com.pocketgallery.PocketGallery;
import com.pocketgallery.Workers;
import com.pocketgallery.config.GalleryConfig;
import com.pocketgallery.folder.FolderScanner;
import com.pocketgallery.gallery.GalleryEntry;
import com.pocketgallery.gallery.GalleryTab;
import com.pocketgallery.gallery.TabHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Images in the image folder (recursive), filtered live by the search box. */
public final class FolderTab extends GalleryTab {
    private Path scannedFolder;
    private List<FolderScanner.LocalImage> files = List.of();
    private boolean scanning;
    private String scanError;
    private AtomicBoolean scanCancel = new AtomicBoolean();
    private List<GalleryEntry> filtered = List.of();
    private String filteredFor;

    public FolderTab() {
        super("folder");
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    @Override
    public void setSearch(String text, TabHost host) {
        super.setSearch(text, host);
        host.resetPage();
    }

    @Override
    public void onShown(TabHost host) {
        ensureScanned(false);
    }

    @Override
    public int addControls(TabHost host, int x, int y) {
        host.addTabWidget(Button.builder(Component.translatable("pocket_gallery.gui.open_folder"), b -> openFolder())
                .bounds(x, y, 80, 20).build());
        host.addTabWidget(Button.builder(Component.translatable("pocket_gallery.gui.rescan"), b -> {
            host.clearImageCaches();
            ensureScanned(true);
            host.resetPage();
        }).bounds(x + 82, y, 56, 20).build());
        return x + 144;
    }

    private static void openFolder() {
        try {
            Files.createDirectories(PocketGallery.imageFolder());
        } catch (Exception ignored) {
            // openPath shows the error itself
        }
        Util.getPlatform().openPath(PocketGallery.imageFolder());
    }

    /** Starts a scan if the folder changed or {@code force}. */
    public void ensureScanned(boolean force) {
        Path folder = PocketGallery.imageFolder();
        if (!force && folder.equals(scannedFolder) && (scanning || scanError == null)) return;
        scanCancel.set(true);
        AtomicBoolean cancel = new AtomicBoolean();
        scanCancel = cancel;
        scannedFolder = folder;
        scanning = true;
        scanError = null;
        Workers.IO.execute(() -> {
            List<FolderScanner.LocalImage> result;
            String error = null;
            try {
                Files.createDirectories(folder);
                result = FolderScanner.scan(folder, cancel::get);
            } catch (Exception e) {
                result = List.of();
                error = "Cannot read folder: " + e.getMessage();
            }
            List<FolderScanner.LocalImage> r = result;
            String err = error;
            Minecraft.getInstance().execute(() -> {
                if (cancel.get()) return;
                files = r;
                scanning = false;
                scanError = err;
                filteredFor = null;
            });
        });
    }

    @Override
    public List<GalleryEntry> entries() {
        if (!search().equals(filteredFor)) {
            List<GalleryEntry> list = new ArrayList<>();
            for (FolderScanner.LocalImage img : files) {
                if (FolderScanner.matches(img, search())) list.add(new GalleryEntry.Local(img));
            }
            filtered = list;
            filteredFor = search();
        }
        return filtered;
    }

    @Override
    public Status status() {
        if (scanning) return new Status("Scanning " + PocketGallery.imageFolder() + " ...", Status.NORMAL);
        if (scanError != null) return new Status(scanError, Status.ERROR);
        int shown = entries().size();
        return new Status(shown + (shown == files.size() ? "" : " of " + files.size()) + " images in "
                + PocketGallery.imageFolder(), Status.NORMAL);
    }

    @Override
    public String emptyMessage() {
        return scanning ? "" : "No images. Put png/jpg/webp/bmp/gif files into the folder and press Rescan.";
    }

    @Override
    public void onConfigChanged(GalleryConfig before, GalleryConfig after) {
        if (!Objects.equals(before.folderPath, after.folderPath)) ensureScanned(true);
    }
}
