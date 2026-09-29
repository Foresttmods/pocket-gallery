# Pocket Gallery

Client-side mod for Minecraft **26.2** (**NeoForge** and **Fabric**) that adds an in-game image gallery to
[Immersive Paintings](https://github.com/Luke100000/ImmersivePaintings) (IP). Browse a folder of images,
public-domain masterpieces from open-access museums, nature and architecture photos, space images, vintage color
photos and wallpapers, select as many as you like and import them as IP paintings in their original quality, sized
automatically by aspect ratio, or with a Minecraft-style pixel look. Animated GIFs play on your client.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

## Installation

Copy into the instance's `mods` folder:

| | NeoForge | Fabric |
|---|---|---|
| Pocket Gallery | `pocket_gallery-neoforge-26.2-1.2.0.jar` | `pocket_gallery-fabric-26.2-1.2.0.jar` |
| Immersive Paintings 0.7.9+ | NeoForge 26.2 build | Fabric 26.2 build |
| IP's dependencies | fzzy_config | fzzy_config, Fabric API, Fabric Language Kotlin |

NeoForge 26.2.0.84+ or Fabric Loader 0.19.3+. Pocket Gallery is client-only: Immersive Paintings must also be on the
server (it stores the paintings), Pocket Gallery doesn't need to be. Remove any older `painting_gallery-...jar`
first (the mod was called Painting Gallery before; its config files are migrated automatically).

## Usage

Open the gallery with **K** (rebindable under Controls > Key Binds > "Pocket Gallery"), or with the **Gallery...**
button in Immersive Paintings' painting screen (right-click a placed IP painting).

### Folder tab
- The folder defaults to `<game folder>/paintings` and is created if missing. You can change it in **Settings**.
- **Open folder** opens it in your file manager. **Rescan** re-reads it.
- Scanned recursively for `png`, `jpg`/`jpeg`, `webp`, `bmp` and `gif`. `webp` support comes from the reader bundled
  with Immersive Paintings. The search box filters by file name and path.

### Museums tab
Public-domain paintings (made up to 1925 by default, changeable in Settings) from:

| Collection | Notes |
|---|---|
| Art Institute of Chicago | up to 3000 px |
| The Metropolitan Museum of Art | European paintings and Japanese prints; fetched slowly on purpose (its firewall blocks bursts) |
| Cleveland Museum of Art | print-size originals |
| **SMK - National Gallery of Denmark** | new in 1.2; Danish Golden Age and European masters, up to 3000 px |
| **Wikimedia Commons** | new in 1.2; featured pictures of paintings and ~9,700 Google Art Project scans from museums worldwide |

The museum button cycles between All and one collection. Type an artist, style or subject (`Monet`, `Baroque`,
`harbor`) or use **Browse...** for quick picks. None of these needs an account or key.

### Photos tab
Pick a **Topic**; words typed in the search box narrow it.

| Group | Source | Topics |
|---|---|---|
| Nature, Architecture | Wikimedia Commons featured / quality pictures | landscapes, mountains, forests, lakes, waterfalls, coasts, sunsets, snow, animals & wildlife, birds, buildings, cityscapes, churches, castles, palaces, bridges, towers... |
| Space | **NASA Image and Video Library** (new) | nebulae, galaxies, planets & moons, Earth from orbit, auroras, rocket launches, spacewalks, any NASA image |
| Vintage | **Library of Congress** (new) | Photochrom color prints (1890s), early color photos by Prokudin-Gorskii (1905-1915), Japanese woodblock prints, WPA posters (1930s) |
| Wallpapers | **Wallhaven** (new) | most loved, landscapes, space, animals, fantasy art, cities, minimalist - general category, safe-for-work only |
| Wallpapers | **Pixabay** (new, needs a free key) | illustrations, photos - safe search always on |

- Library of Congress images are at most about 1024 px (enough for a 4-block painting at 256 px per block).
- **Pixabay**: log in at pixabay.com, open pixabay.com/api/docs, copy your key into **Settings > Pixabay API key**.
  Search results are cached for 24 hours (Pixabay's rule) in `<game folder>/cache/pocket_gallery/api`.
- **Licenses** are shown in the preview and stored in `config/pocket_gallery_sources.json`: museums and NASA are
  public domain / no copyright, Commons photos are mostly CC BY-SA (credit the photographer), Library of Congress
  items have no known restrictions, Pixabay uses the Pixabay Content License. **Wallhaven images belong to their
  original artists** - Immersive Paintings shows imported paintings to everyone on the server, so only import what
  you may share.

### Selecting and importing
- **Click** toggles an image, **Shift+click** selects a range, **Right click** only previews. **Select page** / **None**.
  Selections persist across pages and tabs.
- **- / +** sets the painting's long side in blocks (1-16, default 4); the short side follows the aspect ratio.
- **Crop: ON** (default) center-crops to the exact block ratio; **Crop: OFF** stretches.
- **Import (N)** starts a background queue: download, decode, crop, downscale only above the server's limit
  (4096 px by default), upload through IP. A toast shows progress. **Cancel** stops after the current image.
- Downloads are kept in the image folder (`art/<museum>/`, `photos/<source>/`, `wallpapers/<source>/`), so they also
  appear in the Folder tab and keep their source there.

### Pixel look (per image)
**Pixels:** Off or 16x-256x pixels per block (16x = like vanilla paintings). **Colors:** All or a 64/32/16/8 colour
palette. **Same look for N selected** copies it. The default look for new images is in Settings.

### Sorting by source in Immersive Paintings' "Yours" tab
Names start with `[Museum]`, `[Photo]`, `[Wallpaper]` or `[Folder]`, so IP's name-sorted Yours tab groups them (can
be turned off in Settings). The **Source: All** button on the Yours page filters by source; "Other" = paintings not
imported with Pocket Gallery.

## Limits (from Immersive Paintings, configured on the server)
Paintings are 1-16 blocks per side at 8-256 px per block, images at most 4096x4096 (`maxUserImageWidth/Height`),
at most 1000 paintings per player (`maxUserImages`), uploads rate limited by `maxPacketsPerSecond`, and uploading may
need a permission level (`uploadPermissionLevel`).

## Animated GIFs
IP stores still images. For a GIF the first frame is uploaded and Pocket Gallery animates the painting **on your
client** (`config/pocket_gallery_animated.json`); other players see the first frame. Settings: max GIF frames (256),
max animated paintings (16), animation memory budget (512 MB).

## Config
`config/pocket_gallery.json` (most options are in the in-game Settings):

| key | default | meaning |
|---|---|---|
| `folderPath` | `""` | image folder; empty = `<game folder>/paintings` |
| `longSideBlocks` | `4` | painting long side (1-16) |
| `cropToFit` | `true` | center-crop to the block ratio |
| `animateGifs`, `maxGifFrames`, `maxAnimatedPaintings`, `maxAnimationMemoryMb` | `true`, 256, 16, 512 | GIF animation |
| `artLatestYear` | `1925` | Museums: latest year of shown artworks |
| `museumFilter` | `ALL` | `ALL`, `AIC`, `MET`, `CLEVELAND`, `SMK`, `COMMONS` |
| `photoTopic` | `LANDSCAPES` | current Photos topic |
| `pixabayApiKey` | `""` | free Pixabay key (Pixabay topics only) |
| `sourcePrefix` | `true` | `[Museum]`/`[Photo]`/... at the start of painting names |
| `defaultPixelsPerBlock`, `defaultColors` | `0`, `0` | pixel look for new images |

## Building

JDK 25. The mod is compiled against Immersive Paintings, whose jars are not part of this repository: download the
NeoForge and Fabric 26.2 builds of Immersive Paintings 0.7.9 from
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/immersive-paintings) or
[Modrinth](https://modrinth.com/mod/immersive-paintings) and put them in a `libs/` folder as
`immersive_paintings-neoforge-26.2-0.7.9.jar` and `immersive_paintings-fabric-26.2-0.7.9.jar` (the names are set in
`gradle.properties`). Then:

```
./gradlew build          (Windows: gradlew.bat build)
```

Output: `neoforge/build/libs/pocket_gallery-neoforge-26.2-<version>.jar` and
`fabric/build/libs/pocket_gallery-fabric-26.2-<version>.jar`. Versions and metadata (authors, homepage) are in
`gradle.properties`; `mod_homepage` is also sent in the User-Agent, which Wikimedia asks for.

Layout: `common/` holds all shared code (compiled into both jars), `neoforge/` and `fabric/` only the entry points
(key binding, events; Fabric adds one mixin for the per-frame GIF hook). Minecraft 26.x is unobfuscated, so both
loaders use Mojang names and Fabric uses the non-remapping Loom plugin. Unit tests (JUnit 5, run by the NeoForge
project) cover sizing, cropping, resampling, pixel look, GIF compositing, every web source's parser against real
captured responses, source names and config round-trips.

## License

GPL-3.0, like Immersive Paintings, which this mod integrates with.
