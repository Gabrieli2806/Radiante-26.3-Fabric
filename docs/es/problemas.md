[← Volver al índice](README.md)

# Problemas comunes

## Antes de nada: el log

El **Registro de Depuración** viene activado por defecto, y los mensajes del renderizador nativo
llegan a `logs/latest.log` como líneas `[native] ...` (los errores siempre, aunque lo apagues).
Para reportar un problema basta con ese archivo. Busca primero `[native]` y `Radiante`.

## El juego está en OpenGL

Radiante necesita Vulkan. Si el juego arranca en OpenGL, un mensaje pide cambiar la API Gráfica a
"Preferir Vulkan" o "Predeterminado" en las opciones de vídeo y reiniciar.

Si Minecraft cambió a OpenGL solo tras un arranque fallido, es su propia protección. Radiante no
guarda ese cambio como tu elección: solo dura esa sesión. Vuelve a intentarlo.

## Mi GPU no soporta trazado de rayos

Pantalla "Radiante: trazado de rayos no disponible": la GPU o el driver no reportan
`VK_KHR_ray_tracing_pipeline`. Revisa los [requisitos](requisitos.md#gpu) y actualiza el driver.
También pasa con Vulkan por software (`llvmpipe`), típico en Linux cuando el juego no ve el driver
real: ver [Linux](linux.md). Puedes seguir sin trazado de rayos con el renderizador de Minecraft.

## Mundo negro

- **Linux con FSR, en versiones anteriores a la 0.5.0**: FSR no se podía crear
  (`FSR3 CreateContext` en el log). Arreglado en 0.5.0.
- Busca `[native]` en el log: el renderizador dice qué falló, y un error repetido cada fotograma
  aparece una vez con su número de repeticiones.

## DLSS o XeSS no aparecen en el selector de Pipeline

- **DLSS** solo se ofrece en GPUs NVIDIA. Si no está instalado aparece como `RT-DLSS (Instalar)`.
- **XeSS** solo existe en Windows. En GPUs que no son Intel puede instalarse y aun así no quedar
  disponible, si el driver no pasa la comprobación propia de XeSS.
- Si lo instalaste, falta reiniciar: aparece como `(Requiere reinicio)`.

Ver [Escaladores](escaladores.md).

## Instalé DLSS y sigo en FSR

Es lo esperado: instalar no cambia tu pipeline. Elige **RT-DLSS** en el selector Pipeline.

## El botón de instalar pide salir del mundo

Instalar o borrar un escalador se hace desde los menús, y termina en un reinicio. Sal al menú
principal y abre los ajustes de Radiante desde Opciones → Ajustes de vídeo.

## La descarga falló

Normalmente es red: firewall, proxy, sin conexión. FSR sigue funcionando. Reintenta desde los
ajustes. Un archivo descargado que no coincide con su SHA-256 se descarta.

## No aparece Generación de Fotogramas o Reflex

- **Generación de Fotogramas** se oculta con **Salida HDR** activada.
- **Reflex** solo aparece en GPUs NVIDIA con `VK_NV_low_latency2` y `VK_KHR_present_id`.
  Actualiza el driver.

Ver [Generación de fotogramas y Reflex](frame-generacion-reflex.md).

## Activé HDR y no veo cambios

1. ¿Reiniciaste el juego? Salida HDR lo necesita.
2. ¿HDR está activado en el sistema para ese monitor, no solo soportado?
3. Si el tooltip de "Salida HDR" dice "activado, pero no en uso", el problema es el punto 2.

Ver [HDR](hdr.md).

## Linux: no carga el renderizador

`GLIBC_2.xx not found`, `UnsatisfiedLinkError`, `llvmpipe`: lista completa en [Linux](linux.md).

## Distant Horizons: terreno lejano raro

Ver [Distant Horizons](distant-horizons.md). Si algo falla, reduce la distancia de DH (no la de
Minecraft) y comprueba si sigue pasando.

## Va lento

Ver la [guía de rendimiento](rendimiento.md). Lo primero: el nivel de Calidad, y después el modo
del escalador y Rebotes de Luz.

## Quiero volver a los valores de un install nuevo

**Restablecer valores**, junto al selector de Calidad. Devuelve todo, incluidos los ajustes del
pipeline sin control visible. El pipeline vuelve a RT-DLSS si DLSS está cargado, a FSR si no. Ver
[Ajustes](opciones.md).

## Nada de esto funcionó

Pregunta en [Discord](https://discord.gg/DhBbAzugZ9) o abre un
[issue](https://github.com/Gabrieli2806/Radiante-26.3-Fabric/issues) con tu `latest.log`, tu GPU,
tu sistema y el loader. Revisa antes el [ROADMAP](../../ROADMAP.md) por si ya es conocido.
