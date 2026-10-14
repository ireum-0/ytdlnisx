package com.ireum.ytdl.work

import androidx.concurrent.futures.await
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the actual await API used by FormatBatchCancellation, without an Android runtime. */
class FormatBatchCancellationAwaitTest {
    private class Future(
        private val delegate: SettableFuture<Int> = SettableFuture.create(),
    ) : ListenableFuture<Int> by delegate {
        val cancellationFlags = mutableListOf<Boolean>()
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancellationFlags.add(mayInterruptIfRunning)
            return delegate.cancel(mayInterruptIfRunning)
        }
        fun complete(value: Int) { assertTrue(delegate.set(value)) }
        fun fail(failure: Throwable) { assertTrue(delegate.setException(failure)) }
    }

    @Test fun completedFutureReturnsItsValue() = runBlocking {
        val future = Future()
        future.complete(7)
        assertEquals(7, future.await())
        assertTrue(future.cancellationFlags.isEmpty())
    }

    @Test fun completedFutureUnwrapsItsExactFailure() = runBlocking {
        val failure = IllegalArgumentException("completed future failure")
        val future = Future()
        future.fail(failure)
        assertSame(failure, runCatching { future.await() }.exceptionOrNull())
    }

    @Test fun pendingFutureSuspendsUntilItsOwnCompletion() = runBlocking {
        val future = Future()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { future.await() }
        assertFalse(waiter.isCompleted)
        future.complete(11)
        assertEquals(11, waiter.await())
    }

    @Test fun futureFailurePreservesTheOriginalCause() = runBlocking {
        val failure = IllegalStateException("exact future failure")
        val future = Future()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { future.await() }
        }
        future.fail(failure)
        val observed = waiter.await().exceptionOrNull()!!
        assertEquals(failure.javaClass, observed.javaClass)
        assertEquals(failure.message, observed.message)
        // Coroutine stack recovery may copy a Throwable, retaining the exact original as its cause.
        assertTrue(generateSequence(observed) { it.cause }.any { it === failure })
    }

    @Test fun cancelledFutureCancelsItsAwaiter() = runBlocking {
        val future = Future()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { future.await() }
        }
        future.cancel(false)
        assertTrue(waiter.await().exceptionOrNull() is CancellationException)
    }

    @Test fun coroutineCancellationCancelsOnlyItsFutureWithoutInterrupting() = runBlocking {
        val future = Future()
        val unrelated = Future()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { future.await() }
        waiter.cancel()
        waiter.join()
        assertTrue(future.isCancelled)
        assertEquals(listOf(false), future.cancellationFlags)
        assertFalse(unrelated.isCancelled)
        assertTrue(unrelated.cancellationFlags.isEmpty())
    }

    @Test fun timeoutPropagatesCancellationToThePendingFuture() = runBlocking {
        val future = Future()
        val outcome = runCatching { withTimeout(100) { future.await() } }
        assertTrue(outcome.exceptionOrNull() is TimeoutCancellationException)
        assertTrue(future.isCancelled)
        assertEquals(listOf(false), future.cancellationFlags)
    }

    @Test fun nonCancellableWaitRetainsTheFutureUntilItsCompletion() = runBlocking {
        val future = Future()
        var completed = false
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                assertEquals(23, future.await())
                completed = true
            }
        }
        waiter.cancel()
        assertFalse(waiter.isCompleted)
        assertFalse(future.isCancelled)
        future.complete(23)
        waiter.join()
        assertTrue(completed)
        assertTrue(future.cancellationFlags.isEmpty())
    }
}

