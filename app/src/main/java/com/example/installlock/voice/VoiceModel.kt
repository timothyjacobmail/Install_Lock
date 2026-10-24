package com.example.installlock.voice

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

object VoiceModel {
    @Synchronized fun prepare(c: Context): String {
        val root = File(c.filesDir, "voice-model-v1")
        val marker = File(root, "ready")
        if (!marker.exists()) {
            root.mkdirs()
            ZipInputStream(c.assets.open("voice/model-en-us.zip")).use { zip ->
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name.substringAfter('/', "")
                    if (path.isEmpty()) continue
                    val out = File(root, path)
                    require(out.canonicalPath.startsWith(root.canonicalPath + File.separator))
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { stream ->
                            val buffer = ByteArray(8192)
                            while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total < 250_000_000); stream.write(buffer, 0, n) }
                        }
                    }
                }
            }
            require(File(root, "am/final.mdl").isFile)
            marker.writeText("1")
        }
        return root.path
    }
}
