# Add project specific ProGuard rules here.
# You can control the set of set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Suppress warnings for Java Beans classes that SnakeYAML tries to use
# but aren't available on Android
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

# Suppress hidden API warnings from GeckoView
-dontwarn org.mozilla.gecko.**
-dontwarn org.mozilla.geckoview.**

# Keep SnakeYAML classes that GeckoView needs
-keep class org.yaml.snakeyaml.** { *; }
-keep class org.yaml.snakeyaml.LoaderOptions { *; }
-keep class org.yaml.snakeyaml.TypeDescription { *; }
-keep class org.yaml.snakeyaml.Yaml { *; }
-keep class org.yaml.snakeyaml.constructor.BaseConstructor { *; }
-keep class org.yaml.snakeyaml.constructor.Constructor { *; }
-keep class org.yaml.snakeyaml.error.YAMLException { *; }

# Keep our conversation API classes
-keep class app.gomuks.android.ConversationManager { *; }
-keep class app.gomuks.android.PushData { *; }
-keep class app.gomuks.android.PushMessage { *; }
-keep class app.gomuks.android.PushUser { *; }
-keep class app.gomuks.android.RoomType { *; }

# Keep GeckoView classes
-keep class org.mozilla.geckoview.** { *; }
