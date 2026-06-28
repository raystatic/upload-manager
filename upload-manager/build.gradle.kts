plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    `maven-publish`
}

android {
    namespace = "dev.uploadmanager"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Publish a single release variant with sources, consumable by other apps.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // Run Android Lint without failing the build; surface issues as reports.
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
}

group = "dev.uploadmanager"
version = "0.1.1"

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "upload-manager"
            pom {
                name.set("Upload Manager SDK")
                description.set("Reliable, resumable, battery-aware Firebase upload manager for Android.")
                url.set("https://github.com/raystatic/upload-manager")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("raystatic")
                        name.set("Upload Manager SDK contributors")
                    }
                }
                scm {
                    url.set("https://github.com/raystatic/upload-manager")
                    connection.set("scm:git:https://github.com/raystatic/upload-manager.git")
                    developerConnection.set("scm:git:ssh://git@github.com/raystatic/upload-manager.git")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.storage)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.work.testing)
}
