# libxposed API 102 module entry (see https://github.com/libxposed/api#integration).
# LSPosed instantiates the class named in META-INF/xposed/java_init.list by reflection, so keep the entry class and
# its public no-arg constructor. The name is kept too (no allowobfuscation), so java_init.list never needs rewriting.
-dontwarn io.github.libxposed.annotation.**
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
