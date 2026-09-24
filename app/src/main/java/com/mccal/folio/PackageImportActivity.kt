package com.mccal.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mccal.folio.market.MarketFeature
import com.mccal.folio.market.PackageArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A `.foliopkg` file opened or shared with Folio.
 *
 * The file is untrusted and unsigned: it's read off the main thread with a size cap and a timeout, and then the Market
 * shows the same confirm sheet as any other package, with a line saying nobody signed it. Nothing is applied here.
 */
class PackageImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!MarketAccess.isOpen(this)) {
            finish()
            return
        }
        lifecycleScope.launch {
            val bytes = withTimeoutOrNull(4_000) { withContext(Dispatchers.IO) { readPackage(intent) } }
            if (bytes == null) {
                Toast.makeText(this@PackageImportActivity, getString(R.string.that_file_isn_t_a_folio_package), Toast.LENGTH_SHORT).show()
            } else {
                MarketImport.pending = bytes
                startActivity(
                    Intent(this@PackageImportActivity, MainActivity::class.java)
                        .setAction(Intent.ACTION_APPLICATION_PREFERENCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            finish()
        }
    }

    /** Only a stream the user picked, only through a content provider, and only up to a package's size. */
    private fun readPackage(intent: Intent?): ByteArray? {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > PackageArchive.MAX_COMPRESSED) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}

/**
 * A package file waiting to be looked at. Same process, so the bytes don't go through an intent.
 *
 * Compose state rather than a plain field: a file shared while the Market is already open has to be noticed, and one
 * that arrives while Settings is showing some other page mustn't be left sitting here.
 */
internal object MarketImport {
    var pending: ByteArray? by androidx.compose.runtime.mutableStateOf<ByteArray?>(null)
}
