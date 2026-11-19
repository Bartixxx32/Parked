package com.bartixxx.parked.family

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyCryptoTest {

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val okm = FamilyCrypto.hkdf(
            ikm = hex("0b".repeat(22)),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `group key wraps and unwraps with the invite secret`() {
        val material = FamilyCrypto.newGroupKeyMaterial()
        val unwrapped = FamilyCrypto.unwrapGroupKey(
            material.inviteSecret,
            material.keySalt,
            material.keyIv,
            material.wrappedKey,
        )
        assertNotNull(unwrapped)
        assertArrayEquals(material.groupKey, unwrapped)
    }

    @Test
    fun `wrong invite secret cannot unwrap the group key`() {
        val material = FamilyCrypto.newGroupKeyMaterial()
        assertNull(
            FamilyCrypto.unwrapGroupKey(
                FamilyCrypto.newInviteSecret(),
                material.keySalt,
                material.keyIv,
                material.wrappedKey,
            ),
        )
    }

    @Test
    fun `join auth key is deterministic and never equals the secret`() {
        val secret = FamilyCrypto.newInviteSecret()
        val first = FamilyCrypto.joinAuthKey(secret)
        val second = FamilyCrypto.joinAuthKey(secret)
        assertEquals(first, second)
        assertEquals(43, first.length)
        assertTrue(first != secret)
    }

    @Test
    fun `spot payload round trips and has a fixed size`() {
        val key = FamilyCrypto.encodeBase64(ByteArray(32) { it.toByte() })
        val groupKey = FamilyCrypto.decodeBase64(key)
        val payload = FamilySpotPayload(
            carLabel = "WA 12345",
            latitude = 52.2297,
            longitude = 21.0122,
            accuracyMeters = 8.5,
            savedAtEpochMs = 1_700_000_000_000L,
        )
        val encoded = FamilyCrypto.encryptSpot(
            groupKey = groupKey,
            groupId = "group-1",
            deviceId = "device-1",
            keyVersion = 1,
            payload = payload,
        )
        assertEquals(412, FamilyCrypto.decodeBase64(encoded).size)

        val decoded = FamilyCrypto.decryptSpot(
            groupKey = groupKey,
            groupId = "group-1",
            deviceId = "device-1",
            keyVersion = 1,
            encoded = encoded,
        )
        assertEquals(payload, decoded)
    }

    @Test
    fun `tampered or relocated spot payload is rejected`() {
        val groupKey = ByteArray(32) { (it + 7).toByte() }
        val encoded = FamilyCrypto.encryptSpot(
            groupKey = groupKey,
            groupId = "group-1",
            deviceId = "device-1",
            keyVersion = 1,
            payload = FamilySpotPayload(
                carLabel = "Golf",
                latitude = 50.0,
                longitude = 20.0,
                accuracyMeters = null,
                savedAtEpochMs = 1_700_000_000_000L,
            ),
        )
        val tamperedChars = encoded.toCharArray()
        val index = tamperedChars.size / 2
        tamperedChars[index] = if (tamperedChars[index] == 'A') 'B' else 'A'
        val tampered = String(tamperedChars)
        assertNull(
            FamilyCrypto.decryptSpot(
                groupKey = groupKey,
                groupId = "group-1",
                deviceId = "device-1",
                keyVersion = 1,
                encoded = tampered,
            ),
        )
        assertNull(
            FamilyCrypto.decryptSpot(
                groupKey = groupKey,
                groupId = "group-1",
                deviceId = "device-2",
                keyVersion = 1,
                encoded = encoded,
            ),
        )
    }

    @Test
    fun `invite link carries the secret outside the code`() {
        val code = "BSHR4H4P5M"
        val secret = FamilyCrypto.newInviteSecret()
        val link = FamilyCrypto.inviteLink(code, secret)
        val invite = FamilyCrypto.parseInvite(link)
        assertEquals(code, invite?.code)
        assertEquals(secret, invite?.secret)
        assertTrue(link.contains("#$secret"))
    }

    @Test
    fun `bare code is accepted without a secret`() {
        val invite = FamilyCrypto.parseInvite("bshr4-h4p5m")
        assertEquals("BSHR4H4P5M", invite?.code)
        assertNull(invite?.secret)
    }

    @Test
    fun `invite link tolerates trailing punctuation`() {
        val secret = FamilyCrypto.newInviteSecret()
        val invite = FamilyCrypto.parseInvite(
            "Join us: parked://join/BSHR4H4P5M#$secret.",
        )
        assertEquals("BSHR4H4P5M", invite?.code)
        assertEquals(secret, invite?.secret)
    }

    @Test
    fun `malformed links are rejected`() {
        assertNull(FamilyCrypto.parseInvite("parked://join/BSHR4H4P5M#not-a-secret"))
        assertNull(FamilyCrypto.parseInvite("https://example.com/join/BSHR4H4P5M"))
        assertNull(FamilyCrypto.parseInvite(""))
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
