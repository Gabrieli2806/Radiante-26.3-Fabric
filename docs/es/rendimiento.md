[← Volver al índice](README.md)

# Guía de rendimiento

> Esta página son consejos generales para ganar FPS. Si buscas qué hace exactamente cada control de
> la categoría "Rendimiento" de la pantalla de ajustes, esa es una página distinta:
> **[opciones/rendimiento.md](opciones/rendimiento.md)**.

## Primero, usa el selector de Calidad

Antes de tocar controles individuales, prueba los niveles del selector **Calidad** en
[Calidad y Escalado](opciones/calidad-y-escalado.md) — Baja, Media, Alta, Ultra. Cada uno ajusta a
la vez los controles medidos como los que más cuestan (modo DLSS, muestreo de luces de bloque,
niebla volumétrica, nubes, distancia de renderizado, rebotes de luz y más), en combinaciones ya
probadas. El detalle exacto de qué pone cada nivel está en
[estilos-y-calidad.md](estilos-y-calidad.md#niveles-de-calidad).

## Los controles que más cuestan, de mayor a menor impacto

Medido en una escena de referencia, con el Modo DLSS Rendimiento Ultra (~150 FPS) o Equilibrado
(~80 FPS) como línea base:

1. **Modo DLSS** (o **Modo de Escalado** con FSR/XeSS) — el cambio más grande de todos.
   Partiendo de Rendimiento Ultra como base, pasar a Rendimiento cuesta cerca de -45% FPS, y pasar a
   Calidad, cerca de -65%. Es lo primero a tocar si necesitas más FPS ya.
2. **Rebotes de Luz** (categoría Rendimiento, 1 – 8) — de 4 a 2 ronda +55% FPS, con poco cambio
   visible. Más de 4 es para GPUs potentes. Si no quieres bajarlo para todo, usa **Distancia de
   Rebotes Lejanos**: solo el terreno lejano recibe menos rebotes.
   **Caché en Rebotes Profundos** (activado por defecto) ahorra mucho en zonas con muchas luces.
3. **Distancia de renderizado** — 24 chunks cuesta un 30% más que 16.
4. **Niebla Volumétrica** — un 10% al desactivarla; su "Calidad" (muestras por rayo) también pesa:
   32 muestras cuesta cerca de un 30% más que 16.
5. **Muestreo de Luz de Bloques** — un 20% al desactivarlo, pero mucho más ruido de noche y en
   cuevas; normalmente no vale la pena. **ReSTIR** reduce el ruido con muchas luces sin ese coste.
   **SER** (RTX 40+) puede ganar algo de rendimiento, pero es experimental.
6. **Relieve de Texturas** (parallax) y **Nubes** — unos pocos puntos porcentuales cada uno; solo
   importan si ya estás ajustando todo lo demás.

Luz del objeto en mano y Sombra en Primera Persona no cambiaron nada medible en estas pruebas —
no hace falta tocarlos por rendimiento.

## Si vas justo de VRAM

- Baja **Lotes de Chunks** (categoría Rendimiento) — menos construcciones de estructura de
  aceleración en curso a la vez en la GPU usan menos memoria de vídeo, a costa de cargar terreno
  algo más despacio.
- Baja la distancia de renderizado.
- Si usas Distant Horizons, recuerda que su propia distancia de renderizado también consume VRAM
  además de la del juego — ver [distant-horizons.md](distant-horizons.md).

## Si el juego se traba al cargar terreno nuevo (volar, teletransportarte)

- Sube **Hilos de Chunks** si tu CPU tiene margen — pero no más allá de la mitad de tus núcleos
  lógicos; más que eso le quita tiempo al hilo de renderizado en vez de ayudar. Ver la explicación
  completa en [opciones/rendimiento.md](opciones/rendimiento.md#por-qué-el-máximo-de-hilos-de-chunks-es-la-mitad-de-tus-núcleos).
- Baja **Tamaño de Lote de Chunks** si los tirones ocurren justo al construir chunks, no al
  cargarlos — un lote más pequeño reparte el trabajo en construcciones más cortas y frecuentes en
  vez de una grande que bloquea un fotograma entero.

## Generación de fotogramas como alternativa

Si ya tienes 60+ FPS reales y un monitor de 144 Hz o más, la
[generación de fotogramas](frame-generacion-reflex.md) da más fluidez sin tocar la calidad, a
cambio de algo de latencia que Reflex ayuda a compensar. FSR funciona en cualquier GPU (2x); DLSS
en NVIDIA (hasta 4x en RTX 50).

## Un punto de referencia conocido

Con el objetivo de que una GPU de gama media (por ejemplo RTX 3060 o RX 6700) sostenga 60 FPS a 12
chunks de distancia con DLSS o FSR, ese preset documentado todavía se está construyendo — ver
[ROADMAP.md](../../ROADMAP.md). Si tienes una GPU de esa gama y quieres compartir
tus propios números, el [Discord](https://discord.gg/DhBbAzugZ9) es el lugar.
