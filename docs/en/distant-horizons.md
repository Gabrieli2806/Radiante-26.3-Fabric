[← Back to index](README.md)

# Distant Horizons (experimental)

[Distant Horizons](https://modrinth.com/mod/distanthorizons) generates and shows terrain far beyond
Minecraft's normal render distance, at a progressively lower level of detail the farther away it is.
Radiante can trace that far terrain along with the rest of the world, instead of leaving it out of
ray tracing entirely.

It is entirely optional: without Distant Horizons installed, nothing changes. With it installed and
active, Radiante detects it by itself — there is no setting to turn on in Radiante's own screen.

## What the far terrain looks like

Distant Horizons' terrain is simplified to one colour per block face (rather than the fully textured
geometry near terrain uses), and is traced just like any other section of the world: it receives
light, casts shadows, and shows up in reflections. The farther away, the coarser the sections DH
hands Radiante to trace.

![Distant Horizons far terrain](../images/distant-horizons-terreno-lejano.png)
<!-- TODO: near terrain with full RT and DH's far terrain visibly simpler -->

## What to expect, honestly

This is still under active tuning. Things you may notice:

- **The odd seam or flicker** where far terrain switches level of detail (a large, coarse section is
  swapped for its finer children, or back), especially in areas up to 2048 blocks out.
- **Frame cost grows with Distant Horizons' render distance.** The farther out you configure DH to
  generate, the more it costs the GPU to trace as well. The cost at DH's default distance (512
  chunks) on a fully generated world is still being measured — see
  [ROADMAP.md](../../ROADMAP.md#distant-horizons-far-terrain--implemented-pending-in-game-tuning).
- Textured detail for DH's nearest sections (right where normal terrain ends) is not implemented
  yet — that band shows the flat per-face colour, not textures.

None of this breaks the game; these are known visual limitations of a feature explicitly marked
experimental.

## If you quit while Distant Horizons is still generating

If you close the world or the game while DH is still generating terrain, its own generation threads
can be left waiting on data from a server that has already stopped, which without help would only
close once Minecraft's own shutdown watchdog kills it with a crash report — this happens with ray
tracing off too, it is Distant Horizons' behaviour, not Radiante's. Radiante helps the process close
as soon as it detects only DH threads are left waiting, so the watchdog is not needed. There is
nothing you need to do about it.

## Tested compatibility

Verified in a fresh test world, on both Fabric and NeoForge: day, sunset, night, rain, clouds,
underwater, from 70 to 4000 blocks up, toggling ray tracing on/off in a running world, leaving and
rejoining, and teleporting 4000 blocks.
