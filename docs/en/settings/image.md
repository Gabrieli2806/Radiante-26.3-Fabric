[← Settings](../settings.md) · [Index](../README.md)

# Image

![Image category](../../images/opciones-imagen.png)
<!-- TODO: Picture Style, Tone Mapping, Saturation, and the expanded HDR section -->

| Control | Range / Values | Default | What it does |
|---|---|---|---|
| **Picture Style** | Natural · Bedrock · Vivid · Custom | Natural | Sets Saturation, Bounced Light and Sky Light at once. Full detail in [styles-and-quality.md#picture-styles](../styles-and-quality.md#picture-styles). |
| **Tone Mapping** | PBR Neutral · ACES · Reinhard | PBR Neutral | How bright light is fitted to what the screen can show. PBR Neutral keeps colours true; ACES is more filmic with more contrast; Reinhard is soft and flat. |
| **Saturation** | 50% – 200% | 120% | How colourful the image is. 100% is unchanged from the shader pack's own. |
| **Eye Adaptation** | 0% – 100% | 50% | How far the exposure adapts to what you look at. Lower keeps dark rooms dark and daylight bright, as in Bedrock; 100% always evens the image out. |
| **Exposure** | -3.0 EV – +3.0 EV | +0.7 EV | Overall brightness of the image, in photographic stops. |
| **Motion Blur** | On/Off | Off | Smears what moves across the screen along its motion, as a camera does. Changing it rebuilds the shaders (a brief hitch when applying). |
| **Depth of Field** | On/Off | Off | Keeps what you look at sharp and blurs what is much nearer or farther, focusing on the centre of the screen. Also rebuilds the shaders when changed. |

## HDR

The HDR section is in this same category, below the controls above. It is covered in full, with
screenshots and display considerations, in [hdr.md](../hdr.md) — quick summary:

| Control | Range | Default |
|---|---|---|
| HDR Output | On/Off | Off |
| HDR Peak Brightness | 400 – 4000 nits | 1000 nits |
| HDR Paper White | 80 – 400 nits | 200 nits |
| HDR Debug View | On/Off | Off (not saved) |

Turning HDR Output on or off needs a game restart; the two brightness sliders apply instantly.
