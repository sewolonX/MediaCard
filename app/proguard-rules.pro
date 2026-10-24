# The framework finds this entry through META-INF/xposed/java_init.list.
# Keep the Xposed entry point and names, while allowing R8 to optimize its implementation.
-keep,allowoptimization class module.sewolon.mediacard.MediaCardModule { *; }
# Keep code/resource shrinking enabled while preserving class and member names.
-dontobfuscate
