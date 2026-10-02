[← Back to index](README.md)

# Bedrock RTX resource packs

Radiante can read `.mcpack` resource packs made for **Minecraft Bedrock RTX** and convert them into a
LabPBR pack its own renderer understands, as well as read the sky and caustics files from a Bedrock
install on the same PC. Neither is required — Radiante ships its own maps for 154 vanilla textures —
but a Bedrock pack usually gives more detail and material variety than what is included.

## Using a `.mcpack` pack

1. Drop the `.mcpack` file straight into Minecraft's `resourcepacks` folder, just like any other
   resource pack.
2. Enable it from the game's resource pack menu: Radiante detects it directly in the list, without
   you needing to unzip it or rename it to `.zip` first.
3. The first time you enable it, Radiante converts it to a LabPBR pack and saves the result in
   `radiante/bedrock_packs/` inside the game directory (not next to the original `.mcpack`, so it
   does not show up twice in the pack list). Later uses read the converted copy directly, and only
   convert again if the `.mcpack` changed or a newer version of the converter requires it.

<!-- Screenshot pending: the resource pack list with a .mcpack visible and enable-able -->

### What exactly gets converted

Bedrock describes each texture with a "texture set": a colour texture, a MER(S) map (metalness,
emission, roughness, and optionally subsurface), and a height or normal map. For every Java block
texture that has a Bedrock counterpart, the converter builds:

- The colour texture (if it is size/format compatible with Java's).
- A LabPBR specular map (`_s.png`) from the MER — Bedrock's metalness, emission, roughness and
  subsurface are translated into the channels LabPBR expects.
- A LabPBR normal map (`_n.png`), from Bedrock's normal map if it has one, or derived from its
  height map otherwise.

Maps finer than the colour texture (96 px is common in Bedrock RTX packs, versus Java's 16 px) keep
their own detail when the colour texture can be scaled up to match without looking soft; otherwise
they are averaged down to the colour texture's size. The Bedrock pack's per-biome volumetric fog is
converted too, and is used together with Radiante's own Biome Fog (see
[Sky, Fog & Water](settings/sky-fog-water.md)).

## Sky and caustics from a Bedrock install

With the **Atmosphere** setting on "Bedrock" (in the
[Sky, Fog & Water](settings/sky-fog-water.md) category), Radiante looks for Bedrock RTX's sky, look-up
table and caustics files at, in order:

1. `radiante/bedrock/sky.png`, `look_up_tables.png` and `caustics.png` inside the game directory.
2. `C:/XboxGames/Minecraft for Windows/Content/data/ray_tracing/` — the default path where
   Minecraft Bedrock (the Xbox/Microsoft Store version) installs those same files, if you have
   Bedrock installed on the same PC.

If it cannot find the files at either location — or if `sky.png` is not 64×256 or
`look_up_tables.png` is not 128 wide, the exact dimensions the shader expects — it falls back to
Radiante's own sky (the same one used with Atmosphere set to "Java") even if the setting is on
Bedrock. This is a silent switch, with no warning or error message: the shader simply decides the
1x1 stand-in texture is not a valid Bedrock sky and carries on with its own. Caustics work the same
way, independently: without `caustics.png` available, Radiante's procedural caustics are used
instead of failing.

<!-- Screenshot pending: the sky with Atmosphere set to Java and to Bedrock, same time of day -->

### Copying the files by hand

If you do not have Bedrock installed on this PC but do have those three files from another source,
place them under `radiante/bedrock/` inside the game directory, with these exact names:

```
<game directory>/radiante/bedrock/sky.png
<game directory>/radiante/bedrock/look_up_tables.png
<game directory>/radiante/bedrock/caustics.png
```

## Before / after

<!-- Screenshot pending: the same scene with a .mcpack enabled and disabled -->

## See also

- [styles-and-quality.md](styles-and-quality.md) — the "Bedrock" Picture Style tunes saturation and
  bounced light to look closer to Bedrock RTX; it is a colour adjustment independent from using a
  pack or Bedrock's sky files.
- The state of visual parity with Bedrock RTX (what is still missing, what already matches) is
  tracked in [ROADMAP.md](../../ROADMAP.md#bedrock-rtx-parity--in-progress), including the side by
  side comparison done against Bedrock RTX in the same test worlds.
