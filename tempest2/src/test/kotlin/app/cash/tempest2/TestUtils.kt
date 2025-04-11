package app.cash.tempest2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Exists to silence Intellij warning "Junit test should return Unit".
fun runBlockingTest(block: suspend CoroutineScope.() -> Unit) {
  runBlocking(block = block)
}

/**
 * @param tickOnNow ticks the clock forward this amount every time [instant] is called, result includes the updated time.
 *
 * Test should use [getInstant] et al. to read the current time without ticking the clock.
 */
class FakeClock(val tickOnNow: Duration = 0.seconds) : Clock() {
  private var epochNow = 452_001_600_000

  fun add(duration: Duration) {
    epochNow += duration.inWholeMilliseconds
  }

  fun getInstant() = Instant.ofEpochMilli(epochNow)

  fun getDate() = Date.from(Instant.ofEpochMilli(epochNow))

  fun minusTicks(ticks: Int) = Instant.ofEpochMilli(epochNow - (ticks * tickOnNow.inWholeMilliseconds))

  override fun instant(): Instant {
    epochNow += tickOnNow.inWholeMilliseconds
    return Instant.ofEpochMilli(epochNow)
  }

  override fun withZone(zone: ZoneId?): Clock =
    TODO("Not yet implemented")

  override fun getZone(): ZoneId =
    ZoneId.systemDefault()
}
