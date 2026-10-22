package com.nostrpost.app.nostr

import fr.acinq.secp256k1.Secp256k1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Official BIP-340 test vectors from
 * https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv.
 *
 * The rows below are copied verbatim (indices 0-6) so the expectations are not
 * derived from the implementation under test.
 */
class Bip340VectorsTest {

    data class Bip340Vector(
        val index: Int,
        val secretKey: String,
        val publicKey: String,
        val auxRand: String,
        val message: String,
        val signature: String,
        val expected: Boolean,
        val comment: String,
    )

    private val vectors = listOf(
        Bip340Vector(
            0,
            "0000000000000000000000000000000000000000000000000000000000000003",
            "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
            true,
            "",
        ),
        Bip340Vector(
            1,
            "B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "0000000000000000000000000000000000000000000000000000000000000001",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A",
            true,
            "",
        ),
        Bip340Vector(
            2,
            "C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9",
            "DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8",
            "C87AA53824B4D7AE2EB035A2B5BBBCCC080E76CDC6D1692C4B0B62D798E6D906",
            "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
            "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1BAB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7",
            true,
            "",
        ),
        Bip340Vector(
            3,
            "0B432B2677937381AEF05BB02A66ECD012773062CF3FA2549E44F58ED2401710",
            "25D1DFF95105F5253C4022F628A996AD3A0D95FBF21D468A1B33F8C160D8F517",
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            "7EB0509757E246F19449885651611CB965ECC1A187DD51B64FDA1EDC9637D5EC97582B9CB13DB3933705B32BA982AF5AF25FD78881EBB32771FC5922EFC66EA3",
            true,
            "test fails if msg is reduced modulo p or n",
        ),
        Bip340Vector(
            4,
            "",
            "D69C3509BB99E412E68B0FE8544E72837DFA30746D8BE2AA65975F29D22DC7B9",
            "",
            "4DF3C3F68FCC83B27E9D42C90431A72499F17875C81A599B566C9889B9696703",
            "00000000000000000000003B78CE563F89A0ED9414F5AA28AD0D96D6795F9C6376AFB1548AF603B3EB45C9F8207DEE1060CB71C04E80F593060B07D28308D7F4",
            true,
            "",
        ),
        Bip340Vector(
            5,
            "",
            "EEFDEA4CDB677750A420FEE807EACF21EB9898AE79B9768766E4FAA04A2D4A34",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E17776969E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B",
            false,
            "public key not on the curve",
        ),
        Bip340Vector(
            6,
            "",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "FFF97BD5755EEEA420453A14355235D382F6472F8568A18B2F057A14602975563CC27944640AC607CD107AE10923D9EF7A73C643E166BE5EBEAFA34B1AC553E2",
            false,
            "has_even_y(R) is false",
        ),
    )

    private val secp = Secp256k1.get()

    @Test
    fun `vectors 0-3 derive x-only keys, sign with aux rand and verify`() {
        val oddYIndices = mutableListOf<Int>()

        for (vector in vectors.filter { it.index in 0..3 }) {
            val secretKey = Hex.decode(vector.secretKey)
            val expectedPublicKey = Hex.decode(vector.publicKey)
            val auxRand = Hex.decode(vector.auxRand)
            val message = Hex.decode(vector.message)
            val expectedSignature = Hex.decode(vector.signature)

            val uncompressed = secp.pubkeyCreate(secretKey)
            assertEquals("vector ${vector.index} pubkey length", 65, uncompressed.size)
            assertEquals("vector ${vector.index} uncompressed prefix", 0x04, uncompressed[0].toInt())
            assertArrayEquals(
                "vector ${vector.index} x-only pubkey",
                expectedPublicKey,
                uncompressed.copyOfRange(1, 33),
            )
            if ((uncompressed[64].toInt() and 1) == 1) {
                oddYIndices += vector.index
            }

            // Wrapper used by the app for x-only pubkey derivation.
            assertEquals(
                "vector ${vector.index} NostrCrypto pubkey",
                vector.publicKey.lowercase(),
                NostrCrypto.pubkeyFromPriv(vector.secretKey),
            )

            // BIP-340 signing is deterministic when aux_rand is supplied.
            val signature = secp.signSchnorr(message, secretKey, auxRand)
            assertArrayEquals(
                "vector ${vector.index} BIP-340 signature",
                expectedSignature,
                signature,
            )
            assertTrue(
                "vector ${vector.index} direct verify",
                secp.verifySchnorr(expectedSignature, message, expectedPublicKey),
            )
            assertTrue(
                "vector ${vector.index} NostrCrypto verify",
                NostrCrypto.verify(vector.message, vector.publicKey, vector.signature),
            )

            // NostrCrypto.sign generates its own aux rand, so its signature is
            // not byte-for-byte deterministic, but it must still verify.
            val wrapperSignature = NostrCrypto.sign(message, secretKey)
            assertTrue(
                "vector ${vector.index} NostrCrypto sign",
                secp.verifySchnorr(Hex.decode(wrapperSignature), message, expectedPublicKey),
            )
            assertTrue(
                "vector ${vector.index} NostrCrypto sign/verify roundtrip",
                NostrCrypto.verify(vector.message, vector.publicKey, wrapperSignature),
            )
        }

        // Vector 3 (secret key 0B432B...) is the odd-y key: pubkeyCreate()
        // returns an odd y, so BIP-340 signing must negate the secret key.
        assertTrue(
            "At least one of vectors 0-3 must have odd y; odd-y vectors: $oddYIndices",
            oddYIndices.isNotEmpty(),
        )
        assertEquals("Odd-y vector must be index 3", listOf(3), oddYIndices)
    }

    @Test
    fun `vectors 4-6 verify with the expected results`() {
        for (vector in vectors.filter { it.index in 4..6 }) {
            val signature = Hex.decode(vector.signature)
            val message = Hex.decode(vector.message)
            val publicKey = Hex.decode(vector.publicKey)

            val directResult = try {
                secp.verifySchnorr(signature, message, publicKey)
            } catch (e: Exception) {
                // The library may reject the off-curve public key in vector 5
                // with an exception instead of returning false.
                assertEquals(
                    "Only vector 5 may fail with an invalid-public-key exception",
                    5,
                    vector.index,
                )
                false
            }
            assertEquals(
                "vector ${vector.index} direct verify (${vector.comment})",
                vector.expected,
                directResult,
            )
            assertEquals(
                "vector ${vector.index} NostrCrypto verify (${vector.comment})",
                vector.expected,
                NostrCrypto.verify(vector.message, vector.publicKey, vector.signature),
            )
            if (vector.index == 5) {
                // Off-curve pubkey: both the direct API (if it returns) and the
                // wrapper must reject it.
                assertFalse("vector 5 must be rejected", directResult)
            }
        }
    }
}
