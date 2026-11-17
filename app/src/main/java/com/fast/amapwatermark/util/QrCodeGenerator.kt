package com.fast.amapwatermark.util

import android.util.LruCache
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Pure Kotlin, zero-dependency ISO/IEC 18004 QR Code Matrix Generator (Byte mode, ECC Level L, V1..V9)
 * + FIPS 180-4 SHA-256 Anti-Tamper Cryptographic Signature & Verification Engine (v8.0.0).
 */
object QrCodeGenerator {

    private const val ANTI_TAMPER_SALT = "AMAP_WM_V7_TRUST_ANCHOR_2026"
    private val matrixCache = LruCache<String, Array<BooleanArray>>(64)

    data class VerifiedLocationPayload(
        val gcjLat: Double,
        val gcjLon: Double,
        val wgsLat: Double,
        val wgsLon: Double,
        val shortPoi: String,
        val timestamp: String = "",
        val note: String = "",
        val signature: String = "",
        val expectedSignature: String = "",
        val ntpSynced: Boolean = false,
        val hasAntiTamper: Boolean = false,
        val verified: Boolean = false,
        val sourceLabel: String = "二维码/元数据核验"
    )

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
        VersionSpec(6, 41, 136, 18, 2, intArrayOf(6, 34)),
        VersionSpec(7, 45, 156, 20, 2, intArrayOf(6, 22, 38)),
        VersionSpec(8, 49, 194, 24, 2, intArrayOf(6, 24, 42)),
        VersionSpec(9, 53, 232, 30, 2, intArrayOf(6, 26, 46))
    )

    private val VERSION_INFO_BITS = mapOf(
        7 to 0x07C94,
        8 to 0x085BC,
        9 to 0x09A99
    )

    /**
     * Compute 16-char uppercase SHA-256 Anti-Tamper digest binding GCJ-02 coordinates, timestamp, POI, and optional note.
     */
    fun computeAntiTamperHash(
        gcjLat: Double,
        gcjLon: Double,
        timestamp: String,
        shortPoi: String,
        note: String = ""
    ): String {
        val latStr = String.format(Locale.US, "%.6f", gcjLat)
        val lonStr = String.format(Locale.US, "%.6f", gcjLon)
        val tsClean = timestamp.trim()
        val poiClean = shortPoi.trim().take(10).ifBlank { "实拍点位" }
        val noteClean = note.trim().take(12)
        val payload = if (noteClean.isNotEmpty()) {
            "$latStr|$lonStr|$tsClean|$poiClean|$noteClean|$ANTI_TAMPER_SALT"
        } else {
            "$latStr|$lonStr|$tsClean|$poiClean|$ANTI_TAMPER_SALT"
        }
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(payload.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
            }
            sb.substring(0, 16)
        } catch (_: Exception) {
            "0000000000000000"
        }
    }

    fun verifyAntiTamperHash(
        gcjLat: Double,
        gcjLon: Double,
        timestamp: String,
        shortPoi: String,
        signature: String,
        note: String = ""
    ): Boolean {
        val cleanSig = signature.trim().uppercase(Locale.US)
        if (cleanSig.length < 10) return false
        val expectedWithNote = computeAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi, note)
        if (expectedWithNote.startsWith(cleanSig)) return true
        val expectedLegacy = computeAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi, "")
        return expectedLegacy.startsWith(cleanSig)
    }

    fun buildAmapJumpUrl(
        gcjLat: Double,
        gcjLon: Double,
        shortPoi: String,
        antiTamperEnabled: Boolean = false,
        timestamp: String = "",
        antiTamperHash: String = "",
        ntpSynced: Boolean = false,
        note: String = ""
    ): String {
        val latStr = String.format(Locale.US, "%.6f", gcjLat)
        val lonStr = String.format(Locale.US, "%.6f", gcjLon)
        val cleanPoi = shortPoi.trim().take(10).ifBlank { "实拍点位" }
        val encodedName = try {
            URLEncoder.encode(cleanPoi, "UTF-8")
        } catch (_: Exception) {
            "POI"
        }
        val base = "https://uri.amap.com/marker?position=$lonStr,$latStr&name=$encodedName&coordinate=gaode&callnative=1"
        if (!antiTamperEnabled) return base

        val tsClean = timestamp.trim()
        val noteClean = note.trim().take(12)
        val sig = antiTamperHash.ifBlank {
            computeAntiTamperHash(gcjLat, gcjLon, tsClean, cleanPoi, noteClean)
        }.take(12)
        val encodedTs = try {
            URLEncoder.encode(tsClean, "UTF-8")
        } catch (_: Exception) {
            ""
        }
        val ntpFlag = if (ntpSynced) 1 else 0
        val noteParam = if (noteClean.isNotEmpty()) {
            val encNote = try { URLEncoder.encode(noteClean, "UTF-8") } catch (_: Exception) { "" }
            if (encNote.isNotEmpty()) "&note=$encNote" else ""
        } else ""
        return "$base&ts=$encodedTs&sig=$sig&ntp=$ntpFlag$noteParam"
    }

    /**
     * Parse and cryptographically verify QR URLs, EXIF strings, or shared coordinate text (v8.0.0)
     */
    fun parseAndVerifyPayload(rawInput: String, sourceLabel: String = "二维码/链接核验"): VerifiedLocationPayload? {
        val origStr = rawInput.trim()
        if (origStr.isEmpty()) return null
        val decodedStr = try {
            URLDecoder.decode(origStr, "UTF-8")
        } catch (_: Exception) {
            origStr
        }

        fun extractParam(s: String, key: String): String {
            val reg = Regex("[?&]$key=([^&#]+)", RegexOption.IGNORE_CASE)
            val m = reg.find(s) ?: return ""
            return try {
                URLDecoder.decode(m.groupValues[1], "UTF-8")
            } catch (_: Exception) {
                m.groupValues[1]
            }
        }

        val posRegex = Regex("position=([0-9.+-]+),([0-9.+-]+)", RegexOption.IGNORE_CASE)
        val posMatch = posRegex.find(origStr) ?: posRegex.find(decodedStr)
        if (posMatch != null) {
            val lon = posMatch.groupValues[1].toDoubleOrNull()
            val lat = posMatch.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null) {
                val poi = extractParam(origStr, "name").ifBlank { extractParam(decodedStr, "name") }.ifBlank { "实拍点位" }
                val ts = extractParam(origStr, "ts").ifBlank { extractParam(decodedStr, "ts") }
                val sig = extractParam(origStr, "sig").ifBlank { extractParam(decodedStr, "sig") }.uppercase(Locale.US)
                val note = extractParam(origStr, "note").ifBlank { extractParam(decodedStr, "note") }
                val ntp = (extractParam(origStr, "ntp").ifBlank { extractParam(decodedStr, "ntp") }) == "1"
                val wgs = CoordinateConverter.gcj02ToWgs84(lat, lon)
                val expected = if (ts.isNotBlank()) computeAntiTamperHash(lat, lon, ts, poi, note).take(12) else ""
                val verified = if (sig.isNotBlank()) verifyAntiTamperHash(lat, lon, ts, poi, sig, note) else false
                return VerifiedLocationPayload(
                    gcjLat = lat,
                    gcjLon = lon,
                    wgsLat = wgs.lat,
                    wgsLon = wgs.lon,
                    shortPoi = poi,
                    timestamp = ts,
                    note = note,
                    signature = sig,
                    expectedSignature = expected,
                    ntpSynced = ntp,
                    hasAntiTamper = sig.isNotBlank(),
                    verified = verified,
                    sourceLabel = sourceLabel
                )
            }
        }

        val numRegex = Regex("([0-9]{1,3}\\.[0-9]{3,8})")
        val nums = numRegex.findAll(decodedStr).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (nums.size >= 2) {
            val a = nums[0]
            val b = nums[1]
            val isALon = a > 65.0 && b < 60.0
            val gcjLon = if (isALon) a else b
            val gcjLat = if (isALon) b else a
            val wgs = CoordinateConverter.gcj02ToWgs84(gcjLat, gcjLon)
            return VerifiedLocationPayload(
                gcjLat = gcjLat,
                gcjLon = gcjLon,
                wgsLat = wgs.lat,
                wgsLon = wgs.lon,
                shortPoi = "解析坐标点位",
                sourceLabel = sourceLabel
            )
        }
        return null
    }

    private fun safeUtf8Slice(rawBytes: ByteArray, maxBytes: Int): ByteArray {
        if (rawBytes.size <= maxBytes) return rawBytes
        var cut = maxBytes
        while (cut > 0 && (rawBytes[cut].toInt() and 0xC0) == 0x80) {
            cut--
        }
        return rawBytes.copyOf(cut)
    }

    fun encodeUrlToMatrix(content: String): Array<BooleanArray> {
        matrixCache.get(content)?.let { return it }
        val rawBytes = content.toByteArray(Charsets.UTF_8)
        val spec = SPECS.firstOrNull { rawBytes.size + 3 <= it.totalDataBytes } ?: SPECS.last()
        val payload = safeUtf8Slice(rawBytes, spec.totalDataBytes - 3)

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
        setFuncModule(8, sz - 8, true)

        if (spec.version >= 7) {
            val vbits = VERSION_INFO_BITS[spec.version] ?: 0
            for (i in 0 until 6) {
                for (j in 0 until 3) {
                    val bit = ((vbits ushr (i * 3 + j)) and 1) != 0
                    setFuncModule(i, sz - 11 + j, bit)
                    setFuncModule(sz - 11 + j, i, bit)
                }
            }
        }
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
        val formatBits = 0x77C4
        fun bit(i: Int): Boolean = ((formatBits ushr (14 - i)) and 1) != 0

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
