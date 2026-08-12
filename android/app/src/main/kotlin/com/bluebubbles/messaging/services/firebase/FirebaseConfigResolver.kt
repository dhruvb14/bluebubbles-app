package com.bluebubbles.messaging.services.firebase

/// Where a resolved [FirebaseConfig] was read from.
enum class FirebaseConfigSource { ARGUMENTS, PREFERENCES }

/// The Firebase project configuration needed to build a `FirebaseOptions`.
///
/// [apiKey] and [applicationId] are the only two values `FirebaseOptions.Builder` refuses
/// to accept as null. The rest are legitimately absent for some projects — `databaseUrl`
/// is null for every Cloud Firestore setup, for instance — so they stay nullable here.
data class FirebaseConfig(
    val apiKey: String,
    val applicationId: String,
    val projectId: String?,
    val storageBucket: String?,
    val databaseUrl: String?,
    val gcmSenderId: String?,
)

sealed class FirebaseConfigResult {
    data class Resolved(val config: FirebaseConfig, val source: FirebaseConfigSource) : FirebaseConfigResult()

    /// Neither transport carried a usable config. [missing] names the required values that
    /// could not be found, using their SharedPreferences key names.
    data class Incomplete(val missing: List<String>) : FirebaseConfigResult()
}

/// Resolves the Firebase config for [FirebaseAuthHandler] from the two transports that
/// carry it, without touching Android APIs so it can be unit tested on the JVM.
///
/// 1. The `firebase-auth` method call arguments — snake_case keys, built by
///    `FCMData.toMap()` in `lib/database/io/fcm_data.dart`. This is the authoritative
///    copy: the caller reads it straight off the `FCMData` row it is registering with.
/// 2. The `FlutterSharedPreferences` mirror — camelCase keys, written by
///    `SharedPreferencesFirebaseActions` in
///    `lib/services/backend/settings/actions/shared_preferences_firebase_actions.dart`.
///    Only a mirror, and nothing guarantees it is in step with the row.
///
/// The two are resolved whole rather than field-by-field. Mixing them could splice an
/// `apiKey` from one Firebase project onto an `applicationId` from another, which fails
/// far more confusingly than a missing value does.
object FirebaseConfigResolver {
    // Method call argument keys — must match FCMData.toMap().
    const val ARG_API_KEY = "api_key"
    const val ARG_APPLICATION_ID = "application_id"
    const val ARG_PROJECT_ID = "project_id"
    const val ARG_STORAGE_BUCKET = "storage_bucket"
    const val ARG_DATABASE_URL = "firebase_url"
    const val ARG_GCM_SENDER_ID = "client_id"

    // SharedPreferences mirror keys — must match SharedPreferencesFirebaseActions.
    const val PREF_API_KEY = "apiKey"
    const val PREF_APPLICATION_ID = "applicationID"
    const val PREF_PROJECT_ID = "projectID"
    const val PREF_STORAGE_BUCKET = "storageBucket"
    const val PREF_DATABASE_URL = "firebaseURL"
    const val PREF_GCM_SENDER_ID = "clientID"

    /// [preference] looks a key up in the `FlutterSharedPreferences` store.
    fun resolve(arguments: Map<*, *>?, preference: (String) -> String?): FirebaseConfigResult {
        fromArguments(arguments)?.let { return FirebaseConfigResult.Resolved(it, FirebaseConfigSource.ARGUMENTS) }
        fromPreferences(preference)?.let { return FirebaseConfigResult.Resolved(it, FirebaseConfigSource.PREFERENCES) }

        val missing = mutableListOf<String>()
        if (preference(PREF_API_KEY).normalized() == null) missing.add(PREF_API_KEY)
        if (preference(PREF_APPLICATION_ID).normalized() == null) missing.add(PREF_APPLICATION_ID)
        return FirebaseConfigResult.Incomplete(missing)
    }

    /// The mirror entries that represent [config], so a caller can write the store back
    /// into step. A null value means the key should be removed rather than stored.
    fun mirrorValues(config: FirebaseConfig): Map<String, String?> = linkedMapOf(
        PREF_API_KEY to config.apiKey,
        PREF_APPLICATION_ID to config.applicationId,
        PREF_PROJECT_ID to config.projectId,
        PREF_STORAGE_BUCKET to config.storageBucket,
        PREF_DATABASE_URL to config.databaseUrl,
        PREF_GCM_SENDER_ID to config.gcmSenderId,
    )

    private fun fromArguments(arguments: Map<*, *>?): FirebaseConfig? {
        if (arguments == null) return null
        fun read(key: String): String? = (arguments[key] as? String).normalized()

        val apiKey = read(ARG_API_KEY) ?: return null
        val applicationId = read(ARG_APPLICATION_ID) ?: return null
        return FirebaseConfig(
            apiKey = apiKey,
            applicationId = applicationId,
            projectId = read(ARG_PROJECT_ID),
            storageBucket = read(ARG_STORAGE_BUCKET),
            databaseUrl = read(ARG_DATABASE_URL),
            gcmSenderId = read(ARG_GCM_SENDER_ID),
        )
    }

    private fun fromPreferences(preference: (String) -> String?): FirebaseConfig? {
        val apiKey = preference(PREF_API_KEY).normalized() ?: return null
        val applicationId = preference(PREF_APPLICATION_ID).normalized() ?: return null
        return FirebaseConfig(
            apiKey = apiKey,
            applicationId = applicationId,
            projectId = preference(PREF_PROJECT_ID).normalized(),
            storageBucket = preference(PREF_STORAGE_BUCKET).normalized(),
            databaseUrl = preference(PREF_DATABASE_URL).normalized(),
            gcmSenderId = preference(PREF_GCM_SENDER_ID).normalized(),
        )
    }

    /// Blank is treated as absent — an empty string in the store or on the wire is never a
    /// usable credential, and letting one through only moves the failure to Firebase.
    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
