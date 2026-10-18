import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Datos personales de la cámara y de la cuenta EYE4: IP, UID/VUID y credenciales.
 *
 * Viven en `secrets.properties` (raíz del proyecto) — el equivalente al `.env` de
 * otros proyectos —, que está en `.gitignore`. También se acepta un archivo `.env`
 * con la misma sintaxis si no existe `secrets.properties`.
 *
 * Sin ese archivo la app **compila igual**, con valores vacíos, y se rellenan desde
 * el diálogo de Configuración en el dispositivo. Así el repositorio puede publicarse
 * sin filtrar la contraseña de la cámara.
 *
 * Mapa de claves (ver `secrets.properties.example` y `docs/CONFIGURACION.md`):
 *
 * | Clave en el archivo      | Qué es                                   | Campo en la app          |
 * |--------------------------|------------------------------------------|--------------------------|
 * | `cam.ip`                 | IP de la cámara en tu red                | `CamConfig.ip`           |
 * | `cam.uid` / `cam.vuid`   | VUID de la cámara (empieza por `VE`)     | `CamConfig.uid`/`.vuid`  |
 * | `cam.p2pDid`             | ID P2P real (17 caracteres)              | `CamConfig.p2pDid`       |
 * | `cam.dasAddress`         | Dirección P2P `DAS-…` del equipo         | `CamConfig.dasAddress`   |
 * | `cam.user`               | Usuario de la CÁMARA (`admin`)           | `CamConfig.user`         |
 * | `cam.password`           | **Contraseña de la CÁMARA**              | `CamConfig.password`     |
 * | `cam.accountUser`        | **Tu email de la cuenta EYE4**           | `CamConfig.accountUser`  |
 * | `cam.accountPassword`    | **Contraseña de esa cuenta**             | `CamConfig.accountPassword` |
 * | `cam.portFixed`/`portPtz`| Puertos RTSP de cada lente               | `CamConfig.rtspPort*`    |
 *
 * Ojo con la diferencia: `cam.password` es la de la **cámara** (para RTSP y el
 * `login()` del SDK) y `cam.accountPassword` es la de tu **cuenta EYE4** (para pedir
 * el token de sesión). No son la misma.
 */
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    val fallback = rootProject.file(".env")
    when {
        f.exists() -> f.inputStream().use { load(it) }
        fallback.exists() -> fallback.inputStream().use { load(it) }
    }
}

/** Escapa una propiedad para usarla como literal de cadena dentro de BuildConfig. */
fun secret(key: String): String =
    secrets.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.lgsusdev.camrevert"
    compileSdk {
        version = release(37)
    }

    // NDK/CMake que ya están instalados en el SDK; hace falta para `libpcs_shim.so`
    // (el puente a PPCS_Initialize, ver app/src/main/cpp/ppcs_shim.c).
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.lgsusdev.camrevert"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // El XAPK original de O-KAM Pro solo publica arm64-v8a (ver AGENTS.md, evidencia de fase 1).
        // No existe armeabi-v7a: no hay binarios 32 bits que empaquetar.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // C puro, sin STL: mantiene libpcs_shim.so por debajo de 10 KB.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }

        buildConfigField("String", "CAM_IP", "\"${secret("cam.ip")}\"")
        buildConfigField("String", "CAM_UID", "\"${secret("cam.uid")}\"")
        buildConfigField("String", "CAM_VUID", "\"${secret("cam.vuid")}\"")
        // ID P2P real (17 chars). Es el `did2` que rellena `iface2` (handle+0x90) en
        // `client_create` y, a la vez, el TargetID de `connect` en O-KAM Pro.
        buildConfigField("String", "CAM_P2P_DID", "\"${secret("cam.p2pDid")}\"")
        // Dirección P2P `DAS-…` (`DAS-` + 128 hex = 132 chars). Es el `serverParam` que
        // el SDK valida en `hl_p2p_check_valid` (exige prefijo `DAS-` y longitud ≡ 4
        // mod 16) y en `hl_p2p_connect_async` (exige ≥ 60) antes de montar la sesión;
        // sin ella devuelve −101. La app oficial la obtiene de su nube (`appp2pHost`).
        buildConfigField("String", "CAM_DAS_ADDRESS", "\"${secret("cam.dasAddress")}\"")
        buildConfigField("String", "CAM_USER", "\"${secret("cam.user")}\"")
        buildConfigField("String", "CAM_PASSWORD", "\"${secret("cam.password")}\"")
        // Cuenta EYE4 (la nube del visor oficial). Solo se usa en local para pedir el
        // `loginToken` con el que la cámara autoriza la sesión; sin él todo CGI
        // devuelve `result=-2`. Se queda en `secrets.properties` (ver .gitignore).
        buildConfigField("String", "CAM_ACCOUNT_USER", "\"${secret("cam.accountUser")}\"")
        buildConfigField("String", "CAM_ACCOUNT_PASSWORD", "\"${secret("cam.accountPassword")}\"")
        // Licencia del SDK P2P (InitString de 108 chars que vive en libOKSMARTPPCS.so
        // @ 0x25d10). Es del FABRICANTE, no del usuario, pero no se publica en el
        // repositorio: se inyecta desde `secrets.properties` (`cam.ppcsLicense`) y se
        // pasa al shim como argumento. Sin ella la app compila y el túnel sigue
        // funcionando por `cachedIps`; solo se omite `PPCS_Initialize` (§10.9).
        buildConfigField("String", "CAM_PPCS_LICENSE", "\"${secret("cam.ppcsLicense")}\"")
        buildConfigField("int", "CAM_PORT_FIXED", secrets.getProperty("cam.portFixed", "10555"))
        buildConfigField("int", "CAM_PORT_PTZ", secrets.getProperty("cam.portPtz", "10554"))
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            // Los .so vienen con symbols; sin empaquetado legacy se mapean en memoria (< RAM).
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)

    // Reproductor RTSP de doble cǭmara (bajo consumo: decode por hardware + SurfaceView)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.rtsp)

    // Coroutines para la capa P2P (Dispatchers.IO) y StateFlow/SharedFlow
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
