# Documentación de Radiante

![Radiante](../images/logo-radiante.png)
<!-- TODO: icono/logo de Radiante, o quitar esta línea si no hay uno todavía -->

Idiomas: **ES** · [EN](../en/README.md)

Radiante es un mod de trazado de rayos por hardware para Minecraft 26.3 (Fabric, NeoForge y Forge),
en Windows y Linux,
construido sobre el propio backend Vulkan de Minecraft en vez de crear un segundo dispositivo Vulkan
al lado. Es un fork de [Radiance](https://github.com/Minecraft-Radiance/Radiance) y su renderizador
nativo [MCVR](https://github.com/Minecraft-Radiance/MCVR), de LJIONG e Interstellarss.

Versión de esta documentación: **0.5.0** (primera beta).

## Empezar

1. **[Requisitos](requisitos.md)** — sistema, GPU por fabricante, loader y Java.
2. **[Instalación](instalacion.md)** — pasos por loader (Fabric, NeoForge, Forge) y qué esperar al
   entrar por primera vez.
3. **[Escaladores](escaladores.md)** — FSR incluido; DLSS y XeSS se descargan en el juego.
4. **[Ajustes](opciones.md)** — cómo está organizada la pantalla de ajustes, el botón de restablecer
   valores, y el enlace a cada categoría.

## Funciones a fondo

- **[Estilos de imagen y niveles de calidad](estilos-y-calidad.md)** — los presets Baja/Media/Alta/Ultra,
  y los estilos Natural/Bedrock/Vívido.
- **[Resource packs de Bedrock RTX](bedrock-rtx.md)** — usar un `.mcpack` de Bedrock, y leer archivos
  de una instalación de Bedrock en el mismo PC.
- **[Distant Horizons](distant-horizons.md)** — soporte experimental de terreno lejano.
- **[HDR](hdr.md)** — salida en pantallas HDR, brillo máximo y blanco de papel.
- **[Generación de fotogramas y NVIDIA Reflex](frame-generacion-reflex.md)** — DLSS o FSR, y el
  modo de baja latencia.
- **[Rendimiento](rendimiento.md)** — qué cuesta más FPS y cómo ajustarlo a tu GPU.
- **[Linux](linux.md)** — glibc, drivers, launchers en Flatpak y qué mirar si no arranca.
- **[Problemas comunes](problemas.md)** — pantallas de "no disponible", cambios de API gráfica, y
  qué hacer si algo no funciona.

## Para desarrolladores

- **[Compilar y publicar](compilar.md)** — Windows, Linux, GitHub Actions y cómo sale una versión.

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

- Trazado de rayos por hardware para el terreno: luz directa, sombras suaves e iluminación global,
  con hasta 8 rebotes y ReSTIR para las luces de bloque.
- Cielo físico, niebla volumétrica (rejilla de froxels estilo Radiance o por píxel), nubes
  volumétricas con sombras, lluvia con refracción y suelo mojado, vidrio sin bordes.
- LabPBR (`_n`/`_s`) para bloques, entidades e ítems; resource packs `.mcpack` de Bedrock RTX.
- FSR incluido; DLSS y XeSS descargables. Generación de fotogramas DLSS o FSR y NVIDIA Reflex.
- Salida HDR, desenfoque de movimiento y profundidad de campo.
- Distant Horizons experimental.
- Corre sobre el dispositivo Vulkan que Minecraft ya crea. El jar pesa unos 15 MB.

## Limitaciones conocidas (beta)

- Los pipelines con NRD (FSR, XeSS, nativo) tienen algo más de ruido que DLSS Ray Reconstruction.
- AMD e Intel funcionan, pero están menos probados que NVIDIA.
- Generación de fotogramas y Reflex están probados en Fabric; en NeoForge y Forge corren el mismo
  código sin probar todavía. La generación no está disponible con HDR.
- XeSS solo en Windows. Instalar DLSS no cambia el pipeline: hay que elegir RT-DLSS.
- Shader Execution Reordering es experimental (el agua puede verse blanca).
- Distant Horizons sigue siendo experimental: ver [distant-horizons.md](distant-horizons.md).
- El botón "Avanzado..." de los ajustes todavía no hace nada.

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
