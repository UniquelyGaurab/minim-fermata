package com.minim.fermata.sync

import com.minim.fermata.backup.BackupArchive
import com.minim.fermata.backup.CompactCodec
import com.minim.fermata.backup.Jsonl
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test

class ProfileDump {
    @Test
    fun dump() {
        val dir = System.getenv("FERMATA_PROFILE_DIR") ?: return
        for (n in listOf(5_200, 10_000, 50_000)) {
            val events = RealisticHistory.generate(n)
            File(dir, "h$n.jsonl").outputStream().use { Jsonl.write(events, it) }
            val out = ByteArrayOutputStream()
            BackupArchive.write(out, events, RealisticHistory.DEVICE, 1L)
            File(dir, "h$n.minim").writeBytes(out.toByteArray())
            val t0 = System.nanoTime()
            val c = CompactCodec.pack(events)
            val t1 = System.nanoTime()
            val back = CompactCodec.unpack(c)
            val t2 = System.nanoTime()
            val viaJson = Jsonl.decodeGzip(Jsonl.encodeGzip(events))
            val bad = back.indices.firstOrNull { back[it] != viaJson[it] }
            if (bad != null) { println("DIFF ${events[bad]}\nJSON ${viaJson[bad]}\nC1   ${back[bad]}"); error("diff") }
            println("PROFILE same-as-original=${back == events}")
            println("PROFILE $n events=${events.size} f1=${out.size()} c1=${c.size} raw=${CompactCodec.encode(events).size} pack=${(t1 - t0) / 1_000_000}ms unpack=${(t2 - t1) / 1_000_000}ms")
        }
    }
}
