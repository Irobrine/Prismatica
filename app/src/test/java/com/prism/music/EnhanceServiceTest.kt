package com.prism.music

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A batch of songs runs for an hour, so it has to outlive the screen: the queue's foreground service is declared in the
 * manifest (Android refuses to start one that isn't) and started by the queue itself.
 */
class EnhanceServiceTest {
    @Test fun theQueueHasAForegroundServiceOfItsOwn() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val entry = Regex("""<service\s+android:name="\.data\.enhance\.EnhanceService"[\s\S]*?/>""")
            .find(manifest)?.value
        assertNotNull("app/src/main/AndroidManifest.xml has no entry for EnhanceService", entry)
        assertTrue("only Prism starts it: $entry", entry!!.contains("android:exported=\"false\""))
        assertTrue("Android 14+ needs its type: $entry", entry.contains("android:foregroundServiceType=\"dataSync\""))
    }

    @Test fun theQueueStartsIt() {
        val source = File("src/main/java/com/prism/music/data/enhance/EnhancementService.kt").readText()
        assertTrue("the worker must start the service that keeps it alive", source.contains("EnhanceService.start("))
    }
}
