# Keep the public SDK surface + manifest-referenced components (referenced reflectively / by manifest).
-keep class sa.arsel.core.Arsel { *; }
-keep class sa.arsel.core.Arsel$* { *; }
-keep class sa.arsel.core.ArselConfig { *; }
-keep class sa.arsel.core.ArselConfig$Builder { *; }
-keep class sa.arsel.core.notification.NotificationTapActivity { *; }
-keep class sa.arsel.core.notification.NotificationPermission { *; }
-keep enum sa.arsel.core.model.** { *; }
# WorkManager instantiates the worker reflectively.
-keep class sa.arsel.core.net.PushSyncWorker { *; }
