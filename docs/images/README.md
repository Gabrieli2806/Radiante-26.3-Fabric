# Images

Screenshots used by the docs (`docs/en`, `docs/es`), the READMEs and the Radiante page on the website.

## Structure

| Folder | Content | Format |
|---|---|---|
| `ui/` | Settings screens and the upscaler download flow | PNG |
| `scenes/` | In-game scenes: `hero` (README and site header), gallery shots | WebP, quality 90 |
| `compare/` | Comparisons, one file per variant: `<id>-<variant>.webp` | WebP, quality 90 |

`manifest.json` is the source of truth: it lists every image with its alt text in English and
Spanish (`ui[]`, `scenes[]`) and every comparison with its title, caption and variants
(`compare[]`). Paths are relative to this folder. The website reads the images straight from
GitHub (`raw.githubusercontent.com/.../docs/images/<file>`), so it never bundles copies.

Comparisons with two variants are shown on the site as a draggable split; with three or more, the
reader picks which two to compare. Markdown cannot slide, so the docs show them side by side or one
after another.

## Replacing an image

1. Take it at **1920x1080**. Everything is shot with DLSS Quality unless its label says otherwise.
2. Save it with the **same name** in the same folder: PNG for `ui/`, WebP quality 90 for
   `scenes/` and `compare/`.
3. Commit. The docs and the site pick it up; nothing else to change.

For a new image or comparison, add it to `manifest.json` (id, file, alt or title/caption/variants,
in `en` and `es`) and reference it from the docs.

## Still missing

- Bedrock `.mcpack`: the same scene with the pack on and off, and the resource pack list showing it.
- Atmosphere: Java vs. Bedrock sky at the same time of day.
- Frame generation: the F3 counter showing real vs. generated frames.
- The "Radiante: ray tracing unavailable" screen.
- HDR: a bright scene with HDR on vs. off (a photo of the screen works better than a screenshot).

The docs mark each of these with a `Screenshot pending` / `Captura pendiente` comment.
