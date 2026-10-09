package com.lumisignal.idxscreener.network

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockbitTokenPolicyTest {
    private fun jwt(exp: Long = 2_000_000_000L): String {
        fun encode(value: String) = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray())

        val header = encode("""{"alg":"none"}""")
        val payload = encode("""{"exp":$exp}""")
        return "$header.$payload.signature"
    }

    @Test
    fun onlyStockbitAuthenticationHostsAndPathsAreAccepted() {
        assertTrue(StockbitTokenPolicy.tokenUrlAllowed("https://exodus.stockbit.com/login/v6/username"))
        assertTrue(StockbitTokenPolicy.tokenUrlAllowed("https://exodus.stockbit.com/login/v3/new-device/prompt/verify"))
        assertTrue(StockbitTokenPolicy.tokenUrlAllowed("https://wssocial.stockbit.com/auth/v2/login"))
        assertFalse(StockbitTokenPolicy.tokenUrlAllowed("https://evil.test/x/exodus.stockbit.com/login/v6/username"))
        assertFalse(StockbitTokenPolicy.tokenUrlAllowed("https://api-sekuritas.stockbit.com/auth/v2/login"))
        assertTrue(StockbitTokenPolicy.tokenUrlAllowed("https://exodus.stockbit.com/login/v6/otp"))
        assertFalse(StockbitTokenPolicy.tokenUrlAllowed("https://exodus.stockbit.com/marketdetectors/BBCA"))
    }

    @Test
    fun nestedTokensAndExpiryAreReadWithoutGuessing() {
        val refresh = jwt(2_100_000_000L)
        val access = jwt()
        val body = JSONObject(
            """{"data":{"token_data":{"refresh":{"token":"$refresh"},"access":{"token":"$access"}}}}"""
        )

        assertEquals(refresh, StockbitTokenPolicy.findToken(body, "refresh"))
        assertEquals(access, StockbitTokenPolicy.findToken(body, "access"))
        assertEquals(2_000_000_000L, StockbitTokenPolicy.jwtExpiry(access))
    }
}
