package com.mccal.folio

import androidx.test.platform.app.InstrumentationRegistry

/** Package names at run time: debug builds are "com.mccal.folio.dev" (tests ".dev.test"), so tests never hard-code them. */
object FolioTestPackages {
    val app: String get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    val test: String get() = InstrumentationRegistry.getInstrumentation().context.packageName
}
