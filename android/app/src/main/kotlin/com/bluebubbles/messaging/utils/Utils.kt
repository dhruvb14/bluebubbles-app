package com.bluebubbles.messaging.utils

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.core.graphics.drawable.IconCompat
import com.bluebubbles.messaging.Constants
import com.bluebubbles.messaging.services.firebase.FirebaseAuthHandler
import com.bluebubbles.messaging.services.firebase.ServerUrlRequestHandler
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

object Utils {
    fun getAdaptiveIconFromByteArray(data: ByteArray): IconCompat {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        // Scale the bitmap to 108x108dp to comply with adaptive icon guidelines
        // Start by scaling the inner image to 72x72dp
        var width = bitmap.width
        var height = bitmap.height
        // Float division — integer division truncates to 0 for portrait images,
        // which then causes a divide-by-zero when computing the other dimension.
        val aspectRatio = width.toFloat() / height.toFloat()
        if (aspectRatio > 1f) {
            width = (72 * Resources.getSystem().displayMetrics.density).toInt()
            height = (width / aspectRatio).toInt()
        } else {
            height = (72 * Resources.getSystem().displayMetrics.density).toInt()
            width = (height * aspectRatio).toInt()
        }
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        // Add transparent padding to achieve 108x108dp
        val padding = ((108 - 72) * Resources.getSystem().displayMetrics.density).toInt();
        val adaptiveBitmap = Bitmap.createBitmap(scaledBitmap.width + padding, scaledBitmap.height + padding, Bitmap.Config.ARGB_8888)
        val tempCanvas = Canvas(adaptiveBitmap)
        tempCanvas.drawBitmap(scaledBitmap, (padding / 2).toFloat(), (padding / 2).toFloat(), null)
        return IconCompat.createWithAdaptiveBitmap(adaptiveBitmap)
    }

    /// Resolves the server URL through Firebase.
    ///
    /// Callers are an FCM service and two BroadcastReceivers, none of which go through
    /// `MethodCallHandler.methodCallHandler` — so unlike every method channel invocation,
    /// nothing here contains a thrown exception and one would take the whole process down.
    /// No config is passed with the call either, so [FirebaseAuthHandler] resolves it from
    /// the SharedPreferences mirror; a missing mirror now comes back through `error`.
    fun getServerUrl(context: Context, result: MethodChannel.Result) {
        try {
            FirebaseAuthHandler().handleMethodCall(MethodCall("", null), object : MethodChannel.Result {
                override fun success(temp: Any?) {
                    ServerUrlRequestHandler().handleMethodCall(MethodCall("", null), result, context)
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    PersistentLog.e(context, Constants.logTag, "Could not authenticate with Firebase to resolve the server URL: $errorMessage")
                    result.error(errorCode, errorMessage, errorDetails)
                }

                override fun notImplemented() {
                    result.notImplemented()
                }
            }, context)
        } catch (e: Exception) {
            PersistentLog.e(context, Constants.logTag, "Failed to resolve the server URL", e)
            result.error("500", "Failed to resolve the server URL", e.toString())
        }
    }
}