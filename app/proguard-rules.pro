# DiscoverBounds resolves the public Window Extensions API by class and member name so it can
# retain the split-host fallback on devices whose extension implementation differs. R8 cannot
# infer these references from the strings used by Class.forName/getMethod/Proxy.
-keep class androidx.window.extensions.** { *; }

# Fold8Duo: the privileged fold engine is started BY NAME from outside the app — `app_process` over ADB
# (FoldEngineMain.main) and a Shizuku UserService (instantiated reflectively in the shell-uid process) — so R8 sees no
# caller and would strip or rename it.
-keep class com.mccal.folio.priv.FoldEngineMain { public static void main(java.lang.String[]); }
-keep class com.mccal.folio.priv.FoldPrivilegedService { *; }

# Android manifest components and directly constructed widget-host classes are traced by AGP/R8.
# Layout and backup persistence use org.json with explicit keys, so there are no model classes
# that require broad reflection or serialization keep rules.
