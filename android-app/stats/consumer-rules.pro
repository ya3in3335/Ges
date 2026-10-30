# kotlinx.serialization models of the census API
-keep,includedescriptorclasses class dz.association.stats.data.**$$serializer { *; }
-keepclassmembers class dz.association.stats.data.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
