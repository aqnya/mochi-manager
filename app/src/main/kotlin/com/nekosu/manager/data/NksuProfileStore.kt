package com.nekosu.manager.data

import android.util.Log
import com.nekosu.manager.neko.NekoNative
import java.io.ByteArrayOutputStream

/**
 * NekoSU 内核侧 profile 落盘存储（对齐上游 `neko/store` 的 db.rs）。
 *
 * 内核的授权/profile 表是易失的，重启后由 ncore 读这份文件重新灌回去——管理器只写内核
 * 不落盘的话，重启后授权全部丢失。上游 manager 走 `setProfileProto`（JNI → 内核 +
 * `store::profile::save`），我们自己的 JNI 没有那条链路，这里按同一文件格式直接落盘。
 *
 * 文件布局（小端）：
 * ```
 * header(24B) | profile 索引(16B/条) | feature 索引(16B/条) | profile 载荷 | feature 载荷
 * ```
 * header = `"NKSUPRF\0"` + 版本(u32) + profile 数(u32) + feature 数(u32)；
 * 索引 = key(u32) + 绝对偏移(u64) + 长度(u32)；载荷 = protobuf 的
 * `ProfileEntry{uid=1, root_uid=2, caps=3, ns=4, domain=5}` /
 * `FeatureEntry{id=1, value=2}`。写入走 [NekoNative.writeRootFile] 的
 * 临时文件 + fsync + rename，权限 0600，与上游 persist 语义一致。
 */
object NksuProfileStore {
    private const val TAG = "NekoSU/Store"

    const val STORE_PATH = "/data/adb/nksu/profile"

    private val MAGIC = byteArrayOf('N'.code.toByte(), 'K'.code.toByte(), 'S'.code.toByte(), 'U'.code.toByte(),
        'P'.code.toByte(), 'R'.code.toByte(), 'F'.code.toByte(), 0)
    private const val VERSION = 1
    private const val HEADER_SIZE = 24
    private const val INDEX_SIZE = 16

    /** 内核 profile 表的一条记录（字段对齐 proto ProfileEntry）。 */
    data class ProfileEntry(
        val uid: Int,
        val rootUid: Int,
        val caps: Long,
        val ns: Int,
        val domain: String,
    )

    /** 内核 feature 表的一条记录（管理器不写它，落盘时原样保留）。 */
    data class FeatureEntry(
        val id: Int,
        val value: Long,
    )

    internal data class Store(
        val profiles: List<ProfileEntry>,
        val features: List<FeatureEntry>,
    )

    /**
     * 授权（写入/更新一条 profile）并落盘。读现有文件 → 按 uid upsert → 原子写回。
     * feature 表原样保留。文件不存在时按空表处理，与上游一致。
     */
    fun saveProfile(entry: ProfileEntry): Boolean {
        val current = runCatching { readStore() }
            .onFailure { Log.w(TAG, "读 profile store 失败，按空表处理：${it.message}") }
            .getOrDefault(Store(emptyList(), emptyList()))
        val merged = current.profiles.filterNot { it.uid == entry.uid } + entry
        return writeStore(Store(merged, current.features))
    }

    /** 撤销授权并落盘。条目不存在时视为已成功（幂等，与上游 del 一致）。 */
    fun deleteProfile(uid: Int): Boolean {
        val current = runCatching { readStore() }
            .onFailure { Log.w(TAG, "读 profile store 失败，按空表处理：${it.message}") }
            .getOrDefault(Store(emptyList(), emptyList()))
        if (current.profiles.none { it.uid == uid }) return true
        return writeStore(Store(current.profiles.filterNot { it.uid == uid }, current.features))
    }

    private fun readStore(): Store {
        val bytes = NekoNative.readRootFile(STORE_PATH) ?: return Store(emptyList(), emptyList())
        return decodeStore(bytes)
    }

    private fun writeStore(store: Store): Boolean =
        NekoNative.writeRootFile(STORE_PATH, encodeStore(store))

    // —— 文件格式 ——

    internal fun decodeStore(bytes: ByteArray): Store {
        require(bytes.size >= HEADER_SIZE) { "profile store header is truncated" }
        bytes[0..7].let { head ->
            require(head.contentEquals(MAGIC)) { "profile store magic mismatch" }
        }
        val version = bytes.getLeInt(8)
        require(version == VERSION) { "unsupported profile store version: $version" }
        require(bytes.getLeInt(20) == 0) { "unsupported profile store flags" }
        val profileCount = bytes.getLeInt(12)
        val featureCount = bytes.getLeInt(16)

        val profileIndexBytes = Math.multiplyExact(profileCount, INDEX_SIZE)
        val featureIndexBytes = Math.multiplyExact(featureCount, INDEX_SIZE)
        val dataStart = HEADER_SIZE + profileIndexBytes + featureIndexBytes
        require(dataStart <= bytes.size) { "profile store index is truncated" }

        val profiles = ArrayList<ProfileEntry>(profileCount)
        repeat(profileCount) { position ->
            val offset = HEADER_SIZE + position * INDEX_SIZE
            val index = decodeIndex(bytes, offset)
            val payload = payload(bytes, index, dataStart)
            val entry = decodeProfileEntry(payload)
            require(entry.uid == index.key) { "profile entry key mismatch: ${index.key} != ${entry.uid}" }
            profiles += entry
        }

        val features = ArrayList<FeatureEntry>(featureCount)
        repeat(featureCount) { position ->
            val offset = HEADER_SIZE + profileIndexBytes + position * INDEX_SIZE
            val index = decodeIndex(bytes, offset)
            val payload = payload(bytes, index, dataStart)
            val entry = decodeFeatureEntry(payload)
            require(entry.id == index.key) { "feature entry key mismatch: ${index.key} != ${entry.id}" }
            features += entry
        }
        return Store(profiles, features)
    }

    internal fun encodeStore(store: Store): ByteArray {
        val profileCount = store.profiles.size
        val featureCount = store.features.size
        val profilePayloads = store.profiles.map { encodeProfileEntry(it) }
        val featurePayloads = store.features.map { encodeFeatureEntry(it) }
        val dataStart = HEADER_SIZE + profileCount * INDEX_SIZE + featureCount * INDEX_SIZE
        val totalSize = dataStart + profilePayloads.sumOf { it.size } + featurePayloads.sumOf { it.size }
        val out = ByteArray(totalSize)

        MAGIC.copyInto(out, 0)
        putLeInt(out, 8, VERSION)
        putLeInt(out, 12, profileCount)
        putLeInt(out, 16, featureCount)
        putLeInt(out, 20, 0) // flags，恒 0

        var cursor = dataStart
        for ((position, pair) in store.profiles.zip(profilePayloads).withIndex()) {
            putIndex(out, HEADER_SIZE + position * INDEX_SIZE, pair.first.uid, cursor, pair.second.size)
            pair.second.copyInto(out, cursor)
            cursor += pair.second.size
        }
        val featureIndexStart = HEADER_SIZE + profileCount * INDEX_SIZE
        for ((position, pair) in store.features.zip(featurePayloads).withIndex()) {
            putIndex(out, featureIndexStart + position * INDEX_SIZE, pair.first.id, cursor, pair.second.size)
            pair.second.copyInto(out, cursor)
            cursor += pair.second.size
        }
        return out
    }

    private fun putIndex(
        out: ByteArray,
        at: Int,
        key: Int,
        offset: Int,
        len: Int,
    ) {
        putLeInt(out, at, key)
        putLeLong(out, at + 4, offset.toLong())
        putLeInt(out, at + 12, len)
    }

    private data class Index(
        val key: Int,
        val offset: Int,
        val len: Int,
    )

    private fun decodeIndex(
        bytes: ByteArray,
        at: Int,
    ): Index =
        Index(
            key = bytes.getLeInt(at),
            offset = bytes.getLeInt(at + 4).let {
                // 偏移按 u64 存，实际不会超过文件大小；高 32 位非 0 直接拒绝
                val high = bytes.getLeInt(at + 8)
                require(high == 0) { "profile store offset is too large" }
                it
            },
            len = bytes.getLeInt(at + 12),
        )

    private fun payload(
        bytes: ByteArray,
        index: Index,
        dataStart: Int,
    ): ByteArray {
        require(index.offset >= dataStart) { "profile store entry key=${index.key} points into the index area" }
        val end = Math.addExact(index.offset, index.len)
        require(end <= bytes.size) { "profile store entry key=${index.key} is out of bounds" }
        return bytes.copyOfRange(index.offset, end)
    }

    // —— protobuf（proto3，手写 varint）——

    internal fun encodeProfileEntry(entry: ProfileEntry): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, entry.uid.toLong())
        writeVarintField(out, 2, entry.rootUid.toLong())
        writeVarintField(out, 3, entry.caps)
        // proto3 的 int32 按符号扩展成 64 位再编 varint（负数 10 字节）
        writeVarintField(out, 4, entry.ns.toLong())
        // 上游 prost 对默认值（空串）不编码，保持一致
        if (entry.domain.isNotEmpty()) {
            writeLengthDelimited(out, 5, entry.domain.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    internal fun decodeProfileEntry(payload: ByteArray): ProfileEntry {
        var uid = 0L
        var rootUid = 0L
        var caps = 0L
        var ns = 0L
        var domain = ""
        forEachField(payload) { field, wire, reader ->
            when (field) {
                1 -> requireWire(wire, WIRE_VARINT) { uid = reader.readVarint() }
                2 -> requireWire(wire, WIRE_VARINT) { rootUid = reader.readVarint() }
                3 -> requireWire(wire, WIRE_VARINT) { caps = reader.readVarint() }
                4 -> requireWire(wire, WIRE_VARINT) { ns = reader.readVarint() }
                5 -> requireWire(wire, WIRE_LEN) { domain = String(reader.readBytes(), Charsets.UTF_8) }
            }
        }
        return ProfileEntry(uid.toInt(), rootUid.toInt(), caps, ns.toInt(), domain)
    }

    internal fun encodeFeatureEntry(entry: FeatureEntry): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, entry.id.toLong())
        writeVarintField(out, 2, entry.value)
        return out.toByteArray()
    }

    internal fun decodeFeatureEntry(payload: ByteArray): FeatureEntry {
        var id = 0L
        var value = 0L
        forEachField(payload) { field, wire, reader ->
            when (field) {
                1 -> requireWire(wire, WIRE_VARINT) { id = reader.readVarint() }
                2 -> requireWire(wire, WIRE_VARINT) { value = reader.readVarint() }
            }
        }
        return FeatureEntry(id.toInt(), value)
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_LEN = 2

    private fun writeVarint(
        out: ByteArrayOutputStream,
        value: Long,
    ) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun writeVarintField(
        out: ByteArrayOutputStream,
        field: Int,
        value: Long,
    ) {
        if (value == 0L) return // proto3：零值不编码
        writeVarint(out, (field shl 3 or WIRE_VARINT).toLong())
        writeVarint(out, value)
    }

    private fun writeLengthDelimited(
        out: ByteArrayOutputStream,
        field: Int,
        bytes: ByteArray,
    ) {
        writeVarint(out, (field shl 3 or WIRE_LEN).toLong())
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private class FieldReader(
        private val bytes: ByteArray,
        var pos: Int,
        private val end: Int,
    ) {
        /** 标准 protobuf varint：负数（符号扩展后的 64 位）编成 10 字节也能正确读回。 */
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(pos < end) { "varint truncated" }
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("varint too long")
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            require(len >= 0 && pos + len <= end) { "length-delimited field truncated" }
            val out = bytes.copyOfRange(pos, pos + len)
            pos += len
            return out
        }
    }

    private inline fun forEachField(
        payload: ByteArray,
        onField: (field: Int, wire: Int, reader: FieldReader) -> Unit,
    ) {
        val reader = FieldReader(payload, 0, payload.size)
        while (reader.pos < payload.size) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            require(wire == WIRE_VARINT || wire == WIRE_LEN) { "unsupported wire type $wire for field $field" }
            onField(field, wire, reader)
        }
    }

    private inline fun requireWire(
        actual: Int,
        expected: Int,
        block: () -> Unit,
    ) {
        require(actual == expected) { "unexpected wire type $actual" }
        block()
    }

    // —— 小端字节工具 ——

    private fun putLeInt(
        target: ByteArray,
        at: Int,
        value: Int,
    ) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value ushr 8) and 0xFF).toByte()
        target[at + 2] = ((value ushr 16) and 0xFF).toByte()
        target[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun putLeLong(
        target: ByteArray,
        at: Int,
        value: Long,
    ) {
        for (i in 0 until 8) target[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun ByteArray.getLeInt(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)

    private operator fun ByteArray.get(range: IntRange): ByteArray = copyOfRange(range.first, range.last + 1)
}
