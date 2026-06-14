package dev.uploadmanager.sample

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import dev.uploadmanager.UploadManager

/**
 * Runs against the Firebase Emulator Suite by default (no google-services.json
 * needed): `firebase emulators:start` from the repo's firebase/ directory.
 * To target a real Firebase project, replace the demo options below with your
 * project's values (or wire up the google-services Gradle plugin).
 */
class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()

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

        // The active CUJ preset (chosen in-app, persisted across the restart).
        UploadManager.initialise(this, Preset.current(this).toConfig())
    }

    companion object {
        const val EMULATOR_HOST = "10.0.2.2"
    }
}
