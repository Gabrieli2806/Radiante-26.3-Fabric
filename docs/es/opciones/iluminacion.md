[← Ajustes](../opciones.md) · [Índice](../README.md)

# Iluminación

![Categoría Iluminación](../../images/opciones-iluminacion.png)
<!-- TODO: la categoría completa, con los sliders de brillo día/noche/emisión/mano -->

| Control | Rango | Por defecto | Qué hace |
|---|---|---|---|
| **Brillo del Día** | 0% – 400% | 25% | Brillo de la luz del sol y del cielo de día, en porcentaje del ajustado por el shader pack. |
| **Brillo de la Noche** | 0% – 400% | 35% | Brillo de la luz de la luna y del cielo de noche. |
| **Brillo de Fuentes de Luz** | 0% – 400% | 12% | Brillo de los bloques que emiten luz: antorchas, lava, piedra luminosa, lámparas de redstone encendidas, etc. |
| **Brillo de Luz en la Mano** | 0% – 400% | 12% | Brillo de lo que llevas en la mano — antorcha, farol, cubo de lava — tanto su propio resplandor como la luz que proyecta a tu alrededor. Es un control separado del de Fuentes de Luz desde la versión 0.2.0; una configuración de antes de ese cambio sigue usando el valor de emisión hasta que la reajustes. |
| **Luz Rebotada** | 50% – 200% | 150% | Cuánta luz sigue después de rebotar en las superficies. Más alto llena cuartos desde una sola mancha de sol, al estilo Bedrock RTX; 100% es físicamente correcto. |
| **Luz del Cielo** | 50% – 400% | 180% | Cuánto ilumina el cielo abierto al mundo. Más alto aclara la sombra bajo edificios y árboles. |
| **Muestreo de Luz de Bloques** | Activado/Desactivado | Activado | Las superficies apuntan un rayo de sombra directo a un bloque de luz cercano en vez de esperar a que un rebote aleatorio lo encuentre. Mucho menos ruido de noche y en cuevas, con un coste pequeño. Necesita que el terreno tenga bloques con emisión detectada (ver "Emisión de Bloques" en [Rendimiento](rendimiento.md)). |
| **Luz del Objeto en Mano** | Activado/Desactivado | Activado | Una antorcha, linterna u otra fuente de luz sostenida en cualquier mano ilumina su entorno. La limitación conocida: la luz no se propaga mucho más allá de un radio pequeño alrededor de la mano — ver la lista de limitaciones en el [índice](../README.md#limitaciones-conocidas-alpha). |
| **Iluminación Pixelada** | Activado/Desactivado | Desactivado | Ajusta la luz y las sombras a la cuadrícula de píxeles de la textura: cada píxel de un bloque se ilumina como una baldosa plana, un aspecto retro y cuadrado, similar a la iluminación pixelada de BetterRTX. Desactivado, la luz cae suave sobre la superficie. |
| **Sombra en Primera Persona** | Activado/Desactivado | Activado | Dibuja al jugador para sombras y reflejos mientras la cámara está en primera persona. Minecraft omite al jugador entonces, así que sin esto no proyectas sombra ni apareces en el agua o el vidrio. |

## "Luz Rebotada" y "Luz del Cielo" también son parte del Estilo de Imagen

Estos dos sliders cambian junto con Saturación cuando eliges un Estilo de Imagen distinto de
Personalizado (ver [Imagen](imagen.md) y [estilos-y-calidad.md](../estilos-y-calidad.md)). Tocar
cualquiera de los dos a mano hace que el Estilo de Imagen pase a mostrar "Personalizado", igual que
pasa con el nivel de Calidad.
