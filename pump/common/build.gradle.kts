plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.pump.common"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":implementation"))

    testImplementation(project(":shared:tests"))
    testImplementation(project(":shared:impl"))
    testImplementation(project(":core:objects"))

    implementation(libs.com.thoughtworks.xstream)
    implementation(libs.com.google.code.gson)
    implementation(project(":core:keys"))

    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.hilt.compiler)
    ksp(libs.com.google.dagger.android.processor)
}
