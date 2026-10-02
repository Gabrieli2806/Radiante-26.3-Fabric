[← Volver al índice](README.md)

# Escaladores: FSR, DLSS y XeSS

Radiante traza el mundo a una resolución más baja que la pantalla y un escalador lo lleva a la
resolución final. Desde la 0.5.0 solo FSR viene dentro del jar; DLSS y XeSS se descargan desde el
juego cuando los quieres. Así el jar pesa unos 15 MB en vez de 125 MB, y nadie descarga lo que su
GPU no puede usar.

| | FSR | DLSS | XeSS |
|---|---|---|---|
| Viene en el jar | Sí | No, ~115 MB | No, ~73 MB |
| GPU | Cualquiera | Solo NVIDIA | Windows; Intel Arc recomendado, otras GPUs según su driver |
| Sistema | Windows y Linux | Windows y Linux | Solo Windows |
| Origen | Incluido | Repositorio de NVIDIA (DLSS `v310.9.1`) | Repositorio de Intel (XeSS `3.0.2`) |
| Pipeline | RT-NRD-FSR | RT-DLSS (Ray Reconstruction) | RT-NRD-XeSS |
| Generación de fotogramas | FSR, 2x | DLSS, hasta 4x en RTX 50 | — |

## Primer inicio

FSR es el valor por defecto en todas las GPUs. Al llegar al menú principal por primera vez:

- **NVIDIA:** aparece una ventana ofreciendo DLSS.
- **Intel:** aparece una ventana ofreciendo XeSS (solo en Windows).
- **AMD:** no aparece nada; FSR es lo que hay.

La ventana tiene tres botones: **Descargar (N MB)**, **Ahora no** (vuelve a preguntar en el próximo
inicio) y **No volver a preguntar**. Mientras descarga se ve el progreso; **Continuar en segundo
plano** cierra la ventana y la descarga sigue.

![Oferta de descargar DLSS en el primer inicio](../images/ui/download-offer.png)

![Descarga de DLSS con barra de progreso](../images/ui/download-progress.png)

## Desde los ajustes

En [Calidad y Escalado](opciones/calidad-y-escalado.md), el selector **Pipeline** muestra los
escaladores que tu GPU puede usar aunque no estén instalados:

- `RT-DLSS (Instalar)`: se puede descargar.
- `RT-DLSS (Requiere reinicio)`: descargado, se carga al reiniciar.

Al elegir uno así, la barra de abajo cambia a **Instalar DLSS (115 MB)**, **Reiniciar para usar
DLSS** o **Borrar DLSS**, según el caso. Instalar y borrar se hacen **fuera de un mundo**: dentro
de una partida el botón pide salir primero. Los escaladores se cargan al iniciar el juego, así que
instalar o borrar siempre termina en un reinicio forzado.

![Selector de pipeline con (Install) y el botón Instalar DLSS](../images/ui/pipeline-install.png)

![Pantalla de reinicio obligatorio tras instalar o borrar un escalador](../images/ui/download-restart.png)

**Escaladores.** La misma vista con cada escalador. Los pipelines FSR y sin escalador también dibujan un cielo más nítido: compara las texturas.

*DLSS Quality*

![DLSS Quality](../images/compare/upscalers-dlss-quality.webp)

*DLSS Ultra Performance*

![DLSS Ultra Performance](../images/compare/upscalers-dlss-ultra-performance.webp)

*FSR 3*

![FSR 3](../images/compare/upscalers-fsr.webp)

*Sin escalador*

![Sin escalador](../images/compare/upscalers-none.webp)

## Después de instalar DLSS

Instalar DLSS lo deja disponible, pero **no cambia tu pipeline**. Si venías usando FSR, sigues en
FSR después del reinicio: elige **RT-DLSS** en el selector Pipeline para usarlo. La única
excepción es un install nuevo donde DLSS ya estaba descargado: ahí se elige RT-DLSS solo.

**Restablecer valores** elige RT-DLSS si DLSS está cargado y FSR si no.

## Dónde se guardan

En la carpeta `radiante/` del juego (junto a `mods/`). Cada archivo se comprueba contra su SHA-256
antes de usarse; uno que no coincide se descarta. Lo que rechazaste o borraste se recuerda en
`radiante/downloads.properties`, para no volver a preguntarlo.

Para quitar un escalador: **Borrar** en los ajustes. Los archivos en uso se borran en el próximo
inicio.

## Si la descarga falla

La ventana muestra `La descarga falló: <motivo>`. Lo habitual es un firewall, un proxy o no tener
conexión. FSR sigue funcionando mientras tanto; puedes reintentar desde los ajustes. Si el motivo
no es obvio, el `latest.log` tiene el detalle: ver [Problemas comunes](problemas.md).
