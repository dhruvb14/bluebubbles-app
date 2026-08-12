import 'package:bluebubbles/database/database.dart';
import 'package:bluebubbles/generated/objectbox.g.dart';
import 'package:bluebubbles/services/services.dart';
import 'package:bluebubbles/utils/logger/logger.dart';
import 'package:flutter/foundation.dart';
import 'package:objectbox/objectbox.dart';

@Entity()
class FCMData {
  int? id;
  String? projectID;
  String? storageBucket;
  String? apiKey;
  String? firebaseURL;
  String? clientID;
  String? applicationID;

  FCMData({
    this.id,
    this.projectID,
    this.storageBucket,
    this.apiKey,
    this.firebaseURL,
    this.clientID,
    this.applicationID,
  });

  factory FCMData.fromMap(Map<String, dynamic> json) {
    Map<String, dynamic> projectInfo = json["project_info"];
    Map<String, dynamic> client = json["client"][0];
    String clientID = client["oauth_client"][0]["client_id"];
    return FCMData(
      projectID: projectInfo["project_id"],
      storageBucket: projectInfo["storage_bucket"],
      apiKey: client["api_key"][0]["current_key"],
      firebaseURL: projectInfo["firebase_url"],
      clientID: clientID.contains("-") ? clientID.substring(0, clientID.indexOf("-")) : clientID,
      applicationID: client["client_info"]["mobilesdk_app_id"],
    );
  }

  /// Persists the row and the SharedPreferences mirror the Android `firebase-auth` handler
  /// falls back on.
  ///
  /// The mirror write is always awaited. It used to be a detached future unless the caller
  /// passed `wait: true`, which meant database migration 4 — whose entire job is to create
  /// the mirror for installs that predate it — could durably bump `dbVersion` before the
  /// write landed. The migration never re-runs after that, leaving the row and the mirror
  /// permanently out of step.
  Future<FCMData> save() async {
    if (kIsWeb) return this;

    // `saveConfig` removes a key rather than storing null, so persisting a half-parsed
    // config (an unexpected shape from the server sends `FCMData.fromMap` home with nulls)
    // would delete a working apiKey/applicationID from both stores. Keep the last good one
    // instead — these two are the only values Firebase cannot be initialized without.
    if (apiKey == null || applicationID == null) {
      Logger.warn("Refusing to save FCM data with no apiKey/applicationID", tag: 'FCMData');
      return this;
    }

    List<FCMData> data = Database.fcmData.getAll();
    if (data.length > 1) data.removeRange(1, data.length); // These were being ignored anyway
    id = !Database.fcmData.isEmpty() ? data.first.id : null;
    Database.fcmData.put(this);
    await PrefsSvc.firebase.saveConfig(
      projectID: projectID,
      storageBucket: storageBucket,
      apiKey: apiKey,
      firebaseURL: firebaseURL,
      clientID: clientID,
      applicationID: applicationID,
    );

    SettingsSvc.fcmData = this;
    return this;
  }

  static Future<void> deleteFcmData() async {
    Database.fcmData.removeAll();
    await PrefsSvc.firebase.clearConfig();
    SettingsSvc.fcmData = FCMData();
  }

  static FCMData getFCM() {
    final result = Database.fcmData.getAll();
    if (result.isEmpty) {
      return FCMData(
        projectID: PrefsSvc.firebase.getProjectID(),
        storageBucket: PrefsSvc.firebase.getStorageBucket(),
        apiKey: PrefsSvc.firebase.getApiKey(),
        firebaseURL: PrefsSvc.firebase.getFirebaseURL(),
        clientID: PrefsSvc.firebase.getClientID(),
        applicationID: PrefsSvc.firebase.getApplicationID(),
      );
    }
    return result.first;
  }

  Map<String, dynamic> toMap() => {
        "project_id": projectID,
        "storage_bucket": storageBucket,
        "api_key": apiKey,
        "firebase_url": firebaseURL,
        "client_id": clientID,
        "application_id": applicationID,
      };

  bool get isNull =>
      projectID == null || storageBucket == null || apiKey == null || clientID == null || applicationID == null;
}
