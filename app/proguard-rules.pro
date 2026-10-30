# Serialization generates serializers; retain metadata needed by their lookup.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class **$$serializer { *; }

# WorkManager/Room instantiate the generated database by its conventional class name.
-keep class androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl { public <init>(); }
-keep,allowoptimization class cloud.ohiofiles.app.ui.screens.nativefeatures.NativeNotificationWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-keep,allowoptimization class cloud.ohiofiles.app.data.upload.UploadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
