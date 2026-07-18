# R8/ProGuard-правила release-сборки (M8). Compose/Room/Coil/exifinterface приносят свои consumer-
# правила через AAR — здесь только то, что R8 не может вывести сам.

# --- kotlinx.serialization: бэкап (.psbackup, M7) ---
# @Serializable-DTO сериализуются через генерированные $$serializer; без keep-правил R8 их
# переименует/выкинет и импорт бэкапа тихо сломается — прямая потеря данных пользователя. DTO бэкапа
# немногочисленны, поэтому держим весь пакет целиком (цена по размеру ничтожна, риск исключён).
-keep class ru.papasheets.exportkit.backup.** { *; }
-keepclassmembers class ru.papasheets.exportkit.backup.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Общие правила kotlinx.serialization (официальные): companion serializer() и аннотации.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,*Annotation*,InnerClasses
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }
