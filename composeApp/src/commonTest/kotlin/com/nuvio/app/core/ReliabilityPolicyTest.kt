package com.nuvio.app.core
import com.nuvio.app.core.network.BackendFailurePolicy
import com.nuvio.app.core.sync.RealtimeRetryPolicy
import com.nuvio.app.core.rec.RecBatchPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class ReliabilityPolicyTest {
 @Test fun permanentFailuresDoNotRetry() {
   for (code in listOf(400,401,403,404,409,422)) assertFalse(BackendFailurePolicy.retryable(code))
   for (code in listOf(null,408,429,500,503)) assertTrue(BackendFailurePolicy.retryable(code))
   assertEquals(86_400_000L, BackendFailurePolicy.membershipCooldownMs(404))
 }
 @Test fun reconnectBackoffGrowsIsJitteredAndCapped() {
   assertEquals(500L, RealtimeRetryPolicy.delayMs(1,0.0))
   assertEquals(1000L, RealtimeRetryPolicy.delayMs(1,1.0))
   assertEquals(4000L, RealtimeRetryPolicy.delayMs(3,1.0))
   assertEquals(60000L, RealtimeRetryPolicy.delayMs(100,1.0))
   assertTrue(RealtimeRetryPolicy.delayMs(10,0.0) < RealtimeRetryPolicy.delayMs(10,1.0))
 }
 @Test fun batchesRespectUtf8BytesAndCountWithoutLosingOrder() {
   val input=List(105) { "界".repeat(1000)+it }
   val chunks=RecBatchPolicy.chunks(input) { it.joinToString().encodeToByteArray().size+100 }
   assertEquals(input,chunks.flatten())
   assertTrue(chunks.all { it.size<=50 && it.joinToString().encodeToByteArray().size+100<=RecBatchPolicy.MAX_BYTES })
   assertEquals(listOf(50,50,5),RecBatchPolicy.chunks(List(105){it}) { 100 }.map { it.size })
   assertEquals(listOf(listOf("large"),listOf("small")),RecBatchPolicy.chunks(listOf("large","small")) { if ("large" in it) 100000 else 100 })
   assertTrue(RecBatchPolicy.retryable(429)); assertTrue(RecBatchPolicy.retryable(503)); assertFalse(RecBatchPolicy.retryable(400))
 }
}
