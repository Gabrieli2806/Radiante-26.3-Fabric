[← Volver al índice](README.md)

# Problemas comunes

## El juego está en OpenGL

Radiante necesita el renderizador Vulkan; el trazado de rayos no existe en OpenGL de ninguna forma.
Si el juego arranca en OpenGL verás un mensaje pidiendo cambiar la API Gráfica a "Preferir Vulkan" o
"Predeterminado" en las opciones de vídeo de Minecraft, y reiniciar.

Si Minecraft cambió a OpenGL solo (sin que tú lo pidieras) después de un arranque que no terminó
bien, es la propia protección de Minecraft: tras un inicio fallido, cambia la API preferida a OpenGL
por seguridad para la siguiente vez. Radiante no guarda ese cambio automático como si fuera tu
elección — solo cubre la sesión que protege — así que un solo fallo puntual no te deja con el
trazado de rayos apagado para siempre; vuelve a intentarlo.

## Mi GPU no soporta trazado de rayos

Verás la pantalla "Radiante: trazado de rayos no disponible". Significa que tu tarjeta gráfica o tu
driver no reportan soporte de `VK_KHR_ray_tracing_pipeline` — revisa
[requisitos.md](requisitos.md#sistema) para la lista de GPUs que sí lo soportan. Tienes dos opciones
en esa pantalla:

- **Continuar sin trazado de rayos** — el juego sigue funcionando con el renderizador propio de
  Minecraft, como si Radiante no estuviera instalado.
- **Cambiar a OpenGL** — no cambia nada respecto al trazado de rayos, porque tampoco existe ahí; es,
  en la práctica, la misma opción que la anterior.

Actualizar el driver de tu GPU a la versión más reciente de tu fabricante es lo primero a probar si
crees que tu tarjeta sí debería soportarlo.

## Un preset (DLSS, FSR o XeSS) no aparece en el selector de Pipeline

No es un paso de instalación que te falte — DLSS, FSR y XeSS vienen incluidos en el mod, no hay
nada que descargar aparte. Si un preset no aparece, tu GPU o tu driver no cumplen lo que ese preset
concreto necesita para inicializarse: DLSS solo en GPUs NVIDIA con driver reciente; FSR y XeSS
funcionan en cualquier fabricante, pero también necesitan que su módulo cargue correctamente. Ver
[requisitos.md](requisitos.md#lo-que-no-necesitas-descargar-aparte).

## Generación de Fotogramas o NVIDIA Reflex no aparecen

Dos motivos posibles, y solo uno tiene solución:

- **Estás en NeoForge o Forge.** Estas dos funciones solo están disponibles en Fabric por ahora —
  ver [frame-generacion-reflex.md](frame-generacion-reflex.md). No hay forma de activarlas en los
  otros dos loaders todavía.
- **Tu GPU no es NVIDIA.** Ambas son funciones exclusivas de NVIDIA (Streamline/DLSS); no hay
  equivalente de AMD o Intel disponible en Radiante.

## Activé HDR pero no veo ningún cambio

Repasa en orden:

1. ¿Reiniciaste el juego después de activar "Salida HDR"? El cambio no se aplica sin reiniciar.
2. ¿HDR está realmente activado en la configuración de pantalla de Windows para ese monitor, no
   solo soportado por él?
3. Después de reiniciar, revisa el tooltip del ajuste "Salida HDR" en la pantalla de Radiante — si
   sigue diciendo "activado, pero no en uso", el problema está en el paso 2, no en Radiante.

Más detalle en [hdr.md](hdr.md).

## Distant Horizons: costuras o parpadeo en el terreno lejano

Es un problema conocido y en ajuste activo, no un fallo de tu instalación — ver
[distant-horizons.md](distant-horizons.md#qué-esperar-siendo-honestos). Reducir la distancia de
renderizado de Distant Horizons (no la de Minecraft) suele hacerlo menos notorio mientras se sigue
trabajando en ello.

## El juego va lento / pocos FPS

Ver la [guía de rendimiento](rendimiento.md) completa. Resumen rápido: prueba primero el selector de
Calidad en Baja o Media; si sigue lento, el Modo DLSS (o el escalador equivalente) es lo que más
impacto tiene de todos los controles individuales.

## Quiero que un ajuste vuelva a como estaba al instalar

El botón **Restablecer valores**, junto al selector de Calidad en la pantalla de ajustes, devuelve
absolutamente todo — incluyendo ajustes del pipeline y del shader pack que no tienen su propio
control visible todavía — a los valores de un install nuevo. Más detalle en
[opciones.md#la-fila-de-arriba-calidad-y-restablecer-valores](opciones.md#la-fila-de-arriba-calidad-y-restablecer-valores).

## Nada de esto resolvió mi problema

- Revisa si ya está reportado o en trabajo en [ROADMAP.md](../../ROADMAP.md), en la raíz del
  repositorio — cubre bastantes casos conocidos con más detalle técnico del que cabe aquí.
- Si no, pregunta en el [Discord](https://discord.gg/DhBbAzugZ9) o abre un
  [issue en GitHub](https://github.com/Gabrieli2806/Radiante-26.3-Fabric/issues). Activa
  **Registro de Depuración** (categoría [Otros](opciones/otros.md)) antes de reproducir el problema
  — el log con ese ajuste activado suele tener el detalle que hace falta para diagnosticarlo.
