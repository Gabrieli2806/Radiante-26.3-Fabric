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

## Cómo se une con el terreno cercano

Desde la 0.5.0 el terreno lejano no se vuelve a construir cuando cargan chunks cercanos. Se oculta
**por columna y por rayo** donde el terreno cercano ya está construido, como hace Vista en Radiance.
Una zona lejana solo cuenta como reemplazada cuando el renderizador ya dibuja lo que la reemplaza,
así que no quedan huecos al volar rápido.

## Qué esperar, siendo honestos

- **El coste crece con la distancia de Distant Horizons.** Cuanto más lejos genere DH, más traza la
  GPU.
- Las secciones de DH usan un color plano por cara, no texturas: la franja donde termina el terreno
  normal se nota.
- Sigue marcado como experimental.

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
