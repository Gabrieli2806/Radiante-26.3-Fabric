[← Settings](../settings.md) · [Index](../README.md)

# Lighting

![Lighting category](../../images/opciones-iluminacion.png)
<!-- TODO: the full category, with the brightness sliders and the ReSTIR and SER toggles -->

| Control | Range | Default | What it does |
|---|---|---|---|
| **Day Brightness** | 0% – 400% | 25% | Sun and sky light by day. |
| **Night Brightness** | 0% – 400% | 35% | Moon and sky light by night. |
| **Light Source Brightness** | 0% – 400% | 12% | Blocks that give off light: torches, lava, glowstone… |
| **Held Light Brightness** | 0% – 400% | 12% | What you hold: its own glow and the light it casts. |
| **Bounced Light** | 50% – 200% | 150% | How much light carries on after a bounce. 100% is physically correct; higher fills rooms like Bedrock RTX. |
| **Sky Light** | 50% – 400% | 180% | How much the open sky lights the world. |
| **Block Light Sampling** | On / Off | On | Each surface casts a shadow ray straight at a nearby light. Much less noise at night and in caves. |
| **ReSTIR Block Lights** | On / Off | On | Reuses the light each pixel picked across frames and neighbours. Much less noise in rooms with many lights. Needs Block Light Sampling. |
| **Shader Execution Reordering** | On / Off | Off | SER: the GPU groups similar rays. RTX 40 or newer only. Experimental: water may look white. |
| **Held Item Light** | On / Off | On | A torch or lantern in either hand lights its surroundings. |
| **Glowing Item Light Distance** | 8 – 256 blocks | 64 | How far dropped items and glowing entities cast light. |
| **Pixelated Lighting** | On / Off | Off | Light and shadows snapped to the texture's pixels, a retro look. |
| **First Person Shadow** | On / Off | On | Draws the player for shadows and reflections in first person. |

## Bounced Light and Sky Light are part of the Picture Style too

They change with the Picture Style (see [Image](image.md) and
[styles-and-quality.md](../styles-and-quality.md)). Changing them by hand turns it into Custom.
