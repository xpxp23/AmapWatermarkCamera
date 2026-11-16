package com.fast.amapwatermark.util

import android.util.LruCache
import java.net.URLEncoder
import java.util.Locale

/**
 * Pure Kotlin, zero-dependency ISO/IEC 18004 QR Code Matrix Generator (Byte mode, ECC Level L).
 * Supports Version 1 to Version 6 (up to 134 UTF-8 bytes) so Amap Marker URLs and Mini Program
 * jump payloads can be encoded 100% offline and scanned by WeChat / System Camera / Amap.
 */
object QrCodeGenerator {

    private val matrixCache = LruCache<String, Array<BooleanArray>>(32)

    // Total data codewords and ECC codewords (Level L, single block for Versions 1..5, 2 blocks for V6)
    private data class VersionSpec(
        val version: Int,
        val size: Int,
        val totalDataBytes: Int,
        val eccBytesPerBlock: Int,
        val numBlocks: Int,
        val alignCenters: IntArray
    )

    private val SPECS = arrayOf(
        VersionSpec(1, 21, 19, 7, 1, intArrayOf()),
        VersionSpec(2, 25, 34, 10, 1, intArrayOf(6, 18)),
        VersionSpec(3, 29, 55, 15, 1, intArrayOf(6, 22)),
        VersionSpec(4, 33, 80, 20, 1, intArrayOf(6, 26)),
        VersionSpec(5, 37, 108, 26, 1, intArrayOf(6, 30)),
        VersionSpec(6, 41, 136, 18, 2, intArrayOf(6, 34))
    )

    fun buildAmapJumpUrl(gcjLat: Double, gcjLon: Double, shortPoi: String): String {
        val latStr = String.format(Locale.US, "%.6f", gcjLat)
        val lonStr = String.format(Locale.US, "%.6f", gcjLon)
        val cleanPoi = shortPoi.trim().take(10).ifBlank { "实拍点" }
        val encodedName = try {
            URLEncoder.encode(cleanPoi, "UTF-8")
        } catch (_: Exception) {
            "POI"
        }
        return "https://uri.amap.com/marker?position=$lonStr,$latStr&name=$encodedName&coordinate=gaode&callnative=1"
    }

    fun encodeUrlToMatrix(content: String): Array<BooleanArray> {
        matrixCache.get(content)?.let { return it }
        val rawBytes = content.toByteArray(Charsets.UTF_8)
        val spec = SPECS.firstOrNull { rawBytes.size + 3 <= it.totalDataBytes } ?: SPECS.last()
        val payload = if (rawBytes.size + 3 <= spec.totalDataBytes) {
            rawBytes
        } else {
            rawBytes.copyOf(spec.totalDataBytes - 3)
        }

        val dataCodewords = buildDataCodewords(payload, spec.totalDataBytes)
        val finalCodewords = interleaveWithEcc(dataCodewords, spec)
        val matrix = Array(spec.size) { BooleanArray(spec.size) }
        val isFunction = Array(spec.size) { BooleanArray(spec.size) }

        drawFunctionPatterns(matrix, isFunction, spec)
        drawCodewords(matrix, isFunction, finalCodewords, spec.size)
        applyMask0AndFormatBits(matrix, isFunction, spec.size)

        matrixCache.put(content, matrix)
        return matrix
    }

    private fun buildDataCodewords(bytes: ByteArray, capacity: Int): IntArray {
        val bits = ArrayList<Int>(capacity * 8)
        fun appendBits(value: Int, len: Int) {
            for (i in len - 1 downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }
        // Byte mode indicator (0100) + 8-bit character count (for V1..9)
        appendBits(0b0100, 4)
        appendBits(bytes.size, 8)
        for (b in bytes) {
            appendBits(b.toInt() and 0xFF, 8)
        }
        val maxBits = capacity * 8
        val terminator = minOf(4, maxBits - bits.size)
        appendBits(0, terminator)
        while (bits.size % 8 != 0) {
            bits.add(0)
        }
        val padBytes = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (bits.size < maxBits) {
            appendBits(padBytes[padIdx % 2], 8)
            padIdx++
        }
        val out = IntArray(capacity)
        for (i in 0 until capacity) {
            var v = 0
            for (b in 0 until 8) {
                v = (v shl 1) or bits[i * 8 + b]
            }
            out[i] = v
        }
        return out
    }

    private fun interleaveWithEcc(data: IntArray, spec: VersionSpec): IntArray {
        if (spec.numBlocks == 1) {
            val ecc = computeReedSolomon(data, spec.eccBytesPerBlock)
            return data + ecc
        }
        val blockSize = spec.totalDataBytes / spec.numBlocks
        val blocks = Array(spec.numBlocks) { b ->
            data.copyOfRange(b * blockSize, (b + 1) * blockSize)
        }
        val eccBlocks = Array(spec.numBlocks) { b ->
            computeReedSolomon(blocks[b], spec.eccBytesPerBlock)
        }
        val result = IntArray(spec.totalDataBytes + spec.eccBytesPerBlock * spec.numBlocks)
        var idx = 0
        for (i in 0 until blockSize) {
            for (b in 0 until spec.numBlocks) {
                result[idx++] = blocks[b][i]
            }
        }
        for (i in 0 until spec.eccBytesPerBlock) {
            for (b in 0 until spec.numBlocks) {
                result[idx++] = eccBlocks[b][i]
            }
        }
        return result
    }

    private fun computeReedSolomon(data: IntArray, degree: Int): IntArray {
        val gen = IntArray(degree)
        gen[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                gen[j] = gfMultiply(gen[j], root)
                if (j + 1 < degree) {
                    gen[j] = gen[j] xor gen[j + 1]
                }
            }
            root = gfMultiply(root, 0x02)
        }
        val rem = IntArray(degree)
        for (b in data) {
            val factor = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, degree - 1)
            rem[degree - 1] = 0
            for (j in 0 until degree) {
                rem[j] = rem[j] xor gfMultiply(gen[j], factor)
            }
        }
        return rem
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun drawFunctionPatterns(
        matrix: Array<BooleanArray>,
        isFunc: Array<BooleanArray>,
        spec: VersionSpec
    ) {
        val sz = spec.size
        fun setFuncModule(x: Int, y: Int, dark: Boolean) {
            if (x in 0 until sz && y in 0 until sz) {
                matrix[y][x] = dark
                isFunc[y][x] = true
            }
        }

        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    setFuncModule(cx + dx, cy + dy, dist != 2 && dist != 4)
                }
            }
        }

        drawFinder(3, 3)
        drawFinder(sz - 4, 3)
        drawFinder(3, sz - 4)

        for (i in 0 until sz) {
            if (!isFunc[6][i]) setFuncModule(i, 6, i % 2 == 0)
            if (!isFunc[i][6]) setFuncModule(6, i, i % 2 == 0)
        }

        val aligns = spec.alignCenters
        for (ay in aligns) {
            for (ax in aligns) {
                if ((ax == 6 && ay == 6) || (ax == 6 && ay == sz - 7) || (ax == sz - 7 && ay == 6)) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFuncModule(ax + dx, ay + dy, dist != 1)
                    }
                }
            }
        }

        // Reserve format info areas
        for (i in 0..8) {
            if (i != 6) {
                isFunc[8][i] = true
                isFunc[i][8] = true
            }
        }
        for (i in 0..7) {
            isFunc[8][sz - 1 - i] = true
            isFunc[sz - 1 - i][8] = true
        }
        // Dark module
        setFuncModule(8, sz - 8, true)
    }

    private fun drawCodewords(
        matrix: Array<BooleanArray>,
        isFunc: Array<BooleanArray>,
        codewords: IntArray,
        sz: Int
    ) {
        var bitIdx = 0
        val totalBits = codewords.size * 8
        var right = sz - 1
        var vert = 0
        while (right >= 1) {
            if (right == 6) right = 5
            for (cnt in 0 until sz) {
                val y = if ((vert and 1) == 0) (sz - 1 - cnt) else cnt
                for (j in 0..1) {
                    val x = right - j
                    if (!isFunc[y][x]) {
                        val dark = if (bitIdx < totalBits) {
                            ((codewords[bitIdx ushr 3] ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else false
                        matrix[y][x] = dark
                        bitIdx++
                    }
                }
            }
            right -= 2
            vert++
        }
    }

    private fun applyMask0AndFormatBits(
        matrix: Array<BooleanArray>,
        isFunc: Array<BooleanArray>,
        sz: Int
    ) {
        for (y in 0 until sz) {
            for (x in 0 until sz) {
                if (!isFunc[y][x] && (x + y) % 2 == 0) {
                    matrix[y][x] = !matrix[y][x]
                }
            }
        }
        // Precomputed 15-bit format info for ECC Level L (01) + Mask 0 (000) = 0x77C4
        val formatBits = 0x77C4
        fun bit(i: Int): Boolean = ((formatBits ushr i) and 1) != 0

        for (i in 0..5) matrix[8][i] = bit(i)
        matrix[8][7] = bit(6)
        matrix[8][8] = bit(7)
        matrix[7][8] = bit(8)
        for (i in 9..14) matrix[14 - i][8] = bit(i)

        for (i in 0..7) matrix[sz - 1 - i][8] = bit(i)
        for (i in 8..14) matrix[8][sz - 15 + i] = bit(i)
        matrix[sz - 8][8] = true
    }
}
