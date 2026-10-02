# Capturas pendientes

Esta carpeta guarda las imágenes que usan los documentos en `docs/es/` y `docs/en/`. Cada documento
referencia un archivo aquí con una ruta relativa (`../images/nombre.png`) y dice, justo encima, qué
debería mostrar la captura. Mientras el archivo no exista, el enlace de la imagen simplemente no
carga — no rompe nada, así que se pueden ir añadiendo de a una.

Lista de capturas que los documentos esperan (nombre exacto de archivo, documento que la usa):

| Archivo | Usada en | Qué debe mostrar |
|---|---|---|
| `opciones-pantalla-completa.png` | `opciones.md` | La pantalla de ajustes de Radiante abierta, con la caja de búsqueda, el desplegable de Calidad, "Restablecer valores" y una etiqueta de impacto visibles. |
| `opciones-calidad.png` | `opciones/calidad-y-escalado.md` | La categoría "Calidad y Escalado": Pipeline, Modo de Escalado, Tipo de Generación de Fotogramas, Generación de Fotogramas, NVIDIA Reflex. |
| `opciones-imagen.png` | `opciones/imagen.md` | La categoría "Imagen": Estilo de Imagen, Mapeo de Tonos, Saturación, y la sección de HDR expandida (con los sliders de brillo). |
| `opciones-iluminacion.png` | `opciones/iluminacion.md` | La categoría "Iluminación" completa, con los sliders de brillo y los toggles de ReSTIR y SER. |
| `opciones-cielo-agua.png` | `opciones/cielo-niebla-agua.md` | La categoría "Cielo, Niebla y Agua", con Nubes Volumétricas, Sombras de Nubes y Estilo de Niebla visibles. |
| `opciones-rendimiento.png` | `opciones/rendimiento.md` | La categoría "Rendimiento": Rebotes de Luz (1-8), Rebotes Lejanos, Caché en Rebotes Profundos, Hilos/Lotes de Chunks. |
| `opciones-otros.png` | `opciones/otros.md` | La categoría "Otros": Contorno del Bloque, Bordes de Parallax, Registro de Depuración. |
| `estilo-natural.png` | `estilos-y-calidad.md` | Una escena de día al aire libre con el Estilo de Imagen en Natural. |
| `estilo-bedrock.png` | `estilos-y-calidad.md` | La misma escena y hora, con el Estilo de Imagen en Bedrock — para comparar saturación y luz rebotada. |
| `estilo-vivido.png` | `estilos-y-calidad.md` | La misma escena, con el Estilo de Imagen en Vívido. |
| `calidad-comparacion.png` | `estilos-y-calidad.md` | Un collage o par de capturas Baja vs. Ultra en la misma escena, mismo ángulo. |
| `bedrock-antes-despues.png` | `bedrock-rtx.md` | Comparación lado a lado: la misma escena con un `.mcpack` de Bedrock activado y desactivado. |
| `bedrock-lista-de-packs.png` | `bedrock-rtx.md` | La lista de resource packs de Minecraft mostrando un `.mcpack` detectado directamente en la lista. |
| `atmosfera-java-vs-bedrock.png` | `bedrock-rtx.md` | Comparación del cielo con "Atmósfera" en Java vs. en Bedrock. |
| `distant-horizons-terreno-lejano.png` | `distant-horizons.md` | Una vista con Distant Horizons activo: terreno cercano con ray tracing completo y terreno lejano de DH visiblemente más simple, mostrando la transición. |
| `hdr-comparacion.png` | `hdr.md` | Una escena con una fuente de luz intensa (el sol o lava), fotografiada de la pantalla con HDR activado — difícil de capturar fielmente con una captura SDR normal; una foto de pantalla o un GIF cortos son mejores que un PNG aquí. |
| `frame-generation-contador.png` | `frame-generacion-reflex.md` | El overlay F3 con el contador de FPS mostrando fotogramas reales vs. generados. |
| `pantalla-rt-no-disponible.png` | `problemas.md` | La pantalla que aparece cuando la GPU no soporta trazado de rayos por hardware ("Radiante: trazado de rayos no disponible"). |
| `descarga-dlss.png` | `escaladores.md` | La ventana "NVIDIA DLSS" del primer inicio, con la barra de progreso a mitad. |
| `pipeline-instalar.png` | `escaladores.md` | El selector Pipeline mostrando `RT-DLSS (Instalar)` y el botón "Instalar DLSS (115 MB)" abajo. |
| `logo-radiante.png` | `README.md` (índice) | El icono/logo de Radiante, para la cabecera del índice. |

Formato sugerido: PNG a la resolución nativa de la ventana, sin escalar. Para comparaciones
antes/después, un solo PNG con las dos mitades es más fácil de mantener que dos archivos.
