[← Volver al índice](README.md)

# Linux

Radiante corre en Linux x86-64 desde la 0.5.0, con el mismo jar que Windows.

## Qué hace falta

- **glibc 2.35 o más reciente.** Ubuntu 22.04, Debian 12, Fedora 36, Arch, y cualquier
  distribución de esa época en adelante. Con una más vieja, el renderizador no carga
  (`GLIBC_2.xx not found` en el log).
- **El driver Vulkan real de tu GPU**: el propietario de NVIDIA, o Mesa (RADV para AMD, ANV para
  Intel) con soporte de trazado de rayos. Comprueba con `vulkaninfo --summary`: tu GPU tiene que
  aparecer, no `llvmpipe`.
- **Java 25** y un launcher que lo use.

DLSS funciona en Linux (se descarga igual que en Windows). XeSS no: Intel no publica su runtime
para Linux. Ver [Escaladores](escaladores.md).

## Launchers en Flatpak

Prism Launcher y otros launchers instalados como Flatpak corren en un sandbox. Si el juego cae a
`llvmpipe` o no ve tu GPU:

- En NVIDIA, el Flatpak necesita la extensión del driver que coincida **exactamente** con la
  versión instalada en el sistema (`org.freedesktop.Platform.GL.nvidia-<versión>`).
  `flatpak update` normalmente la instala.
- Comprueba dentro del sandbox: `flatpak run --command=vulkaninfo <id-del-launcher> --summary`.

## Wayland y X11

Las dos funcionan. Si ves problemas de presentación (pantalla negra, parpadeo al cambiar de
ventana), prueba a arrancar el launcher en X11/XWayland para descartar el compositor.

## Lista de comprobación si no arranca

1. `latest.log` contiene `[native]` con el mensaje del renderizador: es el primer sitio donde
   mirar.
2. `GLIBC_2.xx not found` → la distribución es demasiado vieja.
3. `UnsatisfiedLinkError` → el renderizador no se pudo descomprimir o cargar. Borra
   `libcore.so` de la carpeta `radiante/` del juego y vuelve a iniciar: se desempaqueta y comprueba
   de nuevo.
4. El log dice que no hay trazado de rayos o nombra `llvmpipe` → el juego no está usando el driver
   de tu GPU (ver Flatpak arriba).
5. `FSR3 CreateContext` falla → versión anterior a la 0.5.0, que tenía ese fallo en Linux.
   Actualiza.
6. Ves el mundo negro y el log no dice nada → comprueba que no hay otra instancia del juego
   abierta con otra versión del mod.

Más casos en [Problemas comunes](problemas.md).
