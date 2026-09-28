[← Back to index](README.md)

# HDR output

Shows the traced world in real HDR on an HDR display: sunlight, lava, lamps and reflections go
brighter than white, instead of clipping to flat white as they would in SDR. The controls are in the
[Image](settings/image.md) category, at the bottom.

## Requirements

- A display that supports HDR, with **HDR turned on in Windows' display settings** (it is not enough
  for the display to support it; Windows has to actually be using it).
- Turning on "HDR Output" in Radiante.

## How to turn it on

1. Turn on HDR in Windows: Settings → System → Display → HDR, and enable it for your monitor.
2. In Radiante, turn on **HDR Output** (Image category).
3. **Restart the game.** The window's format is decided when it is created, so the change does not
   apply live — if you just turned it on, the setting's tooltip warns you it is "on, but not
   running" until you restart.
4. After restarting, check that the tooltip no longer warns about anything pending; if it still
   does, check that Windows really has HDR active for that monitor.

| Control | Range | Default | Applies |
|---|---|---|---|
| HDR Output | On/Off | Off | After restarting the game |
| HDR Peak Brightness | 400 – 4000 nits | 1000 nits | Instantly |
| HDR Paper White | 80 – 400 nits | 200 nits | Instantly |
| HDR Debug View | On/Off | Off (not saved) | Instantly |

## Peak Brightness and Paper White

- **HDR Peak Brightness** — the brightest your display can show, in nits. Check your monitor's
  specifications or Windows' HDR Calibration. Intense lights in the world (the sun, lava up close)
  are fitted under this value.
- **HDR Paper White** — how bright menus, text and the world's ordinary surfaces (what would be
  "white" in SDR) look, in nits. Raise it in a bright room, lower it in a dark one — in practice, it
  is the overall brightness control for everything that is not an intense light source.

## HDR Debug View

Tints blue everything shown in SDR range (menus, interface text). Whatever keeps its normal colour
is exactly what is being shown in real HDR. Useful to confirm the world is actually using the HDR
range and not just looking brighter — it is not saved between sessions, so it turns off again every
time you open the game.

![HDR comparison](../images/hdr-comparacion.png)
<!-- TODO: the same scene with an intense light source, HDR on vs. off. A photo of the screen or a
     short GIF captures the difference better than a plain PNG, which displays in SDR. -->
