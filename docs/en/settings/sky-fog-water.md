[← Settings](../settings.md) · [Index](../README.md)

# Sky, Fog & Water

![Sky, Fog & Water category](../../images/opciones-cielo-agua.png)
<!-- TODO: the category with Clouds and Volumetric Fog turned on -->

| Control | Range / Values | Default | What it does |
|---|---|---|---|
| **Atmosphere** | Java · Bedrock | Bedrock | Bedrock: Bedrock RTX's sky and sunlight colours, read from a Bedrock install on this PC (or from `radiante/bedrock/sky.png` and `look_up_tables.png`). Java: Radiante's own sky. Without Bedrock files available, the Java sky is used even if this is set to Bedrock. Full detail in [bedrock-rtx.md](../bedrock-rtx.md). |
| **Clouds** | Off · Vanilla · Volumetric | Vanilla | How clouds are drawn. Volumetric are real 3D clouds that cast shadows and cost the most GPU time; vanilla-style are cheaper (the usual flat cloud mesh, but traced and lit); off is fastest. |
| **Sun Glow** | 0% – 300% | 100% | The halo around the sun. |
| **Light Shafts** | 0% – 400% | 100% | Beams of light through windows, skylights and leaves. Needs Volumetric Fog turned on below. |
| **Vanilla Sun Path** | On/Off | On | Sun and moon follow vanilla's path, straight overhead from east to west. Off, the path leans ten degrees south, so noon shadows are not straight down. |
| **Vanilla Sun/Moon Rotation** | On/Off | On | Keeps the sun and moon squares lined up with their path as in vanilla, instead of turning as they cross the sky. |
| **Biome Fog** | On/Off | On | Per-biome haze in every dimension: warm dust over deserts, thick green air over swamps, red haze in crimson forests, purple haze in the End. Fades between biomes and stays out of overworld caves. |
| **Biome Fog Strength** | 0% – 400% | 100% | How thick that haze is. 100% is the tuned look; no effect on performance. |
| **Volumetric Fog** | On/Off | On (if the pipeline supports it) | Ray marches the air, like Bedrock RTX: sunlight and moonlight scatter in the biome fog, and trees and terrain cast light shafts through it. Costs noticeably more GPU time than the plain haze. Its quality (samples per ray) is set in [Performance](performance.md). |
| **Volumetric Fog Strength** | 0% – 400% | 100% | How thick the air that shows sunbeams and light shafts is. No effect on performance — only the sample count (in Performance) does. |
| **Water Waves** | 0% – 400% | 100% | How rough the water surface is. 0% is a flat, mirror-like surface. |
| **Water Murkiness** | 0% – 500% | 100% | How quickly the view fades out underwater. Lower is clearer. |
| **Underwater Light Rays** | 0% – 400% | 100% | Shafts of sunlight through the water surface while underwater. |

## Nether and End

Biome Fog also applies in the Nether and the End, where there is no sky to light it: there it uses
each biome's vanilla fog colour (blended by vanilla the same way as always) with a small tint, and
the End is lifted off near-black so it does not read as fully dark. Volumetric Fog, on the other
hand, only applies in the Overworld; the Nether and End always use the plain haze, whether it is
turned on or not.
