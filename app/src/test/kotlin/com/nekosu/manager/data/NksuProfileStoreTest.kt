package com.nekosu.manager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘格式与上游 neko/store（db.rs + proto/profile.proto）的兼容性用例。
 */
class NksuProfileStoreTest {
    private fun entry(
        uid: Int = 10086,
        rootUid: Int = 0,
        caps: Long = 0x125L,
        ns: Int = 2,
        domain: String = "u:r:su:s0",
    ) = NksuProfileStore.ProfileEntry(uid, rootUid, caps, ns, domain)

    @Test
    fun `store 往返编解码`() {
        val store =
            NksuProfileStore.Store(
                profiles =
                    listOf(
                        entry(uid = 1),
                        entry(uid = 10086, rootUid = 0, caps = 0x125, ns = 2, domain = "u:r:magisk:s0"),
                    ),
                features =
                    listOf(
                        NksuProfileStore.FeatureEntry(3, 0xFF),
                        NksuProfileStore.FeatureEntry(7, 1),
                    ),
            )
        val decoded = NksuProfileStore.decodeStore(NksuProfileStore.encodeStore(store))
        assertEquals(store.profiles, decoded.profiles)
        assertEquals(store.features, decoded.features)
    }

    @Test
    fun `header 布局与上游一致`() {
        val bytes =
            NksuProfileStore.encodeStore(
                NksuProfileStore.Store(
                    profiles = listOf(entry(uid = 0x0A)),
                    features = listOf(NksuProfileStore.FeatureEntry(1, 2)),
                ),
            )
        assertEquals("NKSUPRF", String(bytes, 0, 7, Charsets.US_ASCII))
        assertEquals(0, bytes[7].toInt())
        assertEquals(1, bytes.getLeIntAt(8)) // version
        assertEquals(1, bytes.getLeIntAt(12)) // profile_count
        assertEquals(1, bytes.getLeIntAt(16)) // feature_count
        assertEquals(0, bytes.getLeIntAt(20)) // flags
        // 索引区：1 条 profile 索引 + 1 条 feature 索引，各 16 字节
        assertEquals(0x0A, bytes.getLeIntAt(24)) // profile key = uid
        assertEquals(1, bytes.getLeIntAt(24 + 16)) // feature key = id
        // 载荷紧跟两个索引区
        val dataStart = 24 + 16 + 16
        assertEquals(dataStart, bytes.getLeIntAt(28)) // profile 偏移（u64 低 32 位）
        assertEquals(dataStart + bytes.getLeIntAt(36), bytes.getLeIntAt(24 + 16 + 4))
    }

    @Test
    fun `ProfileEntry 载荷是合法 proto3`() {
        val payload = NksuProfileStore.encodeProfileEntry(entry(uid = 5, rootUid = 1000, caps = 0x125, ns = 2, domain = "abc"))
        // field1 varint tag=0x08, value=5
        assertEquals(0x08, payload[0].toInt() and 0xFF)
        assertEquals(5, payload[1].toInt() and 0xFF)
        // field2 varint tag=0x10, value=1000
        assertEquals(0x10, payload[2].toInt() and 0xFF)
        // field5 length-delimited tag=0x2A, len=3, "abc"
        val tag2a = payload.indexOfFirst { (it.toInt() and 0xFF) == 0x2A }
        assertTrue(tag2a > 0)
        assertEquals(3, payload[tag2a + 1].toInt() and 0xFF)
        assertEquals("abc", String(payload, tag2a + 2, 3, Charsets.UTF_8))
        assertEquals(entry(uid = 5, rootUid = 1000, caps = 0x125, ns = 2, domain = "abc"),
            NksuProfileStore.decodeProfileEntry(payload))
    }

    @Test
    fun `负数 ns 按符号扩展 64 位 varint 编码且可读回`() {
        val payload = NksuProfileStore.encodeProfileEntry(entry(uid = 1, ns = -1))
        assertEquals(-1, NksuProfileStore.decodeProfileEntry(payload).ns)
    }

    @Test
    fun `零值字段按 proto3 不编码`() {
        val payload = NksuProfileStore.encodeProfileEntry(entry(uid = 0, rootUid = 0, caps = 0, ns = 0, domain = ""))
        assertTrue(payload.isEmpty())
    }

    @Test
    fun `坏魔数与截断被拒绝`() {
        val good =
            NksuProfileStore.encodeStore(
                NksuProfileStore.Store(profiles = listOf(entry()), features = emptyList()),
            )
        val badMagic = good.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { NksuProfileStore.decodeStore(badMagic) }
        assertThrows(IllegalArgumentException::class.java) { NksuProfileStore.decodeStore(good.copyOfRange(0, 10)) }
    }

    @Test
    fun `caps 超过 32 位可往返`() {
        val e = entry(caps = 0x0000_0080_0000_0025L)
        assertEquals(e.caps, NksuProfileStore.decodeProfileEntry(NksuProfileStore.encodeProfileEntry(e)).caps)
    }

    @Test
    fun `空 store 编出 24 字节 header`() {
        val bytes = NksuProfileStore.encodeStore(NksuProfileStore.Store(emptyList(), emptyList()))
        assertEquals(24, bytes.size)
        assertEquals(0, NksuProfileStore.decodeStore(bytes).profiles.size)
    }

    private fun ByteArray.getLeIntAt(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)
}
