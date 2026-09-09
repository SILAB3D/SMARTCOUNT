import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/**
 * `versionCode` = número de commits.
 *
 * Android sólo acepta actualizar a un versionCode estrictamente mayor. Derivarlo
 * de la versión semántica obliga a acordarse de subirla en cada push, y el día
 * que se olvida el canal deja de funcionar en silencio. Con los commits crece
 * solo.
 *
 * **Contrapartida:** no se puede reescribir la historia de main. Un rebase o un
 * push --force que reduzca el número de commits deja las releases nuevas por
 * debajo de lo ya instalado, y dejan de verse como actualizaciones.
 */
fun gitCommitCount(): Int = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output.toIntOrNull() ?: 1
} catch (e: Exception) {
    logger.warn("SmartCount: no se pudo leer el historial de git ($e). versionCode = 1.")
    1
}

/**
 * Suelo de seguridad: ninguna release puede quedar por debajo del versionCode ya
 * publicado. Sin esto, un checkout superficial en CI daría 1 y el fallo sería mudo.
 */
val versionCodeFloor = 2
val smartCountVersionCode = maxOf(gitCommitCount(), versionCodeFloor)

/**
 * Firma de release. Android identifica una app por applicationId + firma: una APK
 * firmada con otra clave no es una actualización sino otra app distinta, y la
 * instalación falla con INSTALL_FAILED_UPDATE_INCOMPATIBLE.
 *
 * En local, los datos salen de `keystore.properties` (fuera de git). En CI, de
 * variables de entorno que el workflow reconstruye desde los secrets.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

fun signingValue(property: String, env: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "SMARTCOUNT_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "SMARTCOUNT_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SMARTCOUNT_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "SMARTCOUNT_KEY_PASSWORD")
val canSignRelease = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() } && file(releaseStoreFile!!).exists()

android {
    namespace = "com.silab.smartcount"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.silab.smartcount"
        minSdk = 26
        targetSdk = 35
        versionCode = smartCountVersionCode
        versionName = "0.1.0"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "SmartCount: sin keystore de release. La APK saldrá sin firmar y no " +
                        "se podrá instalar como actualización. Crea keystore.properties " +
                        "o define las variables SMARTCOUNT_STORE_FILE, _STORE_PASSWORD, " +
                        "_KEY_ALIAS y _KEY_PASSWORD."
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Widgets de pantalla de inicio escritos en Compose
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    testImplementation("junit:junit:4.13.2")
}
