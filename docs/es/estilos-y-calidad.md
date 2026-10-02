[← Volver al índice](README.md)

# Estilos de imagen y niveles de calidad

Radiante tiene dos selectores "de conjunto" que ajustan varios controles individuales a la vez. Son
independientes entre sí: **Calidad** afecta a lo que cuesta rendimiento, **Estilo de Imagen** afecta
a cómo se ve el color y la luz. Tocar cualquier control que cualquiera de los dos cubra, a mano,
hace que ese selector muestre "Personalizado" — no existe un estado "a medias" para ninguno.

## Niveles de calidad

El selector **Calidad**, arriba en la categoría [Calidad y Escalado](opciones/calidad-y-escalado.md),
ajusta de una vez los controles medidos como los que más cuestan en fotogramas por segundo:

| Nivel | Modo DLSS / de Escalado | Niebla Volumétrica | Nubes | Muestreo de Luz de Bloques | Distancia de Renderizado | Rebotes de Luz | Relieve de Texturas | Calidad de Niebla Vol. |
|---|---|---|---|---|---|---|---|---|
| **Baja** | Rendimiento Ultra | Apagada | Apagadas | Activado | 8 chunks | 2 | Desactivado | 8 muestras |
| **Media** | Rendimiento | Apagada | Vanilla | Activado | 12 chunks | 2 | Desactivado | 8 muestras |
| **Alta** | Equilibrado | Activada | Vanilla | Activado | 16 chunks | 3 | Activado | 16 muestras |
| **Ultra** | Calidad | Activada | Volumétricas | Activado | 24 chunks | 4 | Activado | 24 muestras |

El modo se aplica al escalador que uses: Modo DLSS con RT-DLSS, Modo de Escalado con FSR o XeSS.
Baja usa 2 rebotes, no 1: con uno solo los cuartos pierden la luz que rebota en paredes y techo y
se ven planos.

Estos números salieron de medir una misma escena con los mismos chunks cargados, usando el Modo
DLSS Rendimiento Ultra (~150 FPS) o Equilibrado (~80 FPS) como referencia para los ajustes del
shader pack:

- Modo DLSS: partiendo de Rendimiento Ultra como base, pasar a Rendimiento cuesta cerca de -45%
  FPS, y pasar a Calidad, cerca de -65%.
- Rebotes de luz: de 4 a 2 ronda +55% FPS; de 4 a 1, +130% — con poco cambio visible tanto en
  exteriores como en interiores.
- Muestreo de Luz de Bloques: -20% al desactivarlo (pero mucho más ruido de noche y en cuevas).
- Niebla volumétrica: -10% al apagarla; 32 muestras cuesta -30% más que 16.
- Distancia de renderizado: 24 chunks cuesta -30% más que 16.
- Relieve de texturas (parallax) y nubes: unos pocos puntos porcentuales cada uno.

Luz del objeto en mano y Sombra en Primera Persona no aparecen en la tabla porque no cambiaron nada
medible en esas pruebas — se dejan fuera del selector de Calidad a propósito.

En un install nuevo el selector puede mostrar **Personalizado**: los valores por defecto no
coinciden exactamente con ningún nivel. No es un fallo; elige un nivel una vez.

## Estilos de imagen

El selector **Estilo de Imagen**, en [Imagen](opciones/imagen.md), ajusta Saturación, Luz Rebotada y
Luz del Cielo (y el método de Mapeo de Tonos) a la vez:

| Estilo | Mapeo de Tonos | Saturación | Luz Rebotada | Luz del Cielo |
|---|---|---|---|---|
| **Natural** | PBR Neutral | 100% | 100% | 100% |
| **Bedrock** | PBR Neutral | 120% | 150% | 180% |
| **Vívido** | ACES | 130% | 150% | 150% |

- **Natural** — luz y color físicamente correctos, sin ningún ajuste encima. Es la referencia neutra.
- **Bedrock** — ajustado para parecerse al look de Bedrock RTX: más saturado, con sombras y luz
  rebotada más claras que lo físicamente correcto, para que los cuartos se sientan más iluminados
  desde una sola fuente de luz.
- **Vívido** — más contraste (vía el mapeo de tonos ACES, más cinematográfico) y más color que
  Natural, pero sin llegar a la luz rebotada extra de Bedrock.

![Estilo Natural](../images/estilo-natural.png)
<!-- TODO: escena de día al aire libre, Estilo de Imagen = Natural -->

![Estilo Bedrock](../images/estilo-bedrock.png)
<!-- TODO: la misma escena y hora, Estilo de Imagen = Bedrock -->

![Estilo Vívido](../images/estilo-vivido.png)
<!-- TODO: la misma escena, Estilo de Imagen = Vívido -->

## Cómo se relacionan con "Atmósfera"

El Estilo de Imagen no cambia el ajuste **Atmósfera** (Java/Bedrock, en
[Cielo, Niebla y Agua](opciones/cielo-niebla-agua.md)) — son cosas independientes. Atmósfera decide
de dónde vienen los colores del cielo y la luz solar; Estilo de Imagen decide cómo se procesa el
color final en pantalla. Se pueden combinar libremente: por ejemplo, cielo Java con Estilo Bedrock,
o cielo Bedrock con Estilo Natural.

## Comparación rápida de calidad

![Comparación de niveles de calidad](../images/calidad-comparacion.png)
<!-- TODO: dos capturas, Baja vs. Ultra, misma escena y ángulo -->
