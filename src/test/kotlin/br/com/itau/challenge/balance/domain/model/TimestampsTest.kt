package br.com.itau.challenge.balance.domain.model

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class TimestampsTest {

    @Test
    fun `converts epoch microseconds to an instant without losing precision`() {
        val result = microsToInstant(1751641364589998L)

        assertEquals(1751641364L, result.epochSecond)
        assertEquals(589_998_000, result.nano)
        assertEquals(Instant.parse("2025-07-04T15:02:44.589998Z"), result)
    }

    @Test
    fun `converts the epoch itself`() {
        assertEquals(Instant.EPOCH, microsToInstant(0))
    }

    /**
     * Floor division, not truncation toward zero. With `/` and `%`, a pre-epoch timestamp
     * would produce a negative nanosecond adjustment and `Instant` would reject it — or, worse
     * on another code path, silently land a second off.
     */
    @Test
    fun `converts a timestamp before the epoch`() {
        val result = microsToInstant(-1_500_000L)

        assertEquals(Instant.parse("1969-12-31T23:59:58.500Z"), result)
    }
}
