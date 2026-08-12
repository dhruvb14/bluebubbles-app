package com.bluebubbles.messaging.services.firebase

import android.content.Context
import com.bluebubbles.messaging.Constants
import com.bluebubbles.messaging.models.MethodCallHandlerImpl
import com.bluebubbles.messaging.utils.PersistentLog
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class FirebaseAuthHandler: MethodCallHandlerImpl() {
    companion object {
        const val tag: String = "firebase-auth"
        const val preferencesFile: String = "FlutterSharedPreferences"
        var firebaseApp: FirebaseApp? = null
    }

    override fun handleMethodCall(
        call: MethodCall,
        result: MethodChannel.Result,
        context: Context
    ) {
        val prefs = context.getSharedPreferences(preferencesFile, 0)

        // Dart sends the whole config along with the call and separately mirrors it into
        // FlutterSharedPreferences. Prefer the arguments — they come straight off the FCMData
        // row being registered — and fall back to the mirror only when the call carries
        // nothing usable. An absent config is an ordinary state (Firebase not set up on the
        // server yet, or a mirror that drifted from the row), so it has to come back as an
        // error the Dart side can act on rather than as a thrown exception.
        val resolution = FirebaseConfigResolver.resolve(call.arguments as? Map<*, *>) { key ->
            prefs.getString(key, null)
        }

        val config = when (resolution) {
            is FirebaseConfigResult.Incomplete -> {
                val error = "Firebase configuration is incomplete! Missing: ${resolution.missing.joinToString(", ")}"
                PersistentLog.e(context, Constants.logTag, error)
                result.error("500", error, null)
                return
            }
            is FirebaseConfigResult.Resolved -> resolution.config
        }

        // Put the mirror back in step with what Dart just sent. Nothing else repairs it once
        // it drifts, and it is what every fallback read depends on.
        if (resolution.source == FirebaseConfigSource.ARGUMENTS) {
            val editor = prefs.edit()
            for ((key, value) in FirebaseConfigResolver.mirrorValues(config)) {
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            editor.apply()
        }

        // Don't auth multiple times, unless the stored config no longer matches what the
        // existing FirebaseApp was initialized with (e.g. the user pointed at a different
        // server backed by a different Firebase project). FirebaseApp is a process-wide
        // singleton that Firebase's own APIs never re-configure in place, so a stale
        // instance has to be explicitly torn down before we can initialize a fresh one
        // with the current config.
        try {
            val existing = FirebaseApp.getInstance()
            val options = existing.options
            val unchanged = options.apiKey == config.apiKey &&
                    options.applicationId == config.applicationId &&
                    options.projectId == config.projectId &&
                    options.storageBucket == config.storageBucket &&
                    options.databaseUrl == config.databaseUrl &&
                    options.gcmSenderId == config.gcmSenderId
            if (unchanged) {
                PersistentLog.d(context, Constants.logTag, "Firebase has already been initialized with the current config!")
                FirebaseCloudMessagingTokenHandler().getToken(context, result)
                return
            }

            PersistentLog.d(context, Constants.logTag, "Firebase config has changed since the last init, reinitializing...")
            existing.delete()
            firebaseApp = null
        } catch (_: IllegalStateException) {}

        // Make sure Google Services are available
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
            val error = "Google Play Services is not available!"
            PersistentLog.e(context, Constants.logTag, error)
            result.error("500", error, null)
            return
        }

        PersistentLog.d(context, Constants.logTag, "Authenticating client ${config.applicationId} with Firebase...")
        // Get a FirebaseApp (manually provide config since we fetch it dynamically)
        firebaseApp = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setApiKey(config.apiKey)
            .setApplicationId(config.applicationId)
            .setDatabaseUrl(config.databaseUrl)
            .setGcmSenderId(config.gcmSenderId)
            .setProjectId(config.projectId)
            .setStorageBucket(config.storageBucket)
            .build()
        )

        // Set up Firestore / Realtime DB listeners for server URL changes
        // databaseUrl null indicates Cloud Firestore setup
        PersistentLog.d(context, Constants.logTag, "Setting Firebase database listeners...")
        if (config.databaseUrl == null) {
            FirebaseFirestore.getInstance().collection("server").document("config").addSnapshotListener(FirestoreDatabaseListener())
        } else {
            FirebaseDatabase.getInstance().getReference("config").addValueEventListener(RealtimeDatabaseListener())
        }

        FirebaseCloudMessagingTokenHandler().getToken(context, result)
    }
}
