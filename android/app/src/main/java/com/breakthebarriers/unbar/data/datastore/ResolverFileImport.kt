package com.breakthebarriers.unbar.data.datastore

// (1.0.2) Resolver list import: cleans a .txt file of resolver
// addresses into the same canonical entries the Custom field uses
// ("ip:port" or "network/prefix"), and reports what it fixed.
//
// Accepted in the file: single IPv4 addresses (with or without a
// port), CIDR ranges, dash ranges ("a.b.c.d-a.b.c.e"), separated by
// new lines, commas, semicolons, spaces or tabs (Persian separators
// and digits too). Text around an address ("DNS: 1.1.1.1") is
// ignored; "#" and "//" start a comment. Anything without a valid
// IPv4 address (hostnames, IPv6, "300.1.2.3") is reported, not kept.
//
// Beyond the Custom field's per-entry cleaning, a file is also
// de-duplicated, single :53 addresses already inside an imported
// range are dropped, ranges inside a larger range are dropped, and
// the whole list is sorted by address.

data class ResolverImportResult(
    val entries: List<String>,
    val totalAddresses: Int,
    val cleaned: Int,
    val duplicates: Int,
    val insideRange: Int,
    val rangesCorrected: Int,
    val dashConverted: Int,
    val ignored: List<String>,
    val oversize: List<String>
) {
    val fixedCount: Int get() = cleaned + duplicates + insideRange + rangesCorrected + dashConverted
}

const val RESOLVER_IMPORT_MAX_BYTES = 1_048_576
const val RESOLVER_SCAN_CAP = 5000
private const val MAX_RANGE_HOST_BITS = 12 // /20 = 4096 addresses, same as the core

private val ADDRESS_IN_TEXT = Regex(
    """(?<!\d)\d{1,3}(?:\.\d{1,3}){3}(?:\s*[-\u2013\u2014]\s*\d{1,3}(?:\.\d{1,3}){3}|\s*/\s*\d{1,2}|:\d{1,5})?(?!\d)"""
)

/** Decodes file bytes: UTF-8 (with or without BOM) or UTF-16 with a BOM. */
fun decodeResolverFile(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    return String(bytes, Charsets.UTF_8)
}

private data class Parsed(val entry: String, val start: Long, val end: Long, val prefix: Int?, val port: Int)

private fun parseEntry(e: String): Parsed? {
    return if (e.contains('/')) {
        val ip = e.substringBefore('/')
        val prefix = e.substringAfter('/').toIntOrNull() ?: return null
        val start = ipv4ToLong(ip)
        Parsed(e, start, start + (1L shl (32 - prefix)) - 1, prefix, 53)
    } else {
        val ip = e.substringBefore(':')
        val port = e.substringAfter(':', "53").toIntOrNull() ?: 53
        val v = ipv4ToLong(ip)
        Parsed(e, v, v, null, port)
    }
}

fun cleanResolverFile(text: String): ResolverImportResult {
    var cleaned = 0
    var rangesCorrected = 0
    var dashConverted = 0
    val ignored = LinkedHashSet<String>()
    val raw = ArrayList<String>()

    for (rawLine in asciiResolverText(text).lineSequence()) {
        var line = rawLine
        val hash = line.indexOf('#')
        if (hash >= 0) line = line.substring(0, hash)
        val slashes = line.indexOf("//")
        if (slashes >= 0) line = line.substring(0, slashes)
        line = line.trim()
        if (line.isEmpty()) continue

        val matches = ADDRESS_IN_TEXT.findAll(line).toList()
        if (matches.isEmpty()) {
            ignored.add(line.take(40))
            continue
        }
        // Leftover words around the addresses count as a cleaned line.
        val leftover = ADDRESS_IN_TEXT.replace(line, " ")
            .filterNot { it == ',' || it == ';' || it.isWhitespace() }
        if (leftover.isNotEmpty()) cleaned++

        for (m in matches) {
            val token = m.value.replace(" ", "").replace("\t", "")
            if (token.contains('-') || token.contains('\u2013') || token.contains('\u2014')) {
                val expanded = normalizeResolvers(expandDashRanges(token))
                if (expanded.isEmpty()) { ignored.add(m.value.take(40)); continue }
                dashConverted++
                raw.addAll(expanded)
                continue
            }
            val out = normalizeResolvers(token)
            if (out.isEmpty()) { ignored.add(m.value.take(40)); continue }
            val entry = out.first()
            if (token.contains('/')) {
                if (entry.substringBefore('/') != token.substringBefore('/')) {
                    val ipPart = canonicalIpv4(token.substringBefore('/'))
                    if (ipPart != null && ipPart != entry.substringBefore('/')) rangesCorrected++ else cleaned++
                }
            } else if (entry != token && entry != "$token:53") {
                cleaned++
            }
            raw.add(entry)
        }
    }

    val distinct = raw.distinct()
    val duplicates = raw.size - distinct.size

    val parsed = distinct.mapNotNull { parseEntry(it) }
    // Ranges, widest first: keep a range unless a kept range covers it.
    val keptRanges = ArrayList<Parsed>()
    for (r in parsed.filter { it.prefix != null }.sortedWith(compareBy({ it.prefix }, { it.start }))) {
        if (keptRanges.none { it.start <= r.start && r.end <= it.end }) keptRanges.add(r)
    }
    val scannable = keptRanges.filter { 32 - (it.prefix ?: 32) <= MAX_RANGE_HOST_BITS }
    val singles = parsed.filter { it.prefix == null }
    // A single :53 address inside a scannable range is scanned anyway.
    val keptSingles = singles.filter { s ->
        !(s.port == 53 && scannable.any { it.start <= s.start && s.start <= it.end })
    }
    val insideRange = (parsed.count { it.prefix != null } - keptRanges.size) + (singles.size - keptSingles.size)

    val result = (keptRanges + keptSingles)
        .sortedWith(compareBy({ it.start }, { it.prefix ?: 33 }, { it.port }))

    var total = 0L
    for (p in result) {
        if (p.prefix == null) total += 1
        else if (32 - p.prefix <= MAX_RANGE_HOST_BITS) total += 1L shl (32 - p.prefix)
    }
    val oversize = result.filter { it.prefix != null && 32 - it.prefix > MAX_RANGE_HOST_BITS }.map { it.entry }

    return ResolverImportResult(
        entries = result.map { it.entry },
        totalAddresses = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        cleaned = cleaned,
        duplicates = duplicates,
        insideRange = insideRange,
        rangesCorrected = rangesCorrected,
        dashConverted = dashConverted,
        ignored = ignored.toList(),
        oversize = oversize
    )
}

/** Real address count of an already-clean list (no 5000 cap). */
fun resolverAddressCount(entries: List<String>): Int {
    var total = 0L
    for (e in entries) {
        if (!e.contains('/')) { total += 1; continue }
        val prefix = e.substringAfter('/').toIntOrNull() ?: continue
        if (32 - prefix <= MAX_RANGE_HOST_BITS) total += 1L shl (32 - prefix)
    }
    return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
