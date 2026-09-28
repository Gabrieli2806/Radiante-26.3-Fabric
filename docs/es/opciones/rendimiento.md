[← Ajustes](../opciones.md) · [Índice](../README.md)

# Rendimiento (categoría de ajustes)

> Esta página es la referencia de los controles que viven en la categoría "Rendimiento" de la
> pantalla de ajustes. Si buscas consejos generales para ganar FPS (qué preset elegir, cómo se
> compara tu GPU, el coste de Distant Horizons), esa es una página distinta:
> **[rendimiento.md](../rendimiento.md)**, en la raíz de la documentación.

![Categoría Rendimiento](../../images/opciones-rendimiento.png)
<!-- TODO: Rebotes de Luz, Relieve de Texturas, Hilos/Lotes de Chunks -->

| Control | Rango | Por defecto | Qué hace |
|---|---|---|---|
| **Rebotes de Luz** | 1 – 4 | 4 | Cuántas veces rebota la luz en las superficies. Menos es mucho más rápido (de 4 a 2 ronda +55% FPS, de 4 a 1 ronda +130%, medido en una escena de referencia) y cambia poco visualmente; los cuartos iluminados solo por luz rebotada se ven algo más oscuros con menos rebotes. |
| **Relieve de Texturas** (parallax) | Activado/Desactivado | Activado | Profundidad en texturas de resource packs con mapas de altura — ladrillos y piedras resaltan. Desactivado: texturas planas y algo más rápido. Sin un pack con mapas de altura, este control no cambia nada visualmente. |
| **Calidad de Niebla Volumétrica** | 4 – 32 muestras | 16 | Muestras por rayo de la niebla volumétrica (ver [Cielo, Niebla y Agua](cielo-niebla-agua.md)). Menos es más rápido y algo más ruidoso; 32 cuesta cerca de un 30% más que 16. Solo aparece si tu pipeline soporta niebla volumétrica. |
| **Hilos de Chunks** | 1 – la mitad de tus núcleos lógicos (máximo) | La mitad de tus núcleos lógicos | Hilos de CPU que convierten los chunks cargados en geometría de trazado de rayos. Más hilos cargan las áreas nuevas más rápido al volar o teletransportarte, pero compiten con el juego (y, en un solo jugador, con el servidor integrado generando esos mismos chunks) por la CPU. Bájalo si el juego se traba al cargar terreno; súbelo si los chunks tardan visiblemente en aparecer. |
| **Tamaño de Lote de Chunks** | 1 – 64 | 12 | Cuántas secciones de chunk se envían a la GPU en una sola construcción de la estructura de aceleración. Más alto llena el mundo más rápido pero puede causar picos de tiempo de fotograma al cargar; más bajo es más suave pero tarda más en ponerse al día. |
| **Lotes de Chunks** | 1 – 64 | 12 | Cuántas de esas construcciones pueden estar en curso en la GPU a la vez. Más alto carga más rápido en GPUs potentes y usa más memoria de vídeo; bájalo si notas tirones o te quedas sin VRAM. |
| **Emisión de Bloques** | Activado/Desactivado | Activado | Los bloques que emiten luz (antorchas, lava, piedra luminosa…) iluminan el mundo a su alrededor. Desactivarlo ahorra algo de rendimiento, pero entonces las fuentes de luz solo brillan ellas mismas — necesario para que "Muestreo de Luz de Bloques" (en [Iluminación](iluminacion.md)) tenga algo que muestrear. |

## Por qué el máximo de Hilos de Chunks es la mitad de tus núcleos

No es un número arbitrario: en una CPU de 24 hilos, medir con 20 hilos de construcción bajó los FPS
de 120 a unos 65 durante varios segundos después de cada teletransporte, mientras que 4 hilos
mantuvo el juego por encima de 110 FPS y cargó el área igual de rápido. Más hilos de los que tu CPU
puede dar sin quitárselos al renderizado solo hacen que el juego vaya peor, no que cargue antes.
