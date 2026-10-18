/*
 * pcs_shim — puente a PPCS_Initialize()
 * =====================================
 *
 * Problema (verificado en el binario, no supuesto):
 *
 *   libOKSMARTPPCS.so exporta `PPCS_Initialize` (0x6460c) como un salto puro a
 *   `cs2p2p_PPPP_Initialize` (0x4f9f8), pero **nada en todo el XAPK lo llama**:
 *   no hay ningún `bl <PPCS_Initialize>` en las 18 librerías, no hay `.init_array`
 *   en el .so, y los 20 JNI de `com.vstarcam.JNIApi` no exponen ninguno.
 *
 *   `cs2p2p_PPPP_Initialize` devuelve exactamente **-5** cuando no se le pasa
 *   InitString:
 *
 *       4faa0:  cbz  x22, 0x4fcb8        ; x22 = InitString
 *       ...
 *       4fcb8:  mov  w0, #-0x5           ; return -5
 *
 *   Ese -5 es el mismo que devuelve el túnel entero en el equipo real:
 *   `[P2PClient.c:367] ... result:-5 end`. El SDK está **sin inicializar**.
 *
 * La app original tampoco lo llama desde Java: O-KAM Pro es Flutter y el InitString
 * lo inyecta su propio runtime. Nosotros lo hacemos aquí, por `dlopen`/`dlsym`,
 * porque `PPCS_Initialize` no es un símbolo `Java_*` y por tanto no puede enlazarse
 * desde Kotlin directamente.
 *
 * El InitString (108 chars) está **dentro del propio .so**, en 0x25d10; se copia
 * literal para no depender de la dirección base del mapeo.
 */

#include <jni.h>
#include <dlfcn.h>
#include <string.h>

/* Literales de 0x25d10 en libOKSMARTPPCS.so (v1 de O-KAM Pro, com.okampro.oksmart). */
static const char kInitString[] =
    "BJHJAABPPKMBDNMABCDGEMALHMNECFNCDBAFBBGAALMMLPLHDLAMCAOECIKBMMOAAF"
    "JOLBDHLMNCEFCGMPIEMMFJJBKDBBHMAFCJHABDIEKE";

typedef int (*ppcs_init_t)(const char *initString);

/* Códigos propios, por debajo de la gama PPCS (-1..-19) para no confundirlos. */
#define SHIM_ERR_DLOPEN    -1000
#define SHIM_ERR_DLSYM     -1001
#define SHIM_ERR_EXCEPTION -1002

JNIEXPORT jint JNICALL
Java_com_lgsusdev_camrevert_p2p_PpcsShim_nativeInitialize(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;

    /* RTLD_NOW sin NOLOAD: si JNIApi ya cargó la lib devuelve el mismo handle,
     * y si no, el linker la carga desde el directorio nativo de la app. */
    void *handle = dlopen("libOKSMARTPPCS.so", RTLD_NOW);
    if (handle == NULL) return SHIM_ERR_DLOPEN;

    ppcs_init_t init = (ppcs_init_t) dlsym(handle, "PPCS_Initialize");
    if (init == NULL) return SHIM_ERR_DLSYM;

    return init(kInitString);
}

JNIEXPORT jstring JNICALL
Java_com_lgsusdev_camrevert_p2p_PpcsShim_nativeInitString(JNIEnv *env, jclass clazz)
{
    (void) clazz;
    return (*env)->NewStringUTF(env, kInitString);
}
