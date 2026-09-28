[← Volver al índice](README.md)

# Distant Horizons (experimental)

[Distant Horizons](https://modrinth.com/mod/distanthorizons) genera y muestra terreno mucho más allá
de la distancia de renderizado normal de Minecraft, a un nivel de detalle cada vez más bajo cuanto
más lejos está. Radiante puede trazar ese terreno lejano junto con el resto del mundo, en vez de
dejarlo fuera del trazado de rayos.

Es completamente opcional: sin Distant Horizons instalado, nada cambia. Con él instalado y activo,
Radiante lo detecta solo — no hay un ajuste que activar en la pantalla de Radiante.

## Cómo se ve el terreno lejano

El terreno de Distant Horizons se simplifica a una sección por cara de bloque con un solo color
(en vez de la geometría con textura completa que usa el terreno cercano), y se traza igual que
cualquier otra sección del mundo: recibe luz, proyecta sombra, y aparece en reflejos. Cuanto más
lejos, más simplificadas son las secciones que DH le da a Radiante para trazar.

![Terreno lejano de Distant Horizons](../images/distant-horizons-terreno-lejano.png)
<!-- TODO: terreno cercano con RT completo y terreno lejano de DH visiblemente más simple -->

## Qué esperar, siendo honestos

Esto sigue en ajuste activo. Cosas que puedes notar:

- **Alguna costura o parpadeo** donde el terreno lejano cambia de nivel de detalle (una sección
  grande y simple se reemplaza por sus hijas más finas, o al revés), especialmente en áreas de
  hasta 2048 bloques.
- **El coste por fotograma crece con la distancia de renderizado de Distant Horizons.** Cuanto más
  lejos configures que DH genere, más le cuesta a la GPU trazarlo también. El coste medido a la
  distancia por defecto de DH (512 chunks) en un mundo completamente generado todavía se está
  midiendo — ver [ROADMAP.md](../../ROADMAP.md#distant-horizons-far-terrain--implemented-pending-in-game-tuning).
- El detalle con textura de las secciones más cercanas de DH (justo donde termina el terreno
  normal) todavía no está implementado — esa franja se ve con el color plano por cara, no con
  texturas.

Nada de esto rompe el juego; son limitaciones visuales conocidas de una función marcada como
experimental.

## Si sales del juego mientras Distant Horizons sigue generando

Si cierras el mundo o el juego mientras DH todavía está generando terreno, sus propios hilos de
generación pueden quedar esperando datos de un servidor que ya se detuvo, lo que sin más tardaría en
cerrarse solo hasta que el watchdog de apagado de Minecraft lo mate con un reporte de fallo — esto
pasa también sin trazado de rayos, es un comportamiento de Distant Horizons, no de Radiante.
Radiante ayuda a que el proceso se cierre en cuanto detecta que solo quedan hilos de DH esperando,
para no necesitar el watchdog. No hay ninguna acción que tengas que tomar tú.

## Compatibilidad probada

Verificado en un mundo de prueba nuevo, tanto en Fabric como en NeoForge: de día, al atardecer, de
noche, con lluvia, con nubes, bajo el agua, desde 70 hasta 4000 bloques de altura, alternando el
trazado de rayos encendido/apagado en un mundo ya cargado, saliendo y volviendo a entrar, y
teletransportándose 4000 bloques.
