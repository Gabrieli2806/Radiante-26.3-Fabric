[← Ajustes](../opciones.md) · [Índice](../README.md)

# Cielo, Niebla y Agua

![Categoría Cielo, Niebla y Agua](../../images/opciones-cielo-agua.png)
<!-- TODO: la categoría con Nubes y Niebla Volumétrica activados -->

| Control | Rango / Valores | Por defecto | Qué hace |
|---|---|---|---|
| **Atmósfera** | Java · Bedrock | Bedrock | Bedrock: colores de cielo y luz solar de Bedrock RTX, leídos de una instalación de Bedrock en este PC (o de `radiante/bedrock/sky.png` y `look_up_tables.png`). Java: el cielo propio de Radiante. Sin archivos de Bedrock disponibles, se usa el cielo Java aunque esté puesto en Bedrock. Detalle completo en [bedrock-rtx.md](../bedrock-rtx.md). |
| **Nubes** | Apagado · Vanilla · Volumétrico | Vanilla | Cómo se dibujan las nubes. Volumétricas son nubes 3D reales que proyectan sombras y cuestan más tiempo de GPU; vanilla son más baratas (usan la malla de nube plana de siempre pero trazada e iluminada); apagado es lo más rápido. |
| **Resplandor del Sol** | 0% – 300% | 100% | El halo alrededor del sol. |
| **Rayos de Luz** | 0% – 400% | 100% | Haces de luz por ventanas, claraboyas y hojas. Necesita Niebla Volumétrica activada más abajo. |
| **Trayectoria Vanilla del Sol** | Activado/Desactivado | Activado | El sol y la luna siguen la trayectoria vanilla, directamente sobre la cabeza de este a oeste. Desactivado, la trayectoria se inclina diez grados al sur, así que las sombras del mediodía no caen totalmente rectas hacia abajo. |
| **Rotación Vanilla del Sol/Luna** | Activado/Desactivado | Activado | Mantiene los cuadrados del sol y la luna alineados con su trayectoria como en vanilla, en vez de girar al cruzar el cielo. |
| **Neblina de Bioma** | Activado/Desactivado | Activado | Neblina propia de cada bioma en cada dimensión: polvo cálido sobre desiertos, aire verde y denso sobre pantanos, neblina roja en bosques carmesí, neblina púrpura en el End. Se difumina entre biomas y no aparece en cuevas del Overworld. |
| **Intensidad de Neblina de Bioma** | 0% – 400% | 100% | Qué tan densa es esa neblina. 100% es el aspecto ajustado; no afecta al rendimiento. |
| **Niebla Volumétrica** | Activado/Desactivado | Activado (si el pipeline lo soporta) | Marcha por el aire con rayos, al estilo Bedrock RTX: la luz del sol y la luna se dispersan en la neblina de bioma, y árboles y terreno proyectan haces de luz a través de ella. Cuesta notablemente más tiempo de GPU que la neblina simple. La calidad (muestras por rayo) se ajusta en [Rendimiento](rendimiento.md). |
| **Intensidad de Niebla Volumétrica** | 0% – 400% | 100% | Qué tan densa es el aire que muestra los rayos de sol y de luz. No afecta al rendimiento — solo el número de muestras (en Rendimiento) lo hace. |
| **Olas del Agua** | 0% – 400% | 100% | Qué tan agitada es la superficie del agua. 0% es plana como un espejo. |
| **Turbidez del Agua** | 0% – 500% | 100% | Qué tan rápido se desvanece la vista bajo el agua. Menos es más claro. |
| **Rayos de Luz Bajo el Agua** | 0% – 400% | 100% | Haces de sol que atraviesan la superficie cuando estás bajo el agua. |

## Nether y End

La Neblina de Bioma también aplica en el Nether y el End, donde no hay cielo que la ilumine: ahí
usa el color de niebla vanilla de cada bioma (mezclado por vanilla igual que siempre) con un tinte
pequeño, y el End se aclara desde un negro casi puro para que no se vea completamente oscuro. La
Niebla Volumétrica, en cambio, solo se aplica en el Overworld; en el Nether y el End se usa siempre
la neblina simple, esté activada o no.
