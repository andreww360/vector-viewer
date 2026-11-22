# AND Vector Viewer — visor de archivos vectoriales para Android

App nativa (Kotlin + Jetpack Compose) para abrir **AI, PDF, EPS, SVG, CDR, DXF y PLT** en el teléfono,
desde la memoria interna, SD/USB, Google Drive, OneDrive, Dropbox y cualquier proveedor de archivos.
Marca: **AND DSGN** · paquete `com.anddesign.vectorviewer`.

---

## 1. Cómo abrirla y probarla

1. Instala **Android Studio** (última versión estable) en Windows.
2. `File → Open…` y elige la carpeta `VectorViewer`.
3. Acepta la sincronización de Gradle. Si Studio ofrece actualizar AGP/Kotlin/Compose, **acepta**.
4. Activa *Depuración USB* en tu teléfono, conéctalo y presiona ▶ **Run**.
5. Para generar el paquete de Play Store: `Build → Generate Signed App Bundle (.aab)`.
   **Guarda la keystore en un lugar seguro**: sin ella no puedes publicar actualizaciones.

> El código se escribió y revisó sin compilar contra el SDK de Android. Es normal que la
> primera compilación pida algún ajuste menor (una importación, una versión). Pega el error
> en el chat y lo corregimos.

---

## 2. Qué hace hoy (v0.1)

| Formato | Cómo se ve | Medición real | Notas |
|---|---|---|---|
| **AI** (CS en adelante) | ✅ Vector real | ✅ | Usa la parte "compatible con PDF". Cada mesa de trabajo = una página. |
| **PDF** | ✅ Vector real | ✅ | Multipágina. |
| **SVG / SVGZ** | ✅ Vector real | ✅ | Unidades mm/cm/px respetadas. |
| **DXF** (ASCII) | ✅ Vector real | ✅ | LINE, CIRCLE, ARC, ELLIPSE, polilíneas con arcos, SPLINE. |
| **PLT / HPGL** | ✅ Vector real | ✅ | El archivo que va al plotter de corte: revisa el corte antes de mandarlo. |
| **EPS** | 🟡 Vista previa incrustada (TIFF/EPSI/Photoshop) | ✅ (BoundingBox) | Vector completo en fase 2 (motor PostScript). |
| **AI antiguo** (≤ 8) | 🟡 Igual que EPS | ✅ | |
| **CDR** | 🟡 Miniatura incrustada | ❌ | Vector completo en fase 2 (libcdr). |

**Funciones del visor**
- Zoom con pellizco y doble toque; la zona visible se re-renderiza a resolución de pantalla, así que el zoom **nunca se pixela** en AI/PDF/SVG/DXF/PLT.
- **Herramienta de medición a escala real** (mm / cm / in): distancia y Δx/Δy entre dos puntos. Ideal para revisar plantillas de moto y PPF.
- Tamaño de la mesa de trabajo siempre visible; toca la píldora para cambiar de unidad.
- **Fondo** blanco, cuadriculado (muestra transparencias de vinilo/PPF) u oscuro.
- Navegación entre páginas / mesas de trabajo.
- **Exportar PNG** transparente a 300 o 600 dpi y compartir (WhatsApp, correo, Drive…).
- Compartir el archivo original o abrirlo en otra app.
- Pantalla de información: formato, peso, páginas, tamaño, motor usado.

**Biblioteca**
- Orígenes: Teléfono, Google Drive, OneDrive, Explorar carpeta (Dropbox, Box, MEGA, USB OTG aparecen en el selector).
- **Galería de carpeta** con miniaturas, búsqueda y orden por nombre/fecha; navegación por subcarpetas.
- Recientes, **favoritos** y carpetas guardadas. Si el archivo de la nube deja de estar accesible se reabre desde la copia local.
- Aparece en **"Abrir con…"** y **"Compartir con…"** de cualquier app.
- Interfaz en español e inglés.

---

## 3. Arquitectura

```
app/src/main/java/com/anddesign/vectorviewer/
├── MainActivity.kt          Navegación por pila + intents "Abrir con / Compartir"
├── data/LibraryRepository   Recientes, favoritos, carpetas, preferencias
├── format/                  Detección por "magic bytes" (no confía solo en la extensión)
├── render/
│   ├── VectorDocument       Interfaz común: tamaño en puntos + render(bitmap, matriz)
│   ├── PdfVectorDocument    PDF + AI (PdfRenderer del sistema = PDFium)
│   ├── SvgVectorDocument    AndroidSVG
│   ├── PathDocument         DXF / PLT como trazos
│   ├── PreviewImageDocument EPS / CDR (vista previa)
│   ├── DocumentLoader       Copia a caché + detección + apertura
│   └── ThumbnailCache       Miniaturas memoria + disco
├── parsers/                 EPS, TIFF, CDR, DXF, HPGL
└── ui/                      Compose: inicio, carpeta, visor
```

Para agregar un formato nuevo: crea una clase que implemente `VectorDocument` y regístrala en
`DocumentLoader.open()`. El visor, la medición, las miniaturas y la exportación funcionan solos.

---

## 4. Nube: por qué así

**Fase 1 (incluida): Storage Access Framework (SAF).** Google Drive, OneDrive, Dropbox, Box, etc.
se integran como "proveedores de documentos" del sistema. Ventajas enormes para vender la app:
- Cero credenciales, cero servidores, cero costos.
- **Sin permisos de almacenamiento**: Play Store restringe mucho `MANAGE_EXTERNAL_STORAGE`; con SAF no lo necesitas.
- Funciona con cualquier nube que el usuario tenga instalada.

**Fase 2 (opcional, Pro): navegación integrada dentro de la app.**
- **OneDrive / SharePoint** → Microsoft Graph + MSAL, permiso `Files.Read`. Registro gratuito en Azure.
- **Google Drive** → Drive API v3. Ojo: listar *todo* el Drive (`drive.readonly`) es un permiso
  **restringido** que exige una auditoría de seguridad anual (CASA) de pago. Alternativa sin
  auditoría: permiso `drive.file` + selector de Google (solo los archivos que el usuario elige).
- **Dropbox** → Dropbox API v2 (sin auditoría).

Recomendación: lanzar con SAF y agregar OneDrive/Dropbox integrados en una actualización Pro.

---

## 5. Hoja de ruta y mejoras sugeridas

### Fase 2 — Motores completos (lo que más valor agrega)
- **CDR vectorial real** con *libcdr* (MPL 2.0, permite uso comercial) compilado con el NDK.
- **EPS / AI antiguo vectorial** con *Ghostscript* (ver licencias abajo).
- Lista de **capas** de AI/PDF (Optional Content) con mostrar/ocultar.
- DXF: bloques (INSERT), textos y colores por capa; DXF binario.

### Funciones pensadas para gráficas de moto, vinilo y PPF
- **Área total de material (m²)** y perímetro de corte → cotizar vinilo/PPF desde el teléfono.
- **Modo línea de corte**: resaltar trazos de contorno (CutContour / Thru-cut) y detectar trazos abiertos.
- **Lista de colores**: tintas planas, Pantone y CMYK usados en el archivo.
- **Detector de texto sin trazar** (fuentes no convertidas a curvas) antes de mandar a imprimir.
- **Vista "sobre la moto"**: superponer el diseño sobre una foto/mockup para mostrárselo al cliente.
- **Aprobación de cliente**: compartir un enlace/PNG con marca de agua y botón "Aprobado".
- **Integración con mx-templates.com**: buscar plantillas por marca/modelo/año y abrirlas directo.
- Regla y cuadrícula en mm; zoom 1:1 a tamaño real en pantalla (calibrable).

### Productividad
- Comparar dos versiones lado a lado / superpuestas.
- Exportar a PDF y SVG; imprimir (Android Print Framework).
- Anotaciones y marcas encima del diseño.
- Widget de "últimos archivos" y accesos directos.
- Modo tablet con dos paneles; soporte de lápiz (S Pen).

---

## 6. Monetización sugerida

**Freemium con pago único "Pro" (Google Play Billing)** — los usuarios de herramientas de diseño
prefieren pagar una vez antes que suscribirse:
- **Gratis**: ver AI, PDF, SVG, DXF, PLT; vista previa EPS/CDR; recientes.
- **Pro (pago único)**: CDR y EPS vectorial completo, medición, exportación 600 dpi, carpetas con
  miniaturas, nubes integradas, área de material, comparar versiones.
- Opcional: suscripción "Studio" solo si agregas servicios con costo (sincronización, aprobación de cliente en la nube).

---

## 7. Checklist para publicar en Play Store

- [ ] Cuenta de desarrollador de Google Play (pago único de USD 25).
- [ ] Si la cuenta es **personal** y nueva: prueba cerrada con **mínimo 12 testers durante 14 días seguidos** antes de pedir acceso a producción. Las cuentas de **organización** están exentas (requieren número D-U-N-S).
- [ ] Política de privacidad publicada (puede ir en mx-templates.com). La app no recopila datos.
- [ ] Formulario "Seguridad de los datos": sin datos recopilados ni compartidos (fase 1).
- [ ] Ícono 512×512, gráfico destacado 1024×500, capturas de teléfono y tablet.
- [ ] Reemplazar el ícono provisorio por el logo AND DSGN (`res/drawable/ic_launcher_foreground.xml`).
- [ ] Ficha en español e inglés; palabras clave: "ai viewer", "eps viewer", "cdr viewer", "visor illustrator", "dxf viewer".
- [ ] `targetSdk` actualizado (hoy 36) y App Bundle firmado.

---

## 8. Licencias — importante para vender

| Componente | Licencia | ¿Se puede vender en app cerrada? |
|---|---|---|
| PdfRenderer (Android) | Parte del sistema | ✅ |
| AndroidSVG | Apache 2.0 | ✅ (incluir aviso) |
| libcdr / librevenge | MPL 2.0 | ✅ (publicar cambios a *esos* archivos) |
| **Ghostscript** | **AGPL** | ❌ salvo **licencia comercial de Artifex** |
| **MuPDF** | **AGPL** | ❌ salvo licencia comercial de Artifex |

Para EPS vectorial: o compras licencia de Ghostscript, o mantienes la vista previa incrustada
(gratis), o escribes un intérprete PostScript reducido (gran esfuerzo).

**Marcas**: no uses los logos de Adobe, Corel, Google ni Microsoft en el ícono o capturas de la
tienda; nombrar los formatos (".ai", ".cdr") en la descripción está bien.
