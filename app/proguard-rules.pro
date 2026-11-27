-keep class com.example.waevirmenasistan.MainActivity { *; }
-keep class com.example.waevirmenasistan.accessibility.WhatsAppTranslatorAccessibilityService { *; }
-keep class com.example.waevirmenasistan.update.ApkFileProvider { *; }
-keepclassmembers class * {
    public void *(android.view.View);
}
