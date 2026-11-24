package com.fast.amapwatermark.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/**
 * Pure Kotlin, zero-dependency ISO/IEC 18004 QR Code Matrix Generator (Byte mode, ECC Level L, V1..V14, 8-mask penalty selection)
 * + FIPS 180-4 SHA-256 Anti-Tamper Cryptographic Signature & Perceptual Image Fingerprint Engine (v9.0.0).
 */
object QrCodeGenerator {

    private const val ANTI_TAMPER_SALT = "AMAP_WM_V7_TRUST_ANCHOR_2026"
    private val matrixCache = LruCache<String, Array<BooleanArray>>(64)
    private val HEX_REGEX = Regex("^[0-9A-F]{10,64}$")

    // ISO/IEC 18004 Format Information (15-bit BCH + XOR 0x5412) for ECC Level L (01) and Mask 0..7
    private val FORMAT_INFO_ECC_L = intArrayOf(
        0x77C4, // mask 0
        0x72F3, // mask 1
        0x7DAA, // mask 2
        0x789D, // mask 3
        0x662F, // mask 4
        0x6318, // mask 5
        0x6C41, // mask 6
        0x6976  // mask 7
    )

    data class VerifiedLocationPayload(
        val gcjLat: Double,
        val gcjLon: Double,
        val wgsLat: Double,
        val wgsLon: Double,
        val shortPoi: String,
        val timestamp: String = "",
        val note: String = "",
        val imageHash: String = "",
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

    // ISO/IEC 18004 ECC Level L specs for V1..V14 (supports up to 457 UTF-8 bytes)
    private val SPECS = arrayOf(
        VersionSpec(1, 21, 19, 7, 1, intArrayOf()),
        VersionSpec(2, 25, 34, 10, 1, intArrayOf(6, 18)),
        VersionSpec(3, 29, 55, 15, 1, intArrayOf(6, 22)),
        VersionSpec(4, 33, 80, 20, 1, intArrayOf(6, 26)),
        VersionSpec(5, 37, 108, 26, 1, intArrayOf(6, 30)),
        VersionSpec(6, 41, 136, 18, 2, intArrayOf(6, 34)),
        VersionSpec(7, 45, 156, 20, 2, intArrayOf(6, 22, 38)),
        VersionSpec(8, 49, 194, 24, 2, intArrayOf(6, 24, 42)),
        VersionSpec(9, 53, 232, 30, 2, intArrayOf(6, 26, 46)),
        VersionSpec(10, 57, 274, 18, 4, intArrayOf(6, 28, 50)),
        VersionSpec(11, 61, 324, 20, 4, intArrayOf(6, 30, 54)),
        VersionSpec(12, 65, 370, 24, 4, intArrayOf(6, 32, 58)),
        VersionSpec(13, 69, 428, 26, 4, intArrayOf(6, 34, 62)),
        VersionSpec(14, 73, 461, 30, 4, intArrayOf(6, 26, 46, 66))
    )

    /**
     * Compute 18-bit BCH(18,6) version information for QR V7..V40.
     */
    private fun computeVersionInfoBits(version: Int): Int {
        var rem = version
        for (i in 0 until 12) {
            rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        }
        return (version shl 12) or rem
    }

    /**
     * Compute lightweight 64-bit perceptual image fingerprint (9x8 grayscale dHash -> 16 uppercase hex chars).
     * Binds actual visual pixels to anti-tamper metadata to detect image substitution.
     */
    fun computeImagePerceptualHash(bitmap: Bitmap): String {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return ""
        var scaled: Bitmap? = null
        return try {
            scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
            val gray = IntArray(9 * 8)
            for (y in 0 until 8) {
                for (x in 0 until 9) {
                    val px = scaled.getPixel(x, y)
                    val r = Color.red(px)
                    val g = Color.green(px)
                    val b = Color.blue(px)
                    gray[y * 9 + x] = (r * 299 + g * 587 + b * 114) / 1000
                }
            }
            var bits = 0L
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    bits = bits shl 1
                    if (gray[y * 9 + x] > gray[y * 9 + x + 1]) {
                        bits = bits or 1L
                    }
                }
            }
            String.format(Locale.US, "%016X", bits)
        } catch (_: Exception) {
            ""
        } finally {
            if (scaled != null && scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    /**
     * Compute 16-char uppercase SHA-256 Anti-Tamper digest binding GCJ-02 coordinates, timestamp, POI, optional note, and optional perceptual imageHash.
     */
    fun computeAntiTamperHash(
        gcjLat: Double,
        gcjLon: Double,
        timestamp: String,
        shortPoi: String,
        note: String = "",
        imageHash: String = ""
    ): String {
        val latStr = String.format(Locale.US, "%.6f", gcjLat)
        val lonStr = String.format(Locale.US, "%.6f", gcjLon)
        val tsClean = timestamp.trim()
        val poiClean = shortPoi.trim().take(10).ifBlank { "实拍点位" }
        val noteClean = note.trim().take(12)
        val imgClean = imageHash.trim().uppercase(Locale.US).take(16)
        val payload = buildString {
            append(latStr).append('|')
            append(lonStr).append('|')
            append(tsClean).append('|')
            append(poiClean)
            if (noteClean.isNotEmpty()) {
                append('|').append(noteClean)
            }
            if (imgClean.isNotEmpty()) {
                append("|IMG:").append(imgClean)
            }
            append('|').append(ANTI_TAMPER_SALT)
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

    /**
     * Strict cryptographic verification (v9.0.0):
     * - Enforces signature length >= 10 and valid uppercase hex characters.
     * - No fallback to empty note when a note is present (closes legacy note-tampering bypass).
     */
    fun verifyAntiTamperHash(
        gcjLat: Double,
        gcjLon: Double,
        timestamp: String,
        shortPoi: String,
        signature: String,
        note: String = "",
        imageHash: String = ""
    ): Boolean {
        val cleanSig = signature.trim().uppercase(Locale.US)
        if (cleanSig.length < 10 || !HEX_REGEX.matches(cleanSig)) return false
        if (timestamp.isBlank()) return false
        val expected = computeAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi, note, imageHash)
        return expected.startsWith(cleanSig)
    }

    fun buildAmapJumpUrl(
        gcjLat: Double,
        gcjLon: Double,
        shortPoi: String,
        antiTamperEnabled: Boolean = false,
        timestamp: String = "",
        antiTamperHash: String = "",
        ntpSynced: Boolean = false,
        note: String = "",
        imageHash: String = ""
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
        val imgClean = imageHash.trim().uppercase(Locale.US).take(16)
        val sig = antiTamperHash.ifBlank {
            computeAntiTamperHash(gcjLat, gcjLon, tsClean, cleanPoi, noteClean, imgClean)
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
        val imgParam = if (imgClean.isNotEmpty()) "&ih=$imgClean" else ""
        return "$base&ts=$encodedTs&sig=$sig&ntp=$ntpFlag$noteParam$imgParam"
    }

    /**
     * Parse and cryptographically verify QR URLs, EXIF strings, or shared coordinate text (v9.0.0)
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
                val imgHash = extractParam(origStr, "ih").ifBlank { extractParam(decodedStr, "ih") }.uppercase(Locale.US)
                val ntp = (extractParam(origStr, "ntp").ifBlank { extractParam(decodedStr, "ntp") }) == "1"
                val wgs = CoordinateConverter.gcj02ToWgs84(lat, lon)
                val expected = if (ts.isNotBlank()) computeAntiTamperHash(lat, lon, ts, poi, note, imgHash).take(12) else ""
                val verified = if (sig.isNotBlank()) verifyAntiTamperHash(lat, lon, ts, poi, sig, note, imgHash) else false
                return VerifiedLocationPayload(
                    gcjLat = lat,
                    gcjLon = lon,
                    wgsLat = wgs.lat,
                    wgsLon = wgs.lon,
                    shortPoi = poi,
                    timestamp = ts,
                    note = note,
                    imageHash = imgHash,
                    signature = sig,
                    expectedSignature = expected,
                    ntpSynced = ntp,
                    hasAntiTamper = sig.isNotBlank(),
                    verified = verified,
                    sourceLabel = sourceLabel
                )
            }
        }

        val numRegex = Regex("([+-]?[0-9]{1,3}\\.[0-9]{3,8})")
        val nums = numRegex.findAll(decodedStr).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (nums.size >= 2) {
            val a = nums[0]
            val b = nums[1]
            val isALon = abs(a) > 65.0 && abs(b) < 60.0
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
        val spec = SPECS.firstOrNull {
            val headerBytes = if (it.version >= 10) 4 else 3
            rawBytes.size + headerBytes <= it.totalDataBytes
        } ?: SPECS.last()
        val headerBytes = if (spec.version >= 10) 4 else 3
        val payload = safeUtf8Slice(rawBytes, spec.totalDataBytes - headerBytes)

        val dataCodewords = buildDataCodewords(payload, spec.totalDataBytes, spec.version)
        val finalCodewords = interleaveWithEcc(dataCodewords, spec)
        val baseMatrix = Array(spec.size) { BooleanArray(spec.size) }
        val isFunction = Array(spec.size) { BooleanArray(spec.size) }

        drawFunctionPatterns(baseMatrix, isFunction, spec)
        drawCodewords(baseMatrix, isFunction, finalCodewords, spec.size)

        // Evaluate all 8 ISO/IEC 18004 masks and pick the lowest penalty score
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestMatrix: Array<BooleanArray>? = null

        for (maskId in 0..7) {
            val candidate = Array(spec.size) { r -> baseMatrix[r].copyOf() }
            applyMaskAndFormatBits(candidate, isFunction, spec.size, maskId)
            val penalty = computeMaskPenalty(candidate, spec.size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = maskId
                bestMatrix = candidate
            }
        }

        val finalMatrix = bestMatrix ?: baseMatrix.also {
            applyMaskAndFormatBits(it, isFunction, spec.size, bestMask)
        }
        matrixCache.put(content, finalMatrix)
        return finalMatrix
    }

    private fun buildDataCodewords(bytes: ByteArray, capacity: Int, version: Int): IntArray {
        val bits = ArrayList<Int>(capacity * 8)
        fun appendBits(value: Int, len: Int) {
            for (i in len - 1 downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }
        appendBits(0b0100, 4) // Byte mode indicator
        val countBits = if (version >= 10) 16 else 8
        appendBits(bytes.size, countBits)
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

    /**
     * Supports both even and uneven RS block splits (required for V10, V12, V14).
     */
    private fun interleaveWithEcc(data: IntArray, spec: VersionSpec): IntArray {
        if (spec.numBlocks == 1) {
            val ecc = computeReedSolomon(data, spec.eccBytesPerBlock)
            return data + ecc
        }
        val numBlocks = spec.numBlocks
        val shortBlockLen = spec.totalDataBytes / numBlocks
        val numLongBlocks = spec.totalDataBytes % numBlocks
        val numShortBlocks = numBlocks - numLongBlocks

        val blocks = ArrayList<IntArray>(numBlocks)
        var offset = 0
        for (b in 0 until numBlocks) {
            val len = if (b < numShortBlocks) shortBlockLen else (shortBlockLen + 1)
            blocks.add(data.copyOfRange(offset, offset + len))
            offset += len
        }
        val eccBlocks = Array(numBlocks) { b ->
            computeReedSolomon(blocks[b], spec.eccBytesPerBlock)
        }

        val result = IntArray(spec.totalDataBytes + spec.eccBytesPerBlock * numBlocks)
        var idx = 0
        val maxBlockLen = if (numLongBlocks > 0) shortBlockLen + 1 else shortBlockLen
        for (i in 0 until maxBlockLen) {
            for (b in 0 until numBlocks) {
                if (i < blocks[b].size) {
                    result[idx++] = blocks[b][i]
                }
            }
        }
        for (i in 0 until spec.eccBytesPerBlock) {
            for (b in 0 until numBlocks) {
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
                    val dist = maxOf(abs(dx), abs(dy))
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
                        val dist = maxOf(abs(dx), abs(dy))
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
            val vbits = computeVersionInfoBits(spec.version)
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

    private fun shouldInvertByMask(maskId: Int, x: Int, y: Int): Boolean {
        return when (maskId) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> ((y / 2) + (x / 3)) % 2 == 0
            5 -> ((x * y) % 2) + ((x * y) % 3) == 0
            6 -> (((x * y) % 2) + ((x * y) % 3)) % 2 == 0
            7 -> (((x + y) % 2) + ((x * y) % 3)) % 2 == 0
            else -> false
        }
    }

    private fun applyMaskAndFormatBits(
        matrix: Array<BooleanArray>,
        isFunc: Array<BooleanArray>,
        sz: Int,
        maskId: Int
    ) {
        for (y in 0 until sz) {
            for (x in 0 until sz) {
                if (!isFunc[y][x] && shouldInvertByMask(maskId, x, y)) {
                    matrix[y][x] = !matrix[y][x]
                }
            }
        }
        val formatBits = FORMAT_INFO_ECC_L[maskId.coerceIn(0, 7)]
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

    /**
     * ISO/IEC 18004 Mask Penalty Score (N1 + N2 + N3 + N4).
     */
    private fun computeMaskPenalty(matrix: Array<BooleanArray>, sz: Int): Int {
        var penalty = 0
        var darkCount = 0

        // N1: Adjacent modules in row/column in same color (>= 5)
        for (y in 0 until sz) {
            var runColor = matrix[y][0]
            var runLen = 1
            if (runColor) darkCount++
            for (x in 1 until sz) {
                val c = matrix[y][x]
                if (c) darkCount++
                if (c == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = c
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }
        for (x in 0 until sz) {
            var runColor = matrix[0][x]
            var runLen = 1
            for (y in 1 until sz) {
                val c = matrix[y][x]
                if (c == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = c
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }

        // N2: 2x2 blocks of same color
        for (y in 0 until sz - 1) {
            val row0 = matrix[y]
            val row1 = matrix[y + 1]
            for (x in 0 until sz - 1) {
                val c = row0[x]
                if (c == row0[x + 1] && c == row1[x] && c == row1[x + 1]) {
                    penalty += 3
                }
            }
        }

        // N3: Finder-like 1:1:3:1:1 patterns with 4 light modules on either side
        for (y in 0 until sz) {
            val row = matrix[y]
            for (x in 0..sz - 11) {
                if (matchesFinderPatternRow(row, x)) penalty += 40
            }
        }
        for (x in 0 until sz) {
            for (y in 0..sz - 11) {
                if (matchesFinderPatternCol(matrix, x, y)) penalty += 40
            }
        }

        // N4: Dark module ratio balance
        val total = sz * sz
        val k = abs(darkCount * 20 - total * 10) / total
        penalty += k * 10

        return penalty
    }

    private fun matchesFinderPatternRow(row: BooleanArray, x: Int): Boolean {
        val p1 = row[x] && !row[x + 1] && row[x + 2] && row[x + 3] && row[x + 4] && !row[x + 5] && row[x + 6] &&
                !row[x + 7] && !row[x + 8] && !row[x + 9] && !row[x + 10]
        if (p1) return true
        return !row[x] && !row[x + 1] && !row[x + 2] && !row[x + 3] &&
                row[x + 4] && !row[x + 5] && row[x + 6] && row[x + 7] && row[x + 8] && !row[x + 9] && row[x + 10]
    }

    private fun matchesFinderPatternCol(m: Array<BooleanArray>, x: Int, y: Int): Boolean {
        val p1 = m[y][x] && !m[y + 1][x] && m[y + 2][x] && m[y + 3][x] && m[y + 4][x] && !m[y + 5][x] && m[y + 6][x] &&
                !m[y + 7][x] && !m[y + 8][x] && !m[y + 9][x] && !m[y + 10][x]
        if (p1) return true
        return !m[y][x] && !m[y + 1][x] && !m[y + 2][x] && !m[y + 3][x] &&
                m[y + 4][x] && !m[y + 5][x] && m[y + 6][x] && m[y + 7][x] && m[y + 8][x] && !m[y + 9][x] && m[y + 10][x]
    }
}
