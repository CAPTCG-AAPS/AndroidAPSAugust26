import java.util.Base64

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.pump.omnipod.common"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Optional build-time Omnipod 5 credential. Drop a credential string (packed or the
        // o5keypair JSON) into pump/omnipod/common/o5credential.txt and it is base64-encoded
        // into BuildConfig.O5_EMBEDDED_CREDENTIAL, which the app seeds on a fresh install (see
        // SecureO5RegistrationStorage.seedEmbeddedCredentialIfNeeded). To bake in several and
        // have each fresh install pick a random one, put multiple credentials in the file
        // separated by a blank line. The file holds PRIVATE KEYS and is git-ignored - never
        // commit it; see o5credential.txt.example for the format. When it is absent the field is
        // empty and nothing is embedded, so builds without the file behave exactly as before.
        val credentialFile = project.file("o5credential.txt")
        val embeddedCredential = if (credentialFile.exists())
            Base64.getEncoder().encodeToString(credentialFile.readText().trim().toByteArray(Charsets.UTF_8))
        else ""
        buildConfigField("String", "O5_EMBEDDED_CREDENTIAL", "\"$embeddedCredential\"")
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))

    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.hilt.navigation.compose)
    // WebViewCompat.addWebMessageListener, for the key manager page to hand back a certificate.
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.rx3)

    testImplementation(project(":shared:tests"))

    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.hilt.compiler)
    ksp(libs.com.google.dagger.android.processor)
}
