[← Ajustes](../opciones.md) · [Índice](../README.md)

# Calidad y Escalado

![Categoría Calidad y Escalado](../../images/opciones-calidad.png)
<!-- TODO: la categoría completa: Pipeline, Modo DLSS, Generación de Fotogramas, Reflex -->

| Control | Valores | Por defecto | Qué hace |
|---|---|---|---|
| **Pipeline** | RT-DLSS (Reconstrucción de Rayos) · RT-NRD · RT-NRD-FSR · RT-NRD-XeSS — solo se listan los que tu GPU soporta | RT-DLSS si tu GPU lo soporta; si no, el mejor disponible | Qué combinación de denoiser y escalador usa el motor. Ver el detalle de cada uno abajo. |
| **Modo DLSS** | Calidad · Equilibrado · Rendimiento · Rendimiento Ultra | Rendimiento Ultra | Solo con Pipeline = RT-DLSS. Resolución interna a la que renderiza antes de escalar. Calidad se ve más nítido y cuesta más; Rendimiento y Rendimiento Ultra van más rápido pero se ven más suaves y engrosan los detalles finos. |
| **Generación de Fotogramas** | Apagado · 2x · 3x … hasta el máximo que reporte tu GPU | Apagado | Solo con Pipeline = RT-DLSS, y solo en Fabric. DLSS genera fotogramas extra entre los renderizados. Detalle completo en [frame-generacion-reflex.md](../frame-generacion-reflex.md). |
| **NVIDIA Reflex** | Activado/Desactivado | Desactivado | Solo GPUs NVIDIA. Modo de baja latencia. Detalle completo en [frame-generacion-reflex.md](../frame-generacion-reflex.md). |

## Los cuatro pipelines

- **RT-DLSS (Reconstrucción de Rayos)** — DLSS hace el denoising y el escalado a la vez ("Ray
  Reconstruction"). Es el camino recomendado en GPUs NVIDIA: menos ruido que los presets basados en
  NRD, y desbloquea Modo DLSS, Generación de Fotogramas y Reflex.
- **RT-NRD** — denoiser NRD (RELAX) sin ningún escalador: renderiza a la resolución nativa de la
  ventana. Funciona en cualquier GPU con trazado de rayos, no solo NVIDIA.
- **RT-NRD-FSR** — NRD más el escalador FSR 3 de AMD. FSR funciona en cualquier fabricante, no hace
  falta una GPU AMD para elegirlo.
- **RT-NRD-XeSS** — NRD más el escalador XeSS de Intel. Igual que FSR, funciona en cualquier
  fabricante.

Solo se ofrecen los pipelines que tu GPU y tu driver pueden ejecutar de verdad — si uno no aparece
en la lista, no es un paso de instalación que te falte (ver
[requisitos.md](../requisitos.md#lo-que-no-necesitas-descargar-aparte)).

## Por qué DLSS y los demás no se mezclan

DLSS es el denoiser y el escalador de un fabricante, juntos en un mismo paquete; ofrecerlo junto a
FSR o XeSS sugeriría que se pueden combinar, y no es así. Por eso Modo DLSS, Generación de
Fotogramas y Reflex solo aparecen cuando el Pipeline elegido es RT-DLSS.
