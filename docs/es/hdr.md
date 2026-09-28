[← Volver al índice](README.md)

# Salida HDR

Muestra el mundo trazado en HDR real en una pantalla HDR: el sol, la lava, las lámparas y los
reflejos brillan por encima del blanco, en vez de recortarse en blanco plano como en SDR. Los
controles están en la categoría [Imagen](opciones/imagen.md), al final.

## Requisitos

- Una pantalla que soporte HDR, con el **HDR activado en la configuración de pantalla de Windows**
  (no basta con que la pantalla lo soporte; Windows tiene que estar usándolo).
- Activar el ajuste "Salida HDR" en Radiante.

## Cómo activarlo

1. Activa HDR en Windows: Configuración → Sistema → Pantalla → HDR, y actívalo para tu monitor.
2. En Radiante, activa **Salida HDR** (categoría Imagen).
3. **Reinicia el juego.** El formato de la ventana se decide al crearla, así que el cambio no se
   aplica en caliente — si acabas de activarlo, el tooltip del ajuste te avisa de que está
   "activado, pero no en uso" hasta que reinicies.
4. Tras reiniciar, comprueba que el tooltip ya no avisa de nada pendiente; si sigue avisando,
   revisa que Windows realmente tenga HDR activo para ese monitor.

| Control | Rango | Por defecto | Se aplica |
|---|---|---|---|
| Salida HDR | Activado/Desactivado | Desactivado | Al reiniciar el juego |
| Brillo Máximo HDR | 400 – 4000 nits | 1000 nits | Al instante |
| Blanco de Papel HDR | 80 – 400 nits | 200 nits | Al instante |
| Vista de Depuración HDR | Activado/Desactivado | Desactivado (no se guarda) | Al instante |

## Brillo Máximo y Blanco de Papel

- **Brillo Máximo HDR** — lo más brillante que tu pantalla puede mostrar, en nits. Míralo en las
  especificaciones de tu monitor o en la calibración de HDR de Windows. Las luces intensas del
  mundo (el sol, la lava de cerca) se ajustan para no pasarse de este valor.
- **Blanco de Papel HDR** — qué tan brillantes se ven los menús, el texto y las superficies
  normales del mundo (lo que en SDR sería "blanco"), en nits. Súbelo en una habitación iluminada,
  bájalo en una oscura — es, en la práctica, el control de brillo general para todo lo que no es
  una fuente de luz intensa.

## Vista de Depuración HDR

Tiñe de azul todo lo que se muestra en rango SDR (menús, texto de la interfaz). Lo que conserva su
color normal es exactamente lo que se está mostrando en HDR real. Sirve para confirmar que el mundo
sí está usando el rango HDR y no solo pareciendo más brillante — no se guarda entre sesiones,
así que vuelve a desactivarse cada vez que abres el juego.

![Comparación con y sin HDR](../images/hdr-comparacion.png)
<!-- TODO: la misma escena con una fuente de luz intensa, HDR activado vs. desactivado. Una foto de
     pantalla o un GIF corto capturan la diferencia mejor que un PNG normal, que se muestra en SDR. -->
