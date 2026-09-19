package rs.zylos.novisad.map

import android.content.Context
import java.io.File

class MbtilesStore(private val context: Context) {
    fun ensureLocalFile(): File {
        val dest = File(File(context.filesDir, "maps"), MapDefaults.MBTILES_ASSET)
        if (dest.exists() && dest.length() > 0L) {
            return dest
        }
        dest.parentFile?.mkdirs()
        context.assets.open(MapDefaults.MBTILES_ASSET).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }

    fun hasAsset(): Boolean {
        return context.assets.list("")?.contains(MapDefaults.MBTILES_ASSET) == true
    }
}
