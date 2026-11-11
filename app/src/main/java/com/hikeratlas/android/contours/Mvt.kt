package com.hikeratlas.android.contours

import java.io.ByteArrayOutputStream

/**
 * A minimal Mapbox Vector Tile writer: one layer of line strings with
 * integer properties, which is all contours need.
 */
class MvtLayer(private val name: String, private val extent: Int = 4096) {
    private class Feature(val props: List<Pair<Int, Int>>, val lines: List<IntArray>)

    private val keys = LinkedHashMap<String, Int>()
    private val values = LinkedHashMap<Long, Int>()
    private val features = ArrayList<Feature>()

    /** Adds a feature of one or more lines, each [x0, y0, x1, y1, ...] in tile units. */
    fun add(props: Map<String, Long>, lines: List<IntArray>) {
        val kept = lines.filter { it.size >= 4 }
        if (kept.isEmpty()) return
        val tags = props.map { (k, v) ->
            keys.getOrPut(k) { keys.size } to values.getOrPut(v) { values.size }
        }
        features += Feature(tags, kept)
    }

    val isEmpty get() = features.isEmpty()

    fun encode(): ByteArray {
        val layer = Pb()
        layer.varintField(15, 2) // version
        layer.bytesField(1, name.toByteArray())
        for (f in features) {
            val feat = Pb()
            feat.packedField(2, f.props.flatMap { listOf(it.first.toLong(), it.second.toLong()) })
            feat.varintField(3, 2) // LINESTRING
            feat.packedField(4, geometry(f.lines))
            layer.bytesField(2, feat.bytes())
        }
        for (k in keys.keys) layer.bytesField(3, k.toByteArray())
        for (v in values.keys) {
            val value = Pb()
            // sint_value: zigzag-encoded
            value.varintField(6, (v shl 1) xor (v shr 63))
            layer.bytesField(4, value.bytes())
        }
        layer.varintField(5, extent.toLong())
        return Pb().apply { bytesField(3, layer.bytes()) }.bytes()
    }

    private fun geometry(lines: List<IntArray>): List<Long> {
        val out = ArrayList<Long>()
        var cx = 0
        var cy = 0
        fun zz(v: Int) = ((v shl 1) xor (v shr 31)).toLong() and 0xffffffffL
        for (line in lines) {
            val n = line.size / 2
            out += command(1, 1) // MoveTo
            out += zz(line[0] - cx); out += zz(line[1] - cy)
            cx = line[0]; cy = line[1]
            out += command(2, n - 1) // LineTo
            for (k in 1 until n) {
                out += zz(line[2 * k] - cx); out += zz(line[2 * k + 1] - cy)
                cx = line[2 * k]; cy = line[2 * k + 1]
            }
        }
        return out
    }

    private fun command(id: Int, count: Int) = ((count.toLong() shl 3) or id.toLong())

    /** Protobuf writing, as far as tiles need. */
    private class Pb {
        private val out = ByteArrayOutputStream()
        fun bytes(): ByteArray = out.toByteArray()
        fun varint(v: Long) {
            var x = v
            while (x and 0x7fL.inv() != 0L) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        fun varintField(field: Int, v: Long) { varint((field shl 3).toLong()); varint(v) }
        fun bytesField(field: Int, b: ByteArray) { varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun packedField(field: Int, vs: List<Long>) {
            val p = Pb(); vs.forEach(p::varint)
            bytesField(field, p.bytes())
        }
    }
}
