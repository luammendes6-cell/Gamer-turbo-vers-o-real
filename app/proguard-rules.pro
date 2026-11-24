# O JavaScript bridge é chamado pelo WebView por meio das anotações @JavascriptInterface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Preserva os serviços Android declarados no Manifest.
-keep class com.gamerturbo.mobile.MainActivity { *; }
-keep class com.gamerturbo.mobile.GameOverlayService { *; }
-keep class com.gamerturbo.mobile.ScreenRecordService { *; }

# O Shizuku UserService é carregado pelo nome da classe em outro processo.
-keep class com.gamerturbo.mobile.GamerTurboUserService { *; }
-keep interface com.gamerturbo.mobile.IGamerTurboUserService { *; }
-keep class com.gamerturbo.mobile.IGamerTurboUserService$Stub { *; }
-keep class rikka.shizuku.** { *; }

# Preserve exact bridge class name and public bridge methods accessed from JS.
-keep class com.gamerturbo.mobile.MainActivity$GamerTurboBridge { public *; }
