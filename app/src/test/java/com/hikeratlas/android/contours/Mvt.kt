package com.hikeratlas.android.contours

/** Reads back what MvtLayer writes, for tests. */
object Mvt {
    data class Feature(val layer: String, val props: Map<String, Long>, val lines: List<IntArray>)

    private class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long {
            var r = 0L; var s = 0
            while (true) { val x = b[p++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r; s += 7 }
        }
        fun sub(): Reader { val n = varint().toInt(); return Reader(b, p, p + n).also { p += n } }
        fun packed(): List<Long> { val r = sub(); val out = ArrayList<Long>(); while (r.more()) out += r.varint(); return out }
    }

    fun decode(tile: ByteArray): List<Feature> {
        val out = ArrayList<Feature>()
        val t = Reader(tile)
        while (t.more()) {
            val key = t.varint().toInt()
            require(key == 0x1a) { "expected a layer" }
            val l = t.sub()
            var name = ""
            val keys = ArrayList<String>(); val values = ArrayList<Long>()
            val raw = ArrayList<Pair<List<Long>, List<Long>>>()
            while (l.more()) {
                when (val k = l.varint().toInt()) {
                    (1 shl 3) or 2 -> { val s = l.sub(); name = String(s.b, s.p, s.end - s.p) }
                    (2 shl 3) or 2 -> {
                        val f = l.sub(); var tags = emptyList<Long>(); var geom = emptyList<Long>()
                        while (f.more()) when (f.varint().toInt()) {
                            (2 shl 3) or 2 -> tags = f.packed()
                            (3 shl 3) -> f.varint()
                            (4 shl 3) or 2 -> geom = f.packed()
                            else -> error("feature field")
                        }
                        raw += tags to geom
                    }
                    (3 shl 3) or 2 -> { val s = l.sub(); keys += String(s.b, s.p, s.end - s.p) }
                    (4 shl 3) or 2 -> { val v = l.sub(); require(v.varint().toInt() == (6 shl 3)); val z = v.varint(); values += (z ushr 1) xor -(z and 1) }
                    else -> l.varint().also { require(k and 7 == 0) }
                }
            }
            for ((tags, geom) in raw) {
                val props = tags.chunked(2).associate { (k, v) -> keys[k.toInt()] to values[v.toInt()] }
                val lines = ArrayList<IntArray>(); var cur = ArrayList<Int>(); var x = 0; var y = 0; var i = 0
                fun unzz(v: Long) = ((v ushr 1) xor -(v and 1)).toInt()
                while (i < geom.size) {
                    val cmd = (geom[i] and 7).toInt(); val count = (geom[i] ushr 3).toInt(); i++
                    if (cmd == 1) { if (cur.isNotEmpty()) lines += cur.toIntArray(); cur = ArrayList() }
                    repeat(count) { x += unzz(geom[i]); y += unzz(geom[i + 1]); i += 2; cur += x; cur += y }
                }
                if (cur.isNotEmpty()) lines += cur.toIntArray()
                out += Feature(name, props, lines)
            }
        }
        return out
    }
}
