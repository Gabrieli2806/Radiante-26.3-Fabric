[← Ajustes](../opciones.md) · [Índice](../README.md)

# Iluminación

![Categoría Iluminación](../../images/opciones-iluminacion.png)
<!-- TODO: la categoría completa, con los sliders de brillo y los toggles de ReSTIR y SER -->

| Control | Rango | Por defecto | Qué hace |
|---|---|---|---|
| **Brillo del Día** | 0% – 400% | 25% | Luz del sol y del cielo de día. |
| **Brillo de la Noche** | 0% – 400% | 35% | Luz de la luna y del cielo de noche. |
| **Brillo de Fuentes de Luz** | 0% – 400% | 12% | Bloques que emiten luz: antorchas, lava, piedra luminosa… |
| **Brillo de Luz en la Mano** | 0% – 400% | 12% | Lo que llevas en la mano, su resplandor y la luz que proyecta. |
| **Luz Rebotada** | 50% – 200% | 150% | Cuánta luz sigue tras rebotar. 100% es físicamente correcto; más alto llena los cuartos como Bedrock RTX. |
| **Luz del Cielo** | 50% – 400% | 180% | Cuánto ilumina el cielo abierto. |
| **Muestreo de Luz de Bloques** | Activado / Desactivado | Activado | Cada superficie lanza un rayo de sombra directo a una luz cercana. Mucho menos ruido de noche y en cuevas. |
| **ReSTIR para Luces de Bloque** | Activado / Desactivado | Activado | Reutiliza la luz elegida por cada píxel entre fotogramas y vecinos. Mucho menos ruido en cuartos con muchas luces. Requiere Muestreo de Luz de Bloques. |
| **Reordenamiento de Ejecución (SER)** | Activado / Desactivado | Desactivado | Shader Execution Reordering: la GPU agrupa rayos parecidos. Solo RTX 40 o superior. Experimental: el agua puede verse blanca. |
| **Luz del Objeto en Mano** | Activado / Desactivado | Activado | Una antorcha o farol en cualquier mano ilumina alrededor. |
| **Distancia de Luz de Objetos** | 8 – 256 bloques | 64 | Hasta dónde iluminan los objetos tirados y las entidades que brillan. |
| **Iluminación Pixelada** | Activado / Desactivado | Desactivado | Luz y sombras ajustadas a los píxeles de la textura, estilo retro. |
| **Sombra en Primera Persona** | Activado / Desactivado | Activado | Dibuja al jugador para sombras y reflejos en primera persona. |

## Luz Rebotada y Luz del Cielo también son parte del Estilo de Imagen

Cambian con el Estilo de Imagen (ver [Imagen](imagen.md) y
[estilos-y-calidad.md](../estilos-y-calidad.md)). Tocarlas a mano lo deja en Personalizado.
