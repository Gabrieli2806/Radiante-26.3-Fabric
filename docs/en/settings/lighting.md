[← Settings](../settings.md) · [Index](../README.md)

# Lighting

![Lighting category](../../images/opciones-iluminacion.png)
<!-- TODO: the full category, with the day/night/emission/held-item brightness sliders -->

| Control | Range | Default | What it does |
|---|---|---|---|
| **Day Brightness** | 0% – 400% | 25% | Brightness of sunlight and the daytime sky, as a percentage of the shader pack's own. |
| **Night Brightness** | 0% – 400% | 35% | Brightness of moonlight and the night sky. |
| **Light Source Brightness** | 0% – 400% | 12% | Brightness of blocks that give off light: torches, lava, glowstone, lit redstone lamps, and so on. |
| **Held Light Brightness** | 0% – 400% | 12% | Brightness of what you hold in your hand — a torch, lantern, lava bucket — both its own glow and the light it casts around you. This has been its own control separate from Light Source Brightness since 0.2.0; a config from before that change keeps using the emission value until you retune it. |
| **Bounced Light** | 50% – 200% | 150% | How much light carries on after bouncing off surfaces. Higher fills rooms from a single patch of sunlight, as Bedrock RTX does; 100% is physically based. |
| **Sky Light** | 50% – 400% | 180% | How strongly the open sky lights the world. Higher brightens shade under buildings and trees. |
| **Block Light Sampling** | On/Off | On | Surfaces aim a shadow ray straight at a nearby light block instead of waiting for a random bounce to find it. Much less noise at night and in caves, for a small cost. Needs the terrain to have blocks with emission detected (see "Block Emission" in [Performance](performance.md)). |
| **Held Item Light** | On/Off | On | A torch, lantern or other light source held in either hand lights up its surroundings. Known limitation: the light does not spread much beyond a small radius around the hand — see the limitations list in the [index](../README.md#known-limitations-alpha). |
| **Pixelated Lighting** | On/Off | Off | Snaps light and shadows to the texture's pixel grid: every pixel of a block is lit as one flat tile, a retro, blocky look similar to pixelated lighting in BetterRTX. Off, light falls smoothly across a surface. |
| **First Person Shadow** | On/Off | On | Draws the player for shadows and reflections while the camera is in first person. Minecraft itself skips the player then, so without this you cast no shadow and do not appear in water or glass. |

## "Bounced Light" and "Sky Light" are also part of Picture Style

These two sliders change together with Saturation when you pick a Picture Style other than Custom
(see [Image](image.md) and [styles-and-quality.md](../styles-and-quality.md)). Touching either by
hand switches Picture Style to "Custom", the same way it happens with the Quality level.
