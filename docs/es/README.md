# Documentación de Radiante

![Radiante](../images/logo-radiante.png)
<!-- TODO: icono/logo de Radiante, o quitar esta línea si no hay uno todavía -->

Idiomas: **ES** · [EN](../en/README.md)

Radiante es un mod de trazado de rayos por hardware para Minecraft 26.3 (Fabric, NeoForge y Forge),
construido sobre el propio backend Vulkan de Minecraft en vez de crear un segundo dispositivo Vulkan
al lado. Es un fork de [Radiance](https://github.com/Minecraft-Radiance/Radiance) y su renderizador
nativo [MCVR](https://github.com/Minecraft-Radiance/MCVR), de LJIONG e Interstellarss.

Versión de esta documentación: **0.2.0** (alpha).

## Empezar

1. **[Requisitos](requisitos.md)** — qué GPU, versión de Minecraft y de Java necesitas.
2. **[Instalación](instalacion.md)** — pasos por loader (Fabric, NeoForge, Forge) y qué esperar al
   entrar por primera vez.
3. **[Ajustes](opciones.md)** — cómo está organizada la pantalla de ajustes, el botón de restablecer
   valores, y el enlace a cada categoría.

## Funciones a fondo

- **[Estilos de imagen y niveles de calidad](estilos-y-calidad.md)** — los presets Baja/Media/Alta/Ultra,
  y los estilos Natural/Bedrock/Vívido.
- **[Resource packs de Bedrock RTX](bedrock-rtx.md)** — usar un `.mcpack` de Bedrock, y leer archivos
  de una instalación de Bedrock en el mismo PC.
- **[Distant Horizons](distant-horizons.md)** — soporte experimental de terreno lejano.
- **[HDR](hdr.md)** — salida en pantallas HDR, brillo máximo y blanco de papel.
- **[Generación de fotogramas y NVIDIA Reflex](frame-generacion-reflex.md)** — DLSS Frame Generation
  y el modo de baja latencia.
- **[Rendimiento](rendimiento.md)** — qué cuesta más FPS y cómo ajustarlo a tu GPU.
- **[Problemas comunes](problemas.md)** — pantallas de "no disponible", cambios de API gráfica, y
  qué hacer si algo no funciona.

## Referencia de ajustes por categoría

La pantalla de ajustes de Radiante (`F6` en el juego) tiene seis categorías. Cada una tiene su propia
página con todos sus controles, valor por defecto y qué hace cada uno:

| Categoría en el juego | Documento |
|---|---|
| Calidad y Escalado | [opciones/calidad-y-escalado.md](opciones/calidad-y-escalado.md) |
| Imagen | [opciones/imagen.md](opciones/imagen.md) |
| Iluminación | [opciones/iluminacion.md](opciones/iluminacion.md) |
| Cielo, Niebla y Agua | [opciones/cielo-niebla-agua.md](opciones/cielo-niebla-agua.md) |
| Rendimiento | [opciones/rendimiento.md](opciones/rendimiento.md) |
| Otros | [opciones/otros.md](opciones/otros.md) |

## Qué es Radiante, en corto

- Trazado de rayos por hardware (`VK_KHR_ray_tracing_pipeline`) para el terreno: iluminación directa
  path-traced, sombras suaves e iluminación global.
- Cielo, sol y luna físicamente basados, con su tamaño angular y color reales.
- Un shader pack, `vanilla-pt`: muestreo directo de luces de bloque, niebla y nubes volumétricas,
  lluvia/nieve con vectores de movimiento correctos, e iluminación pixelada opcional.
- Soporte LabPBR (`_n`/`_s`) para bloques, entidades e ítems en mano, con emisión por entidad.
- Escalado mediante DLSS, FSR 3 y XeSS, con denoising NRD (RELAX).
- Desenfoque de movimiento y profundidad de campo, ambos opcionales.
- Soporte experimental de Distant Horizons y de resource packs `.mcpack` de Bedrock.
- Corre sobre el dispositivo Vulkan que Minecraft ya crea: sin segunda instancia, sin swapchain
  duplicado.

## Limitaciones conocidas (alpha)

Esto es software en fase alpha. Sé honesto contigo mismo sobre lo que todavía no funciona:

- Los presets basados en NRD (todo lo que no es DLSS Ray Reconstruction) tienen algo más de ruido
  del denoiser que DLSS-RR. Si tienes una GPU NVIDIA, DLSS es el camino estable ahora mismo.
- La luz de un objeto en la mano no se propaga a su alrededor más allá de un pequeño radio.
- Las nubes vanilla (no volumétricas), el clima, la animación de romper bloques y el brillo de
  encantamiento todavía no están portados a este renderizador.
- Generación de fotogramas y NVIDIA Reflex solo funcionan en Fabric por ahora — ver
  [frame-generacion-reflex.md](frame-generacion-reflex.md).
- Distant Horizons puede mostrar alguna costura o parpadeo en el terreno lejano mientras se sigue
  ajustando — ver [distant-horizons.md](distant-horizons.md).

La lista completa y actualizada de trabajo pendiente está en
[ROADMAP.md](../../ROADMAP.md), en la raíz del repositorio.

## Enlaces

- [GitHub](https://github.com/Gabrieli2806/Radiante-26.3-Fabric) — código fuente y reportes de errores.
- [Modrinth](https://modrinth.com/project/radiante) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/radiante)
- [Discord](https://discord.gg/DhBbAzugZ9) — para preguntas, reportes y discutir cambios grandes antes de un PR.
- [Ko-fi](https://ko-fi.com/gabrieli2806)

## Licencia

GPL-3.0, heredada de los proyectos originales. Radiance y MCVR son de LJIONG e Interstellarss; este
fork mantiene su licencia y créditos.
