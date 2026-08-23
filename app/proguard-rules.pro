# proguard-rules.pro
# 本项目 release 未开启混淆(minifyEnabled false)，此文件为占位与保险。
# 若日后开启混淆，请把下列类 keep 住（WebView JS 桥、Room、前台服务）：

# 保留 JS 桥接口类（addJavascriptInterface 反射调用，混淆会丢方法）
# -keepclassmembers class com.hidden.dictation.bridge.** { *; }

# 保留 Room 实体与 DAO
# -keep class com.hidden.dictation.db.** { *; }

# 保留前台服务/无障碍服务（系统反射实例化）
# -keep public class * extends android.app.Service
# -keep public class * extends android.accessibilityservice.AccessibilityService
