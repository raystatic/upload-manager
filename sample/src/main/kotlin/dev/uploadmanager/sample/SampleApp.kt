package dev.uploadmanager.sample

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import dev.uploadmanager.UploadManager

/**
 * Two modes, chosen automatically at build time:
 *
 * - **Demo (default):** no `google-services.json` → talks to the local Firebase
 *   Emulator Suite (`firebase emulators:start` in `firebase/`). This is what CI
 *   and local CUJ testing use.
 * - **Production:** drop a real `google-services.json` into the `sample/` module
 *   → the google-services Gradle plugin generates the real config, this skips the
 *   emulator wiring, and uploads go to your actual Firebase project.
 *
 * The SDK itself is identical in both modes — only Firebase initialisation differs,
 * and that is the host app's responsibility, never the SDK's.
 */
class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.USE_EMULATOR) {
            initialiseForEmulator()
        } else {
            // Reads google-services.json via the applied plugin — real project.
            FirebaseApp.initializeApp(this)
            // Production tip: install Firebase App Check here (e.g. Play Integrity)
            // before enqueuing, if you require request-integrity enforcement.
        }

        // The SDK is configured the same way regardless of backend. The sample uses
        // the in-app preset selector; a real app passes its own UploadManagerConfig.
        UploadManager.initialise(this, Preset.current(this).toConfig())
    }

    private fun initialiseForEmulator() {
        val options = FirebaseOptions.Builder()
            .setProjectId("demo-upload-manager")
            .setApplicationId("1:123456789012:android:demo")
            .setApiKey("fake-api-key-for-emulator")
            .setStorageBucket("demo-upload-manager.appspot.com")
            .build()
        FirebaseApp.initializeApp(this, options)

        // 10.0.2.2 reaches the host machine from the Android emulator.
        FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, 9099)
        FirebaseFirestore.getInstance().useEmulator(EMULATOR_HOST, 8080)
        FirebaseStorage.getInstance().useEmulator(EMULATOR_HOST, 9199)
    }

    private companion object {
        const val EMULATOR_HOST = "10.0.2.2"
    }
}
