# R8 keeps for the places this app hands a name to something that is not renamed
# alongside it: the JNI bridge and Room's generated code.
#
# The default proguard-android-optimize.txt already carries
# `-keepclasseswithmembernames class * { native <methods>; }`, which covers
# OnDeviceTranscriber's three `external` declarations — whisper.cpp finds them by
# the symbol Java_com_harken_android_speech_OnDeviceTranscriber_nativeLoadModel,
# so both the class and the method names have to survive. It is repeated here
# scoped to this app so a future change to the default file cannot silently take
# the JNI bridge with it; a renamed native method fails at first decode with
# UnsatisfiedLinkError and nowhere earlier.
-keepclasseswithmembernames,includedescriptorclasses class com.harken.android.** {
    native <methods>;
}

# No rule is needed for the JSON whisper returns. The first attempt at enabling R8
# failed there — Gson reflected on a class R8 had merged, and the release build
# reported "Abstract classes can't be instantiated ... Class name: n2.f" while debug
# was fine. OnDeviceTranscriber now reads the two fields with org.json, so there is
# no reflection left to keep and Gson is off the classpath entirely.

# Room's generated implementations are looked up by name from the generated
# database class (`AppDatabase_Impl`). room-runtime ships its own consumer rules;
# this is the belt to their braces, because a missed DAO is a crash at first
# query on a build that compiled and unit-tested clean.
-keep class com.harken.android.data.**_Impl { *; }

# Line numbers in a release stack trace, which are otherwise the first thing
# minification takes and the one thing a crash report cannot do without.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
