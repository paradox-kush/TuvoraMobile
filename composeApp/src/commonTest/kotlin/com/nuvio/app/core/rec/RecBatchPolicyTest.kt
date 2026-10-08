package com.nuvio.app.core.rec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecBatchPolicyTest {
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
