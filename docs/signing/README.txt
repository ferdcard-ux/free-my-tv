Free My TV — archivos de firma Android

Contenido
- freemytv.keystore: llave privada usada para firmar los APK locales del fork.
- SHA256.txt: huella pública del certificado para comprobar que la firma no cambió.

IMPORTANTE: la contraseña inicial apareció en una versión anterior del script de compilación. La persona propietaria
la ha cambiado localmente antes de preparar este paquete. La contraseña nueva no está incluida aquí; consérvala en
un gestor de contraseñas y compártela separadamente, por un canal seguro.

Para una futura rotación, usa keytool interactivamente:
   keytool -storepasswd -keystore "freemytv.keystore"
Luego cambia también la contraseña de la clave privada:
   keytool -keypasswd -alias freemytv -keystore "freemytv.keystore"
Ambos comandos preguntan las contraseñas sin incluirlas en el historial. El script
tools/change-signing-password.ps1 del checkout sirve solo para migrar un archivo que aún usa la contraseña inicial
antigua; no lo uses de nuevo después de cambiarla. Cambiar las contraseñas no cambia la firma de la app.

La contraseña no se guarda en este paquete ni en el repositorio. Al compilar con build.sh, se solicita sin mostrarla.
También se puede definir FREEMYTV_KEYSTORE_PASS como variable de entorno protegida. No escribas la contraseña en
build.sh, config.properties, un archivo compartido o un comando que quede en el historial.

Para compilar desde otro equipo:
1. Copia freemytv.keystore a la raíz del checkout privado, o consérvalo en una carpeta segura fuera del checkout.
2. Desde Git Bash, define FREEMYTV_KEYSTORE con la ruta segura si no está en la raíz:
   export FREEMYTV_KEYSTORE="/ruta/segura/freemytv.keystore"
3. Ejecuta ./build.sh. Ingresa la contraseña nueva cuando se solicite.
4. Comprueba el APK con apksigner verify --print-certs FreeMyTV.apk y compara la huella SHA-256 con SHA256.txt.

Usa siempre exactamente el mismo keystore para los APK locales y los releases de este fork. Si se pierde, Android no
aceptará una llave nueva como actualización. El APK del repositorio upstream `simbasjungles/free-my-tv` usa otra
llave y no se puede actualizar con este keystore.

Transfiere el keystore solo mediante un gestor de contraseñas con adjuntos cifrados u otro canal cifrado y privado.
No lo subas a GitHub, correo sin cifrar, almacenamiento compartido ni servicios públicos.
