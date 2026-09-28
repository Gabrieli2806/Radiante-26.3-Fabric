[← Volver al índice](README.md)

# Ajustes de Radiante

Se abre con `F6` en el juego. Los cambios se acumulan mientras la pantalla está abierta y se aplican
todos juntos al cerrarla con "Listo" (o al cambiar de categoría/preset, que reabre la pantalla con
las mismas elecciones) — así el pipeline se reconstruye como máximo una vez por sesión de ajustes,
no una vez por clic.

![La pantalla de ajustes de Radiante](../images/opciones-pantalla-completa.png)
<!-- TODO: la pantalla completa, categoría Calidad y Escalado, con Calidad y Restablecer visibles -->

## La fila de arriba: Calidad y Restablecer valores

**Calidad** es un selector rápido — Baja / Media / Alta / Ultra / Personalizado — que ajusta de una
vez los controles que más cuestan en fotogramas por segundo: modo DLSS, muestreo de luces de bloque,
niebla volumétrica, nubes y distancia de renderizado. Si después tocas cualquiera de esos a mano, el
selector pasa solo a mostrar "Personalizado" — no hay una calidad "a medias". El detalle de qué pone
cada nivel exactamente está en [estilos-y-calidad.md](estilos-y-calidad.md#niveles-de-calidad).

**Restablecer valores** (el botón junto a Calidad) devuelve *todo* lo de esta pantalla a como viene
un install nuevo: no solo los sliders visibles, sino también los ajustes guardados del pipeline y
del shader pack que no tienen su propio control aquí todavía (ver
[opciones.md#el-botón-avanzado-y-lo-que-no-se-ve-todavía](#el-botón-avanzado-y-lo-que-no-se-ve-todavía)).
Es una vuelta a cero completa, no solo de la categoría que estés mirando.

## Categorías

Debajo de esa fila hay un selector de **Sección** que cambia qué categoría se muestra; el juego
recuerda cuál tenías abierta la última vez, incluso entre partidas. Cada una tiene su propia página
con la tabla completa de controles, su rango y su valor por defecto:

| Sección | Contenido, en corto |
|---|---|
| [Calidad y Escalado](opciones/calidad-y-escalado.md) | El pipeline (DLSS/NRD/FSR/XeSS), el modo DLSS, Generación de Fotogramas, Reflex. |
| [Imagen](opciones/imagen.md) | Estilo de Imagen, Mapeo de Tonos, saturación, exposición, HDR, desenfoque de movimiento, profundidad de campo. |
| [Iluminación](opciones/iluminacion.md) | Brillo de día/noche/emisión/objeto en mano, luz rebotada, luz del cielo, muestreo de luces de bloque. |
| [Cielo, Niebla y Agua](opciones/cielo-niebla-agua.md) | Atmósfera Java/Bedrock, nubes, niebla de bioma, niebla volumétrica, olas y turbidez del agua. |
| [Rendimiento](opciones/rendimiento.md) | Rebotes de luz, relieve de texturas (parallax), calidad de niebla volumétrica, hilos y lotes de construcción de chunks. |
| [Otros](opciones/otros.md) | Contorno de bloque, bordes de parallax transparentes, registro de depuración. |

Un control solo aparece si el pipeline o el shader pack activos lo tienen — por ejemplo, el Modo
DLSS y la Generación de Fotogramas solo se muestran con el preset RT-DLSS puesto, y "Relieve de
Texturas" (parallax) solo si el shader pack lo soporta. No es un error si algo de esta documentación
no aparece en tu pantalla; probablemente depende del preset o del shader pack que tengas activo.

## El botón "Avanzado..." y lo que no se ve todavía

Está deshabilitado a propósito: por ahora es un marcador de posición para una futura pantalla con
todos los ajustes del pipeline y del shader pack — atmósfera, dispersión atmosférica, estrellas,
curvas de mapeo de tonos completas, parámetros de NRD — para ajuste fino más allá de lo que cubren
estas seis categorías. Esos parámetros ya existen y se guardan, solo que hoy no tienen un control
aquí; ver [ROADMAP.md](../../ROADMAP.md#advanced-settings-menu). Mientras tanto, **Restablecer
valores** sí los toca a todos aunque no los veas, porque limpia el pipeline guardado entero, no solo
lo visible en pantalla.
