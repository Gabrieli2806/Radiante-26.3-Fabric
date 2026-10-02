[← Ajustes](../opciones.md) · [Índice](../README.md)

# Calidad y Escalado

![Categoría Calidad y Escalado](../../images/opciones-calidad.png)
<!-- TODO: la categoría completa: Pipeline, Modo de Escalado, Generación de Fotogramas, Reflex -->

| Control | Valores | Por defecto | Qué hace |
|---|---|---|---|
| **Pipeline** | RT-DLSS · RT-NRD · RT-NRD-FSR · RT-NRD-XeSS. Los no instalados salen como `(Instalar)` o `(Requiere reinicio)` | RT-DLSS si DLSS ya está instalado; si no, RT-NRD-FSR | Denoiser y escalador. Ver abajo y [Escaladores](../escaladores.md). |
| **Modo DLSS** | Calidad · Equilibrado · Rendimiento · Rendimiento Ultra | Rendimiento Ultra | Solo con RT-DLSS. Resolución interna antes de escalar. |
| **Modo de Escalado** | FSR: Rendimiento Ultra · Rendimiento · Equilibrado · Calidad · AA Nativo. XeSS añade Calidad Ultra y Calidad Ultra Plus | Calidad | Lo mismo que Modo DLSS, para FSR y XeSS. El nivel de Calidad también lo ajusta. |
| **Tipo de Generación de Fotogramas** | Auto · DLSS · FSR | Auto | Auto elige DLSS si la GPU lo soporta, FSR si no. FSR funciona con cualquier escalador pero solo duplica. |
| **Generación de Fotogramas** | Apagado · 2x … 4x | Apagado | Fotogramas extra entre los renderizados. Oculto con Salida HDR. Ver [frame-generacion-reflex.md](../frame-generacion-reflex.md). |
| **NVIDIA Reflex** | Activado / Desactivado | Desactivado | Baja latencia. Solo NVIDIA con `VK_NV_low_latency2`; oculto en otras GPUs. Sin reinicio. |

## Los pipelines

- **RT-DLSS (Reconstrucción de Rayos)**: DLSS hace denoising y escalado a la vez. El de menos
  ruido. Solo NVIDIA, se descarga en el juego.
- **RT-NRD-FSR**: denoiser NRD (RELAX) y FSR 3. Cualquier GPU, viene incluido. Es el valor por
  defecto si DLSS no está instalado.
- **RT-NRD-XeSS**: NRD y XeSS. Windows, se descarga en el juego. Recomendado en Intel Arc.
- **RT-NRD**: NRD sin escalador, a resolución nativa. Cualquier GPU; el más caro.

## Cambiar de pipeline no instala nada solo

Elegir `RT-DLSS (Instalar)` cambia la barra de abajo a **Instalar DLSS (115 MB)**. Instalar o
borrar se hace fuera de un mundo y termina en un reinicio. Después de instalar, hay que elegir
RT-DLSS: el pipeline no cambia solo. Ver [Escaladores](../escaladores.md).
