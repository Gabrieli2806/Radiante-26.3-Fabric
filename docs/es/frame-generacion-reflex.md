[← Volver al índice](README.md)

# Generación de fotogramas y NVIDIA Reflex

Dos funciones de NVIDIA, ambas en la categoría [Calidad y Escalado](opciones/calidad-y-escalado.md),
y ambas con la misma limitación por ahora: **solo funcionan en la versión de Fabric**. En NeoForge y
Forge, la carga de Streamline (la librería de NVIDIA que las habilita) hace fallar la creación del
dispositivo gráfico nativo, así que ahí ambas opciones están ocultas — ver
[ROADMAP.md](../../ROADMAP.md#frame-generation-and-reflex-on-forge--neoforge--investigate) para el
estado de esa investigación.

## Generación de Fotogramas

| | |
|---|---|
| Requiere | Pipeline = RT-DLSS, GPU NVIDIA, Fabric |
| Valores | Apagado · 2x · 3x … hasta el máximo que tu GPU reporte |
| Por defecto | Apagado |
| Se aplica | La primera vez que la activas, tras reiniciar el juego; los cambios después de eso, al instante |

DLSS genera fotogramas adicionales entre los que el motor realmente renderiza — con 2x, por cada
fotograma real ves uno generado de más; con más multiplicador, más fotogramas generados por cada
real. El resultado es movimiento más suave en un monitor de alta tasa de refresco, a costa de un
poco más de latencia de entrada (los fotogramas generados no reaccionan más rápido a tu ratón o
teclado, solo rellenan el movimiento entre los que sí lo hacen).

**Por qué el primer encendido pide reiniciar:** Streamline tiene que cargarse antes de que Minecraft
cree su dispositivo Vulkan, así que activar Generación de Fotogramas por primera vez en una sesión
no puede tomar efecto hasta el próximo inicio del juego. Una vez que Streamline ya está cargado,
subir o bajar el multiplicador sí se aplica al instante.

![Contador de fotogramas generados](../images/frame-generation-contador.png)
<!-- TODO: el overlay F3 mostrando FPS reales vs. generados -->

### Se recomienda con Reflex activado

Generar fotogramas añade algo de latencia de entrada; Reflex la reduce. Usar ambos juntos es lo que
NVIDIA recomienda para esta combinación, y es también el uso previsto en Radiante.

## NVIDIA Reflex

| | |
|---|---|
| Requiere | GPU NVIDIA, Fabric |
| Valores | Activado/Desactivado |
| Por defecto | Desactivado |
| Se aplica | La primera vez que lo activas, tras reiniciar el juego; después, al instante |

Modo de baja latencia: retiene la CPU para que cada fotograma empiece lo más tarde que la GPU
permita, en vez de adelantarse y acumular fotogramas en cola. El resultado es que tu clic o tu
movimiento de ratón llegan a la pantalla más rápido, sobre todo notable con Generación de Fotogramas
activada o con FPS ya altos donde la CPU va sobrada.

A diferencia de Generación de Fotogramas, Reflex no depende del Pipeline elegido — solo de tener una
GPU NVIDIA y estar en Fabric. Igual que Generación de Fotogramas, la primera vez que lo activas en
una sesión necesita un reinicio porque también depende de que Streamline se cargue antes de que
Minecraft cree su dispositivo Vulkan.
