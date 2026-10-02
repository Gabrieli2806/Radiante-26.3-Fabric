[← Volver al índice](README.md)

# Resource packs de Bedrock RTX

Radiante puede leer resource packs `.mcpack` hechos para **Minecraft Bedrock RTX** y convertirlos en
un pack LabPBR que su propio renderizador entiende, además de leer los archivos de cielo y cáusticas
de una instalación de Bedrock en el mismo PC. Ninguna de las dos cosas es necesaria — Radiante trae
sus propios mapas para 154 texturas vanilla — pero un pack de Bedrock suele dar más detalle y más
variedad de materiales que lo incluido.

## Usar un pack `.mcpack`

1. Coloca el archivo `.mcpack` directamente en la carpeta `resourcepacks` de Minecraft, igual que
   cualquier otro resource pack.
2. Ábrelo en el menú de resource packs del juego: Radiante lo detecta directamente en la lista, sin
   que tengas que descomprimirlo ni renombrarlo a `.zip` primero.
3. La primera vez que lo actives, Radiante lo convierte a un pack LabPBR y guarda el resultado en
   `radiante/bedrock_packs/` dentro de la carpeta del juego (no al lado del `.mcpack` original, para
   que no aparezca dos veces en la lista de packs). Las veces siguientes usa la copia convertida
   directamente, y solo vuelve a convertir si el `.mcpack` cambió o si una versión nueva del
   conversor lo requiere.

<!-- Captura pendiente: la lista de resource packs con un .mcpack visible y activable -->

### Qué convierte exactamente

Bedrock describe cada textura con un "texture set": una textura de color, un mapa MER(S)
(metalicidad, emisión, rugosidad y opcionalmente subsuperficie) y un mapa de altura o de normales.
Para cada textura de bloque de Java que tiene equivalente en Bedrock, el conversor construye:

- La textura de color (si es compatible en tamaño/formato con la de Java).
- Un mapa especular LabPBR (`_s.png`) a partir del MER — metalicidad, emisión, rugosidad y
  subsuperficie de Bedrock se traducen a los canales que LabPBR espera.
- Un mapa de normales LabPBR (`_n.png`), a partir del mapa de normales de Bedrock si lo trae, o
  derivado de su mapa de altura si no.

Los mapas más finos que la textura de color (96 px es común en packs de Bedrock, frente a 16 px de
Java) se conservan a su propio detalle cuando la textura de color puede escalarse a ese mismo tamaño
sin perder nitidez; si no, se promedian hacia abajo al tamaño de la textura de color. La niebla
volumétrica por bioma del pack de Bedrock también se convierte y se usa junto con la Niebla de
Bioma de Radiante (ver [Cielo, Niebla y Agua](opciones/cielo-niebla-agua.md)).

## Cielo y cáusticas de una instalación de Bedrock

Con el ajuste **Atmósfera** en "Bedrock" (categoría [Cielo, Niebla y Agua](opciones/cielo-niebla-agua.md)),
Radiante busca los archivos de cielo, tablas de consulta y cáusticas de Bedrock RTX en, por orden:

1. `radiante/bedrock/sky.png`, `look_up_tables.png` y `caustics.png` dentro de la carpeta del juego.
2. `C:/XboxGames/Minecraft for Windows/Content/data/ray_tracing/` — la ruta por defecto donde
   Minecraft Bedrock (versión de Xbox/Microsoft Store) instala esos mismos archivos, si tienes
   Bedrock instalado en el mismo PC.

Si no encuentra los archivos en ninguna de las dos rutas — o si `sky.png` no mide 64×256 o
`look_up_tables.png` no mide 128 de ancho, las dimensiones exactas que el shader espera — usa el
cielo propio de Radiante (el mismo que con Atmósfera en "Java") aunque el ajuste esté puesto en
Bedrock. Es un cambio silencioso, sin ningún aviso ni mensaje de error: el shader simplemente decide
que la textura de reemplazo de 1x1 no es un cielo de Bedrock válido y sigue con el suyo. Las
cáusticas funcionan igual y por separado: sin `caustics.png` disponible, se usan las cáusticas
procedurales de Radiante en vez de fallar.

<!-- Captura pendiente: el cielo con Atmósfera en Java y en Bedrock, misma hora del día -->

### Copiar los archivos a mano

Si no tienes Bedrock instalado en este PC pero sí tienes esos tres archivos de otra fuente,
colócalos en `radiante/bedrock/` dentro de la carpeta del juego, con esos nombres exactos:

```
<carpeta del juego>/radiante/bedrock/sky.png
<carpeta del juego>/radiante/bedrock/look_up_tables.png
<carpeta del juego>/radiante/bedrock/caustics.png
```

## Antes / después

<!-- Captura pendiente: la misma escena con un .mcpack activado y desactivado -->

## Ver también

- [estilos-y-calidad.md](estilos-y-calidad.md) — el Estilo de Imagen "Bedrock" ajusta saturación y
  luz rebotada para parecerse más a Bedrock RTX; es un ajuste de color independiente de usar un pack
  o los archivos de cielo de Bedrock.
- El estado de la paridad visual con Bedrock RTX (qué falta todavía, qué ya se igualó) se sigue en
  [ROADMAP.md](../../ROADMAP.md#bedrock-rtx-parity--in-progress), incluida la comparación hecha con
  Bedrock RTX lado a lado en los mismos mundos de prueba.
