[← Volver al índice](README.md)

# Generación de fotogramas y NVIDIA Reflex

Las dos están en [Calidad y Escalado](opciones/calidad-y-escalado.md). Desde la 0.5.0 no usan
Streamline: la generación de fotogramas corre nativa en su propio hilo de presentación y Reflex va
directo sobre `VK_NV_low_latency2`. Ninguna depende del loader ni pide reiniciar.

## Generación de Fotogramas

| | |
|---|---|
| Tipo | Auto · DLSS · FSR |
| Valores | Apagado · 2x · 3x · 4x (hasta lo que permita el tipo y la GPU) |
| Por defecto | Apagado |
| Se aplica | Al instante |
| No disponible | Con Salida HDR activada |

Genera fotogramas extra entre los que el motor renderiza. Más fluidez en un monitor de alta tasa
de refresco, a cambio de algo de latencia: los fotogramas generados no responden antes a tu ratón.

**Tipo de Generación de Fotogramas:**

- **DLSS**: solo NVIDIA con DLSS instalado. Hasta 4x en RTX 50; menos en series anteriores.
- **FSR**: cualquier GPU, con cualquier escalador (también con DLSS). Solo 2x.
- **Auto**: DLSS si la GPU lo soporta, FSR si no.

Un hilo de presentación reparte los fotogramas generados a lo largo de cada fotograma real. No
fuerza V-Sync: sigue siendo tu elección (activar la generación hace que Minecraft reconstruya la
swapchain una vez).

Probado en Fabric en Windows. NeoForge y Forge corren el mismo código, sin probar todavía.

![Contador de fotogramas generados](../images/frame-generation-contador.png)
<!-- TODO: el overlay F3 mostrando FPS reales vs. generados -->

## NVIDIA Reflex

| | |
|---|---|
| Requiere | GPU NVIDIA con `VK_NV_low_latency2` y `VK_KHR_present_id` (driver reciente) |
| Valores | Activado / Desactivado |
| Por defecto | Desactivado |
| Se aplica | Al instante |

Retiene la CPU para que cada fotograma empiece lo más tarde que la GPU permita, en vez de acumular
fotogramas en cola. Tus clics llegan antes a la pantalla. Se nota sobre todo con generación de
fotogramas activada. Funciona en Windows y Linux, con cualquier pipeline. En GPUs sin esas
extensiones el ajuste no aparece.

Generación de fotogramas y Reflex juntos es la combinación recomendada.
