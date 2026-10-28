# Bitácora de Desarrollo — Free My TV

> **Propósito:** Registro cronológico de las implementaciones, decisiones, intentos fallidos y verificaciones realizadas para añadir nombres personalizables, nuevas fuentes de imágenes del protector de pantalla y fondos de tema.
>
> **Alcance:** Esta bitácora cubre el trabajo descrito en las conversaciones de desarrollo y las pruebas en emulador y en la TV física Caixun Sgamer. No es un registro de todas las funciones históricas de Free My TV ni sustituye las notas de una publicación.

---

## 1. Requisitos y decisiones de alcance

### Problema

Se solicitó personalizar el encabezado «Para ti», ampliar las fuentes de imágenes del protector de pantalla y permitir imágenes locales como fondo del tema, conservando la interfaz Canvas y la navegación por control remoto existentes.

### Hallazgos

- La interfaz Android dibuja sus pantallas con Canvas y navega mediante DPAD; las nuevas páginas debían seguir el patrón de ajustes existente.
- Los nombres se administrarían individualmente desde Ajustes, sin reemplazar el texto de reserva cuando no hubiera nombres.
- Para imágenes nuevas se necesitaban opciones fijas y aleatorias con intervalo diario o minutos configurables.
- La fuente TMDB debía conservar el comportamiento que ya tenía.
- Las imágenes de Bing debían consultarse con configuración regional española y retenerse localmente.

### Alcance del cambio

- Toca: aplicación Android; preferencias, páginas de ajustes, pantalla principal, protector, importación de imágenes, caché local y catálogos de idioma.
- Fuera de alcance: modo de inicio del televisor, desinstalación de aplicaciones, otros dispositivos ADB conectados, sitio web y paquetes webOS/Tizen.

### Decisiones

1. Agregar y quitar nombres uno por uno. Si no hay nombres, mantener «Para ti».
2. Ofrecer modo fijo y aleatorio; permitir intervalo diario o en minutos.
3. Consultar Bing mediante el feed público de Peapix en español; guardar hasta diez imágenes únicas y sus metadatos en el almacenamiento privado de la app.
4. Mantener TMDB como fuente predeterminada y conservar su slideshow existente.
5. Copiar las imágenes escogidas a almacenamiento privado para no depender de permisos persistentes del selector.
6. Permitir una sola imagen de tema, con opacidad configurable; el valor inicial observado en la TV fue 45 %.

### No verificado / atajos de prueba

- La rotación real entre varios nombres no podía observarse con un solo nombre de prueba; se verificaron la selección de modo e intervalo, no el cambio de nombre al transcurrir el periodo.
- No se verificó el comportamiento en un televisor distinto del emulador y la Caixun Sgamer.

### Cambios residuales en el entorno

- En esta etapa no se modificaron ajustes del televisor ni se instaló una compilación.

---

## 2. Implementación de nombres personalizables

### Implementación

- `app/src/io/freemytv/Prefs.java`: persistencia de lista de nombres, índice fijo, modalidad e intervalo. El modo aleatorio elige un nombre a partir del intervalo de tiempo.
- `app/src/io/freemytv/SettingsActivity.java`: nueva página **Ajustes → Ajustes de inicio → Nombres**; permite cambiar Fijo/Aleatorio, escoger el nombre fijo, cambiar el intervalo, agregar nombres y solicitar confirmación antes de quitar uno.
- `app/src/io/freemytv/HomeView.java`: encabezado dinámico «Para ti: {nombre}» y reserva «Para ti» si la lista está vacía.
- Catálogos `app/assets/i18n/*.json`: cadenas de la página y del diálogo.

### Alcance del cambio

- Toca: ajustes y encabezado de la aplicación Android.
- Fuera de alcance: nombre de usuario del dispositivo, cuentas del televisor y texto de otras secciones.

### Resultado verificado

- En la Caixun Sgamer se agregó temporalmente `FMTPRUEBA`; la pantalla principal mostró **«Para ti: FMTPRUEBA»**.
- Se cambió la modalidad a Aleatorio y se observó el valor en los ajustes.
- Después de la prueba se quitó `FMTPRUEBA`; quedaron Fijo, ningún nombre y el intervalo diario.

### No verificado / atajos de prueba

- El intervalo de cambio aleatorio no se dejó transcurrir para observar una rotación efectiva. La verificación fue del modo y su configuración.
- En la prueba física se utilizó ADB para escribir el nombre temporal en el diálogo del televisor; no se probó la escritura con el control remoto en manos de una persona.

### Cambios residuales en el entorno

- El nombre temporal fue eliminado desde la propia página de Nombres. La TV quedó sin nombres guardados, como antes de la prueba.

---

## 3. Fuentes y gestión de imágenes del protector

### Implementación

- `app/src/io/freemytv/Prefs.java`: fuente TMDB, Bing o almacenamiento interno; modo fijo/aleatorio, intervalo e imagen fija seleccionada.
- `app/src/io/freemytv/BingWallpapers.java`: consulta `https://peapix.com/bing/feed?country=es&n=10`, descarga y valida imágenes, conserva un máximo de diez entradas únicas, almacena sus metadatos y expone el estado a la interfaz.
- `app/src/io/freemytv/WallpaperImages.java`: importación privada de imágenes locales y funciones compartidas de archivo.
- `app/src/io/freemytv/DreamView.java`: carga de la fuente elegida, presentación fija/aleatoria para las fuentes nuevas y crédito en pantalla para las imágenes Bing.
- `app/src/io/freemytv/SettingsActivity.java`: fuente, modo, imagen fija, intervalo, actualización de Bing y opción «Ver ahora».
- Directorio privado de las imágenes Bing: `files/bing-wallpapers/`. Índice de metadatos: `files/bing-wallpapers.json`.

### Alcance del cambio

- Toca: protector de pantalla Android y sus ajustes.
- Fuera de alcance: protector de webOS/Tizen y configuración del protector nativo del televisor.

### Resultado verificado

- El selector mostró TMDB, Bing y almacenamiento interno.
- En el emulador, Bing descargó siete imágenes y presentó título y crédito en español.
- En la Caixun Sgamer, el estado de la página mostró **7/10 imágenes guardadas**. El feed consultado devolvió siete elementos, no diez.
- En la TV se probaron el modo fijo, el modo aleatorio y el selector de intervalos (diario y opciones en minutos).
- «Ver ahora» abrió tanto el protector Bing como el protector TMDB. En la vista Bing se observaron la imagen, el título y el crédito en español.

### No verificado / atajos de prueba

- No se forzó una respuesta Peapix con diez imágenes; la respuesta real de las pruebas contenía siete. El límite de diez se verificó por el estado de la función, no llenando el caché hasta diez.
- En la Caixun Sgamer se abrió el selector de almacenamiento interno y se canceló; no se seleccionó una foto personal ni se probó una imagen local como fuente del protector de principio a fin.
- No se esperó un intervalo de minutos o de un día para comprobar el cambio automático entre imágenes.
- No se verificaron todas las variaciones de errores de red ni la recuperación tras reiniciar el televisor sin conexión.

### Cambios residuales en el entorno

- La fuente del protector se restauró a TMDB y el modo aleatorio quedó restaurado después de la prueba temporal de modo fijo.
- El caché local de siete imágenes Bing permanece en la app de la TV para que siga disponible sin conexión. No se borró.

---

## 4. Imagen de fondo del tema y ajustes de interfaz

### Implementación

- `app/src/io/freemytv/WallpaperImages.java`: copia las imágenes elegidas a `files/wallpaper-images/`, guarda la imagen de tema por separado y ofrece su carga al tema.
- `app/src/io/freemytv/Prefs.java`: nombre de la imagen de tema y opacidad limitada al rango de 0 a 100 %.
- `app/src/io/freemytv/HomeView.java`: dibuja la imagen como fondo con la opacidad seleccionada.
- `app/src/io/freemytv/SettingsActivity.java`: opción de imagen, control de opacidad, vista del archivo seleccionado y confirmación para quitarla.
- Se añadió truncamiento visual de valores extensos en las filas de ajustes para que no invadan la etiqueta contigua.

### Alcance del cambio

- Toca: tema y pantalla de ajustes Android.
- Fuera de alcance: cambiar el tema del sistema, la imagen de fondo del televisor o cualquier ajuste de imagen del hardware.

### Resultado verificado

- En el emulador se eligió una imagen sintética y se confirmó su renderizado como fondo del tema.
- En la TV física se importó una imagen sintética de prueba mediante el selector de fotos; Ajustes confirmó que se guardó.
- Se comprobó el cambio de opacidad de 45 % a 50 % y el renderizado del fondo en Inicio. Luego se quitó la imagen temporal y se restauró 45 %.

### No verificado / atajos de prueba

- La prueba física usó una imagen sintética creada para este fin, no una fotografía del usuario.
- No se probó con una colección grande de imágenes ni con todos los formatos y tamaños que puede devolver un proveedor de fotos.

### Cambios residuales en el entorno

- La imagen de tema temporal se quitó desde los ajustes. Se eliminó también el PNG sintético de `/sdcard/Pictures/` y su copia temporal local.
- El tema quedó en Automático, sin imagen de fondo y con opacidad de 45 %, como antes de la prueba.

---

## 5. Construcción, compatibilidad del selector y depuración

### Problema

La primera ruta de importación de imágenes no abría un selector utilizable en el emulador y la primera compilación señaló errores Java.

### Causa Raíz

- `ACTION_OPEN_DOCUMENT` resolvía al stub `com.android.tv.frameworkpackagestubs/.Stubs$DocumentsStub`, que respondía que no había una aplicación para completar la acción.
- La compilación inicial reveló problemas de alcance en Java y tipos de pintura incompatibles; se corrigieron antes de continuar las pruebas.

### Alcance del cambio

- Toca: implementación Android y preparación de imágenes locales.
- Fuera de alcance: agregar dependencias o un sistema Gradle; el proyecto sigue compilándose con el SDK y `build.sh`.

### Solución Aplicada

1. Se prefiere `ACTION_OPEN_DOCUMENT` cuando existe un selector real de archivos; si solo se anuncia el stub de Android TV, se usa `ACTION_GET_CONTENT`, manejado por Photo Picker en el emulador.
2. Se corrigieron los errores de compilación y se volvió a generar `FreeMyTV.apk`.
3. Se retiró el log temporal `theme image probe` de `WallpaperImages.java`.
4. Se añadió truncamiento de valores largos en filas para mantener separados rótulos y valores.

### Verificación

- `build.sh`: compilación correcta; produjo `FreeMyTV.apk`.
- `python tools/translate.py --check` con `PYTHONUTF8=1`: `OK: all catalogues match the code (286 keys)`.
- `git diff --check`: sin errores de whitespace.
- La instalación y la comprobación visual de fondos se realizaron también en el emulador Android TV.

### No verificado / atajos de prueba

- No se ejecutó `tools/check.sh` completo; por tanto, no se registran aquí resultados de sus pruebas LG, Samsung, ES5 ni de validaciones adicionales del build.
- No se compiló contra `android-26/android.jar` para repetir la comprobación de compatibilidad de API mínima.
- El selector se validó en el Photo Picker del emulador y del televisor disponible; no en proveedores alternativos de imágenes.

### Cambios residuales en el entorno

- Las capturas y APK extraídos a `%TEMP%` para la prueba física fueron eliminados al finalizar.
- No se creó una imagen sintética dentro del repositorio; `git status` conservó solo los cambios de desarrollo existentes.

---

## 6. Instalación de la compilación en la TV física

### Contexto

Se solicitó instalar la versión nueva en la TV conectada por ADB con identificador `5C857EF4F339-rfpAQ4`, teniendo en cuenta que una firma distinta podría impedir la actualización.

### Diagnóstico

- ADB mostraba el dispositivo como `adb-5C857EF4F339-rfpAQ4 (2)._adb-tls-connect._tcp`, transporte 3. Usar el identificador corto como selector no funcionó; se utilizó `adb -t 3`.
- `dumpsys power` indicó `mWakefulness=Asleep`; se envió `KEYCODE_WAKEUP` y el estado pasó a Awake.
- La app instalada era `io.freemytv`, `versionName=1.1`, `versionCode=2`.
- Se extrajo temporalmente el APK instalado y se verificó el certificado con `apksigner`. El digest SHA-256 del certificado instalado y del APK nuevo coincidía: `dc1b5d4929869d72590e0ebc5ba9fb46de0ae144b57bb90164b240017a5dc734`.

### Alcance del cambio

- Toca: instalación de la compilación de Free My TV en el televisor identificado.
- Fuera de alcance: desinstalar la app, borrar sus datos, cambiar el launcher del sistema o interactuar con los otros dispositivos ADB conectados.

### Verificación

- `adb install -r FreeMyTV.apk`: **Success**.
- `am start -W -n io.freemytv/.SettingsActivity`: `Status: ok`.
- La TV permaneció Awake. No se solicitó ni se ejecutó desinstalación porque las firmas coincidían.

### No verificado / atajos de prueba

- No se registró la versión de Android/ROM de la Caixun durante esta instalación.
- El digest identifica el certificado público, no prueba ni documenta la ubicación o el respaldo del keystore privado.

### Cambios residuales en el entorno

- Se actualizó la app preservando sus datos.
- No se cambió el modo de inicio configurado en el televisor durante la instalación.

---

## 7. Registro de pruebas funcionales en hardware y estado final

### Qué se comprobaba

Verificar las funciones añadidas en la Caixun Sgamer real, no solamente por compilación o en el emulador, y devolver los ajustes de prueba a su estado previo.

### Métodos

| Vía | Para qué sirve | Trampa medida |
|---|---|---|
| ADB con `-t 3` | Enviar teclas, consultar estado, lanzar pantallas y leer logs | El nombre corto de serie no era aceptado por `adb -s`; el identificador visible era un transporte mDNS y el selector reproducible fue `-t 3`. |
| Capturas `screencap` | Verificar el contenido dibujado en la pantalla real | Una captura negra apareció al iniciar Inicio mientras la TV estaba dormida; se comprobó y despertó la TV antes de concluir que era un problema de renderizado. |
| Interfaz de la app | Confirmar etiquetas, valores, selección y vistas previas | La navegación DPAD debe comprobarse con capturas entre pasos; una secuencia de teclas demasiado rápida o con foco distinto abre otra página. |
| Logcat y dumpsys | Contrastar fallos de app con el estado real del sistema | La captura negra no era evidencia de caída: la actividad se abrió mientras el sistema estaba dormido; una captura posterior despierta mostró Inicio correctamente. |

### Resultado

- La app terminó abierta en Inicio de Free My TV, con el encabezado «Para ti» sin nombre.
- La TV quedó despierta; `topResumedActivity` indicó `io.freemytv/.HomeActivity`.
- Fuente del protector: TMDB. Se probó «Ver ahora» y apareció una recomendación TMDB.
- Ajustes de nombres: Fijo, sin nombres, intervalo diario.
- Tema: Automático, sin imagen y opacidad de 45 %.
- Los cambios temporales se revirtieron. Se eliminó la imagen sintética importada y se comprobó que el PNG de prueba ya no estaba en `/sdcard/Pictures/`.
- No se observaron excepciones fatales ni ANR de `io.freemytv` en la consulta final de logcat.

### No verificado / atajos de prueba

- La modalidad aleatoria se observó en ajustes, pero no se aguardó el intervalo para observar un cambio natural del nombre.
- Se verificó la lista de imágenes Bing y se utilizó una imagen Bing en la vista previa, pero no se esperó un día para comprobar la rotación automática.
- Se abrió y canceló el Photo Picker de imágenes para el protector interno; no se importó una imagen personal como fuente de protector.
- No se probó reinicio completo de la TV, activación del protector por inactividad ni vuelta desde el protector nativo del fabricante.
- No se cambió la modalidad de Inicio entre Free My TV y Original; la prueba no valida el flujo de cambio de launcher ni la reversión de apps del televisor.
- El caché de Bing observado fue de siete imágenes, debido a la cantidad devuelta por el feed durante la prueba.

### Cambios residuales en el entorno

- Permanece el caché legítimo de **siete imágenes Bing** y sus metadatos en el almacenamiento privado de Free My TV. No es un archivo de prueba y se conservó deliberadamente para disponibilidad sin conexión.
- No quedaron nombres de prueba, fondo sintético ni archivo sintético en Pictures. La opacidad quedó en 45 % y la fuente TMDB.
- La TV quedó despierta en Inicio de Free My TV. No se modificó su selección de modo de inicio.
- No se alteraron los demás dispositivos conectados por ADB ni los ajustes de imagen, sonido, red o cuenta de la TV.

---

## 8. Commit, push y release en el fork

### Contexto

Se solicitó publicar los cambios y el APK en `ferdcard-ux/free-my-tv`, no en el repositorio padre `simbasjungles/free-my-tv`.

### Diagnóstico

- El remoto local `origin` apunta al repositorio padre; por seguridad, no se usó para publicar.
- La rama `main` del fork estaba en `d84b743`, antepasado de la base local `f2d984c`; no había commits divergentes que requirieran reescritura.
- El fork no tenía releases ni tags. El release público del repositorio padre `v1.2.0` usa Android `versionCode=3`, `versionName=1.2`.
- El APK local y la TV física usan el mismo certificado de desarrollo (`SHA-256 dc1b5d4929869d72590e0ebc5ba9fb46de0ae144b57bb90164b240017a5dc734`); ese certificado es distinto del usado por el APK del repositorio padre. El APK se publicará solo en el fork. No se publicará ni se añadirá al repositorio ningún keystore.

### Alcance del cambio

- Toca: `main` y release `v1.3.0` de `ferdcard-ux/free-my-tv`.
- Fuera de alcance: push al repositorio padre y publicación de APK en el repositorio padre.

### Versión y artefacto

- `app/AndroidManifest.xml`: `versionName=1.3`, `versionCode=4`, superior al APK del padre y a la versión de desarrollo instalada en la TV.
- El APK de release se construye temporalmente con `config.example.properties`, sin la configuración privada de la máquina. Se restaura `config.properties` después de la compilación y se compara su SHA-256 antes y después.
- El artefacto `FreeMyTV.apk` se adjunta al release; se excluye del commit por `.gitignore`.

### Verificación

- `tools/check.sh`: build Android, `translate.py --check`, paquetes genéricos webOS/Tizen y chequeo ES5; resultado: `All checks passed`.
- `apksigner` y `aapt dump badging`: validar firma y metadatos del APK de release.
- Push y publicación: consultar el commit de la rama `main`, el tag y los assets desde GitHub.

### No verificado / atajos de prueba

- El APK de este fork no comparte el certificado del APK del repositorio padre. Quien migre desde ese APK oficial tendrá que desinstalarlo primero, lo que borra sus datos; la TV usada en esta bitácora tenía la firma local del fork y se pudo actualizar sin ese paso.
- Esta publicación incluye el APK Android. No se publican artefactos webOS/Tizen ni se modifican los índices de Homebrew en esta operación.

### Cambios residuales en el entorno

- `config.properties` se restauró byte por byte después de preparar el APK sin configuración privada.
- La llave privada local permanece ignorada por Git; el APK firmado se comparte solo como asset del release del fork.
- La rama `origin` sigue apuntando al repositorio padre; la publicación usa el remoto del fork.

---

## 9. Corrección del enfoque al elegir imágenes

### Causa

- La navegación de imágenes se abría como una actividad del sistema, no se dibujaba dentro de Free My TV. El Photo Picker no mostraba un indicador de foco suficientemente claro en las TVs de prueba.
- En ambas TVs, `ACTION_OPEN_DOCUMENT` resolvía a `com.android.tv.frameworkpackagestubs/.Stubs$DocumentsStub`, sin una interfaz de archivos funcional. La app tampoco puede modificar cómo otra aplicación del sistema dibuja su foco.

### Cambio

- Se añadió en `SettingsActivity` un navegador interno de imágenes respaldado por `MediaStore`. Las carpetas y las fotos se dibujan en el Canvas de Ajustes, por lo que comparten el mismo resaltado visible de foco y navegación DPAD que el resto de la app.
- El navegador permite recorrer carpetas, elegir una imagen para el tema y seleccionar varias para el protector. Incluye carga, estado vacío, reintento, cancelación, permiso de lectura, acceso a ajustes y Photo Picker del sistema como alternativa.
- El permiso se solicita al abrir el navegador: `READ_MEDIA_IMAGES` en Android 13+, `READ_MEDIA_VISUAL_USER_SELECTED` para acceso limitado en Android 14+, y `READ_EXTERNAL_STORAGE` hasta Android 12. La app solo lee las imágenes seleccionadas; `WallpaperImages` sigue copiándolas al almacenamiento privado de Free My TV.
- Se revisó el foco de las páginas principales, Ajustes de inicio, Tema, Nombres y Protector de pantalla: las filas seleccionadas usan el resaltado blanco compartido.

### Verificación y límites

- `build.sh` y `python tools/translate.py --check` finalizaron correctamente.
- Se instaló la compilación en la Caixun Sgamer y el emulador Android TV. En ambos se comprobó el foco visible en Ajustes y en el selector; en el emulador también se recorrieron una carpeta anidada y una imagen sintética.
- En el emulador, el acceso de solo lectura permitió enumerar `Pictures/FreeMyTVTest/selector-test.png`. Al cancelar, el foco regresó a «Imagen de fondo». También se verificó la recuperación al denegar el permiso y reabrir el selector.
- En la Caixun, que estaba dormida, se comprobó la pantalla de permiso y el resaltado de «Permitir acceso»; el permiso no se concedió en ese dispositivo.
- La imagen sintética y su carpeta se retiraron. No se importaron imágenes ni se accedió a fotos personales. Logcat no mostró excepciones fatales ni ANR durante la prueba.
- Los demás dispositivos que aparecen en ADB no se modificaron.

### Estado final del entorno

- La Caixun quedó despierta tras la prueba. El permiso de fotos permanece sin conceder y con sus indicadores de solicitud restablecidos; el emulador también quedó sin permiso concedido.
- No quedó la imagen sintética en Pictures. La imagen de tema preexistente del emulador no se modificó.

---

## 10. Vista previa de fotos en el navegador interno

### Cambio

- Al enfocar una foto en «Mis fotos», el panel izquierdo muestra una miniatura recortada y el nombre del archivo. Mientras se carga, conserva una indicación de carga y, si no se puede leer, muestra el error correspondiente.
- Las miniaturas se cargan fuera del hilo de interfaz con `ContentResolver.loadThumbnail` en Android 10+ y una decodificación reducida como alternativa. Al cambiar de foto o salir del navegador, se cancela la tarea anterior y se libera el bitmap para evitar resultados obsoletos y retener memoria.

### Verificación y límites

- `build.sh` terminó con `Built FreeMyTV.apk`; `python tools/translate.py --check` validó los 301 textos.
- Se instaló la compilación en el emulador Android TV (Android 14). Una imagen sintética no personal mostró su miniatura y nombre al enfocarla; al cancelar, el foco regresó a «Imagen de fondo». Logcat no registró errores de la app durante la prueba.
- No se seleccionó ni importó ninguna foto como fondo. No se probó la vista previa en la Caixun física.

### Estado final del entorno

- Se eliminó del emulador la imagen y carpeta de prueba, junto con la captura temporal; se revocó `READ_MEDIA_IMAGES` y se confirmó que ambos permisos de fotos están denegados.
- No se modificaron el fondo, las preferencias ni los datos de fotos personales del emulador; los otros dispositivos ADB no se modificaron.

---

## 11. Integración de mejoras del repositorio original

### Alcance

- Se fusionó `origin/main` (repositorio `simbasjungles/free-my-tv`, commit `fa471834`) con el fork, conservando las funciones propias de este último.
- Se integraron las mejoras recientes de Android, entre ellas recomendaciones por servicio, «Now Playing», páginas de actualización y compatibilidad adicional con televisores.
- También se integraron cambios de documentación, catálogos, zonas horarias y paquetes webOS/Tizen.
- En las resoluciones manuales se combinaron las opciones originales de recomendaciones, reproducción y actualizaciones con los nombres personalizables, las fuentes Bing/almacenamiento interno, los fondos de tema y las vistas previas de imágenes del fork.
- El manifiesto queda en `1.4.0` (`versionCode=6`). Se conservaron las traducciones existentes y se completó el catálogo zh-TW con valores de origen en inglés para 68 cadenas del fork que faltaban en ese catálogo.

### Verificación

- `tools/check.sh`: compilación Android, comprobación de traducciones, paquetes genéricos webOS/Tizen y verificación ES5; resultado: `All checks passed`.
- `python tools/translate.py --check`: resultado correcto con 371 cadenas.
- Las pruebas funcionales de la app `1.4.0` se realizaron únicamente en `emulator-5554` (Android TV 14). La instalación posterior en la TV física, solicitada por el usuario, se registra abajo; no se hicieron pruebas funcionales allí.
- Se verificaron en pantalla Ajustes de inicio, Recomendaciones, «Recomendaciones de» y Protector de pantalla. El emulador no tenía servicios de streaming instalados; la lista de proveedores mostró su estado vacío.
- En Protector de pantalla se verificó la selección de TMDB, Bing y almacenamiento interno, el foco visible, el modo fijo/aleatorio, el intervalo de cinco minutos y la opción «Ver ahora». La interfaz informó 9 de 10 imágenes Bing disponibles.
- Se abrió el flujo para añadir imágenes internas y se canceló en la solicitud de acceso; no se concedió permiso ni se seleccionaron fotos.
- Tras las comprobaciones, se restauraron TMDB, el modo fijo y el intervalo diario. No se modificaron ajustes de la TV física.

### Límites

- No se seleccionó una imagen local ni se probó la presentación completa de una foto personal.
- No se esperó el transcurso de un intervalo para comprobar una rotación automática, ni se probó la descarga con la red desconectada.
- zh-TW incluye fallbacks en inglés para las 68 cadenas indicadas; no se consideran traducciones revisadas.
- La fusión se publicó después de la etapa de pruebas; el commit y push se detallan en el cierre de esta bitácora.
- El fork es independiente; el APK de release debe usar la firma persistente propia del fork, no la llave upstream. El APK personalizado de la compilación local no es publicable porque incluye datos de `config.properties`.

### Estado final del entorno

- El emulador conserva la app `1.4.0`; la fuente TMDB, el modo fijo y el intervalo diario quedaron restaurados. No se concedió acceso a fotos.
- La fusión quedó finalizada y publicada en `ferdcard-ux/free-my-tv`, rama `main`. El APK personalizado no se publica; se generará una compilación genérica para el release.

### Instalación solicitada en la TV física

- El 2026-10-28, a petición del usuario, se instaló `FreeMyTV.apk` versión `1.4.0` (`versionCode=6`) en la Caixun Sgamer `5C857EF4F339-rfpAQ4`.
- La versión anterior era `1.3` (`versionCode=4`). Se comprobó que la firma del APK nuevo coincide con la instalada; Android aceptó `adb install -r` y respondió `Success`. La hora de primera instalación no cambió, por lo que se actualizó sin desinstalar ni borrar los datos.
- La TV ya estaba despierta. No se abrió Free My TV ni se cambió la aplicación que estaba en primer plano.
- Esta acción fue solo la instalación solicitada; no se realizaron pruebas funcionales en la TV física. La integración posterior se publicó en GitHub según el cierre de esta bitácora.

---

## 12. Persistencia de la firma Android entre equipos

### Problema

- La compilación podía generar automáticamente un `freemytv.keystore` distinto cuando faltaba el archivo. En otro equipo, el APK resultante tendría otra firma y Android rechazaría la actualización sobre una instalación existente.
- El keystore local está excluido de Git, por lo que un clon nuevo no recibe la clave privada (correctamente), pero tampoco podía saber que debía restaurar exactamente la misma llave.

### Cambio

- `build.sh` usa `FREEMYTV_KEYSTORE` si se define y, de lo contrario, busca `freemytv.keystore` en la raíz del proyecto. Si no encuentra la llave, ahora se detiene: nunca crea silenciosamente otra identidad.
- La contraseña se solicita sin mostrarla en una compilación interactiva, o se recibe desde `FREEMYTV_KEYSTORE_PASS` en automatización. Se pueden especificar `FREEMYTV_KEY_PASS` y `FREEMYTV_KEY_ALIAS` cuando sean distintos del valor predeterminado.
- `README.md` y `CLAUDE.md` documentan el respaldo cifrado y la transferencia privada del mismo archivo a cada equipo, el uso de una ubicación fuera del checkout y la rotación interactiva de la antigua contraseña de desarrollo que estuvo en un script anterior.
- El fork usa el mismo keystore privado persistente para las compilaciones locales y sus releases. La llave del upstream es distinta; sus APK no se pueden instalar como actualización directa de los del fork.

### Seguridad y límites

- No se añadió ningún keystore, contraseña, archivo de secretos ni ruta privada al repositorio. `*.keystore` ya está excluido por `.gitignore`.
- La garantía entre equipos depende de que quien compile restaure la copia privada exacta del keystore y use su contraseña; no se debe generar ni sustituir por otra llave. El respaldo debe mantenerse cifrado y privado.
- La persona propietaria confirmó que cambió la contraseña de la copia externa antes de transferirla. La nueva contraseña no se comparte ni se registra. Las futuras rotaciones se harán interactivamente con `keytool -storepasswd` y `keytool -keypasswd`; el script de migración solo sirve para una llave que conserve la contraseña inicial antigua.
- Se añadió `tools/change-signing-password.ps1`, que solicitó una nueva frase dos veces con entrada oculta y actualiza las contraseñas del almacén y de la entrada de clave privada, conservando el certificado. El script obtiene la contraseña heredada directamente del historial local de Git, no la imprime ni la pasa en argumentos de línea de comandos.

### Paquete privado solicitado

- Se creó `C:\Users\migue\FreeMyTV-signing`, fuera del checkout, con `freemytv.keystore`, `README.txt` y `SHA256.txt`.
- Se limitaron los permisos de la carpeta a la cuenta actual de Windows, SYSTEM y Administradores. El SHA-256 del archivo copiado coincide con el keystore local; la huella pública del certificado también está en `SHA256.txt`.
- El paquete no contiene contraseñas. El keystore externo se actualizó antes de transferirlo; la nueva contraseña se mantiene separada en un gestor de contraseñas.
- El keystore sigue excluido de Git; solo se añadieron al repositorio instrucciones públicas sin contraseñas ni llave privada.

### Cierre de documentación y validación

- Se actualizaron `README.md`, `CLAUDE.md` y los documentos públicos de `docs/signing/` para explicar cómo usar y transferir el keystore privado del fork sin publicar la llave ni la contraseña.
- `tools/check.sh` volvió a pasar después de los cambios; también pasaron `git diff --check`, la validación sintáctica de `tools/change-signing-password.ps1` y una prueba de rotación con un keystore temporal.
- Se confirmó que `freemytv.keystore` y `FreeMyTV.apk` siguen excluidos de Git y que no hay keystores incluidos entre los archivos rastreados.
- No se publicó el APK construido con `config.properties`: la inspección de su DEX descomprimido detectó valores configurados, entre ellos la clave TMDB y datos de ubicación. Se generó `FreeMyTV.apk` con `./build.sh --generic`; `Config.java` confirma que `GENERIC` es `true` y los campos TMDB y ubicación están vacíos.
- El APK genérico verificado es `io.freemytv` 1.4.0 (`versionCode=6`), firmado con la huella SHA-256 del certificado del fork (`dc1b5d4929869d72590e0ebc5ba9fb46de0ae144b57bb90164b240017a5dc734`). La inspección de los DEX no encontró valores privados de `config.properties`.
- Commit de integración: `d21633b960d9094bb2be957e5733d1aa3e794632` (`Merge upstream Free My TV improvements`), sin trailer de coautoría.
- Push verificado a `fork/main` (`ferdcard-ux/free-my-tv`). No se hizo push al repositorio padre. El worktree quedó limpio tras el commit.
- Se publicó [el release Android `v1.4.0`](https://github.com/ferdcard-ux/free-my-tv/releases/tag/v1.4.0), adjuntando únicamente `FreeMyTV.apk` (1.504.294 bytes). Su SHA-256 es `d508295c40d129b8d2a281c4483f9fa48816a001712e595b41c44ce8c0867174`.
- El tag `v1.4.0` apunta a `a4221b39b2094e4dc734aca9401330405664b157`; la rama remota `fork/main` quedó en ese mismo commit. El repositorio upstream no se modificó.

---

## Historial de Versiones

| Fecha | Versión | Cambios |
|---|---|---|
| 2026-10-28 | 1.4.0 / Android 1.4.0 (versionCode 6) | Integración de las mejoras recientes de `simbasjungles/free-my-tv` en el fork, conservando sus funciones propias; validación en emulador Android TV. |
| 2026-10-28 | 1.3.1 / Android 1.3.1 (versionCode 5) | Vista previa de fotos enfocadas en el navegador interno; publicación de APK genérico en el release `v1.3.1` del fork. |
| 2026-10-26 | 1.3.0 / Android 1.3 (versionCode 4) | Nombres personalizables, fuentes TMDB/Bing/almacenamiento interno, fondos de tema con opacidad; pruebas en emulador y Caixun Sgamer y publicación del APK en el fork `ferdcard-ux/free-my-tv`. |
