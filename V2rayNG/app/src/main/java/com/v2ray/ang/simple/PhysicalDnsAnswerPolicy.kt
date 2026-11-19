package com.v2ray.ang.simple

/** Reject synthetic block answers without treating valid LAN addresses as failures. */
internal object PhysicalDnsAnswerPolicy {
    fun accepts(query: ByteArray, response: ByteArray): Boolean = runCatching {
        val q = Packet(query)
        val r = Packet(response)
        require(q.u16(0) == r.u16(0) && r.u16(2) and 0x8000 != 0)
        require(q.u16(4) == 1 && r.u16(4) == 1)
        val question = q.name(12)
        val answerQuestion = r.name(12)
        require(question.first.equals(answerQuestion.first, ignoreCase = true))
        require(q.u16(question.second) == r.u16(answerQuestion.second))
        require(q.u16(question.second + 2) == r.u16(answerQuestion.second + 2))
        val host = question.first.lowercase()
        // Local development/service names may intentionally name loopback.
        if (!host.contains('.') || host == "localhost" ||
            listOf(".localhost", ".local", ".lan", ".internal").any(host::endsWith)) return@runCatching true
        var offset = answerQuestion.second + 4
        repeat(r.u16(6)) {
            offset = r.name(offset).second
            val type = r.u16(offset)
            val clazz = r.u16(offset + 2)
            val size = r.u16(offset + 8)
            offset += 10
            require(offset + size <= response.size)
            if (clazz == 1 && type == 1) {
                require(size == 4)
                if (r.byte(offset) in listOf(0, 127)) return@runCatching false
            }
            if (clazz == 1 && type == 28) {
                require(size == 16)
                val address = response.copyOfRange(offset, offset + size)
                val zeroPrefix = address.take(15).all { it == 0.toByte() }
                val mapped = address.take(10).all { it == 0.toByte() } &&
                    address[10] == 0xff.toByte() && address[11] == 0xff.toByte()
                if (zeroPrefix && address[15] in listOf(0.toByte(), 1.toByte()) ||
                    mapped && (address[12].toInt() and 255) in listOf(0, 127)) return@runCatching false
            }
            offset += size
        }
        true
    }.getOrDefault(false)

    private class Packet(private val data: ByteArray) {
        fun byte(offset: Int): Int { require(offset in data.indices); return data[offset].toInt() and 255 }
        fun u16(offset: Int) = (byte(offset) shl 8) or byte(offset + 1)
        fun name(start: Int): Pair<String, Int> {
            var offset = start
            var end = -1
            val seen = HashSet<Int>()
            val labels = ArrayList<String>()
            while (true) {
                require(seen.add(offset) && seen.size <= 128)
                val size = byte(offset)
                when {
                    size == 0 -> return labels.joinToString(".") to if (end < 0) offset + 1 else end
                    size and 0xc0 == 0xc0 -> {
                        if (end < 0) end = offset + 2
                        offset = ((size and 63) shl 8) or byte(offset + 1)
                    }
                    else -> {
                        require(size in 1..63 && offset + 1 + size <= data.size)
                        labels += String(data, offset + 1, size, Charsets.US_ASCII)
                        offset += size + 1
                    }
                }
            }
        }
    }
}
