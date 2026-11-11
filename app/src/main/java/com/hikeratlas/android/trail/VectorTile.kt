package com.hikeratlas.android.trail

/**
 * A small Mapbox Vector Tile reader: a layer's features, their properties
 * and their geometry, as tile coordinates. Enough to find a trail's ways.
 */
object VectorTile {
    class Feature(val type: Int, val properties: Map<String, Any?>, private val geometry: LongArray) {
        /** The geometry's parts (lines or rings), each [x0, y0, x1, y1, ...]. */
        fun parts(): List<IntArray> {
            val out = ArrayList<IntArray>()
            var cur = ArrayList<Int>()
            var x = 0
            var y = 0
            var i = 0
            fun unzz(v: Long) = ((v ushr 1) xor -(v and 1)).toInt()
            while (i < geometry.size) {
                val cmd = (geometry[i] and 7).toInt()
                val count = (geometry[i] ushr 3).toInt()
                i++
                when (cmd) {
                    1, 2 -> {
                        if (cmd == 1 && cur.isNotEmpty()) { out += cur.toIntArray(); cur = ArrayList() }
                        repeat(count) {
                            x += unzz(geometry[i]); y += unzz(geometry[i + 1]); i += 2
                            cur += x; cur += y
                        }
                    }
                    7 -> if (cur.size >= 2) { cur += cur[0]; cur += cur[1] } // ClosePath
                }
            }
            if (cur.isNotEmpty()) out += cur.toIntArray()
            return out
        }

        companion object {
            const val LINESTRING = 2
        }
    }

    class Layer(val name: String, val extent: Int, val features: List<Feature>)

    private class Reader(val b: ByteArray, var p: Int, val end: Int) {
        fun more() = p < end
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                val x = b[p++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl s)
                if (x < 0x80) return r
                s += 7
            }
        }
        fun sub(): Reader { val n = varint().toInt(); return Reader(b, p, p + n).also { p += n } }
        fun string(): String { val r = sub(); return String(b, r.p, r.end - r.p, Charsets.UTF_8) }
        fun packed(): LongArray {
            val r = sub()
            val out = ArrayList<Long>()
            while (r.more()) out += r.varint()
            return out.toLongArray()
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> p += 8
                2 -> p += varint().toInt()
                5 -> p += 4
                else -> error("wire type $wire")
            }
        }
    }

    /** The layer called [name], or null. */
    fun layer(tile: ByteArray, name: String): Layer? {
        val t = Reader(tile, 0, tile.size)
        while (t.more()) {
            val key = t.varint().toInt()
            if (key != (3 shl 3 or 2)) { t.skip(key and 7); continue }
            val l = t.sub()
            val layer = readLayer(l)
            if (layer?.name == name) return layer
        }
        return null
    }

    private fun readLayer(l: Reader): Layer? {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any?>()
        val raw = ArrayList<Triple<Int, LongArray, LongArray>>()
        while (l.more()) {
            val key = l.varint().toInt()
            when (key) {
                (1 shl 3) or 2 -> name = l.string()
                (2 shl 3) or 2 -> {
                    val f = l.sub()
                    var type = 0
                    var tags = LongArray(0)
                    var geom = LongArray(0)
                    while (f.more()) {
                        val fk = f.varint().toInt()
                        when (fk) {
                            (2 shl 3) or 2 -> tags = f.packed()
                            (3 shl 3) -> type = f.varint().toInt()
                            (4 shl 3) or 2 -> geom = f.packed()
                            else -> f.skip(fk and 7)
                        }
                    }
                    raw += Triple(type, tags, geom)
                }
                (3 shl 3) or 2 -> keys += l.string()
                (4 shl 3) or 2 -> values += value(l.sub())
                (5 shl 3) -> extent = l.varint().toInt()
                else -> l.skip(key and 7)
            }
        }
        val features = raw.map { (type, tags, geom) ->
            val props = HashMap<String, Any?>()
            for (k in 0 until tags.size / 2) props[keys[tags[2 * k].toInt()]] = values[tags[2 * k + 1].toInt()]
            Feature(type, props, geom)
        }
        return Layer(name, extent, features)
    }

    private fun value(v: Reader): Any? {
        var out: Any? = null
        while (v.more()) {
            val key = v.varint().toInt()
            out = when (key) {
                (1 shl 3) or 2 -> v.string()
                (2 shl 3) or 5 -> java.lang.Float.intBitsToFloat(
                    (v.b[v.p].toInt() and 0xff) or ((v.b[v.p + 1].toInt() and 0xff) shl 8) or
                        ((v.b[v.p + 2].toInt() and 0xff) shl 16) or ((v.b[v.p + 3].toInt() and 0xff) shl 24),
                ).toDouble().also { v.p += 4 }
                (3 shl 3) or 1 -> java.lang.Double.longBitsToDouble(
                    (0 until 8).fold(0L) { acc, k -> acc or ((v.b[v.p + k].toLong() and 0xff) shl (8 * k)) },
                ).also { v.p += 8 }
                (4 shl 3) -> v.varint()
                (5 shl 3) -> v.varint()
                (6 shl 3) -> v.varint().let { (it ushr 1) xor -(it and 1) }
                (7 shl 3) -> v.varint() != 0L
                else -> { v.skip(key and 7); out }
            }
        }
        return out
    }
}
