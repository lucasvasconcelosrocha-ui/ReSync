package com.resync.app.engine

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe Buffer Pool (Swift Backup style) to reuse large I/O and crypto buffers (128 KB - 256 KB)
 * across backup, restore, streaming compression, and Google Drive upload/download pipelines.
 * Prevents JVM GC thrashing and excessive memory allocations on Android heap.
 */
object BufferPool {

    const val BUFFER_SIZE_256K = 256 * 1024
    const val BUFFER_SIZE_128K = 128 * 1024
    const val BUFFER_SIZE_64K = 64 * 1024

    private const val MAX_POOL_CAPACITY = 16

    private val pool256k = ConcurrentLinkedQueue<ByteArray>()
    private val pool128k = ConcurrentLinkedQueue<ByteArray>()
    private val pool64k = ConcurrentLinkedQueue<ByteArray>()

    /**
     * Acquire a reusable 256 KB buffer from the pool or allocate a new one if empty.
     */
    fun acquire256k(): ByteArray = pool256k.poll() ?: ByteArray(BUFFER_SIZE_256K)

    /**
     * Return a 256 KB buffer to the pool if under capacity limit.
     */
    fun release256k(buffer: ByteArray) {
        if (buffer.size == BUFFER_SIZE_256K && pool256k.size < MAX_POOL_CAPACITY) {
            pool256k.offer(buffer)
        }
    }

    /**
     * Acquire a reusable 128 KB buffer from the pool or allocate a new one if empty.
     */
    fun acquire128k(): ByteArray = pool128k.poll() ?: ByteArray(BUFFER_SIZE_128K)

    /**
     * Return a 128 KB buffer to the pool if under capacity limit.
     */
    fun release128k(buffer: ByteArray) {
        if (buffer.size == BUFFER_SIZE_128K && pool128k.size < MAX_POOL_CAPACITY) {
            pool128k.offer(buffer)
        }
    }

    /**
     * Acquire a reusable 64 KB buffer from the pool or allocate a new one if empty.
     */
    fun acquire64k(): ByteArray = pool64k.poll() ?: ByteArray(BUFFER_SIZE_64K)

    /**
     * Return a 64 KB buffer to the pool if under capacity limit.
     */
    fun release64k(buffer: ByteArray) {
        if (buffer.size == BUFFER_SIZE_64K && pool64k.size < MAX_POOL_CAPACITY) {
            pool64k.offer(buffer)
        }
    }

    /**
     * Safely executes a block using a pooled 256 KB buffer, auto-releasing it on exit.
     */
    inline fun <T> use256k(block: (ByteArray) -> T): T {
        val buffer = acquire256k()
        return try {
            block(buffer)
        } finally {
            release256k(buffer)
        }
    }

    /**
     * Safely executes a block using a pooled 128 KB buffer, auto-releasing it on exit.
     */
    inline fun <T> use128k(block: (ByteArray) -> T): T {
        val buffer = acquire128k()
        return try {
            block(buffer)
        } finally {
            release128k(buffer)
        }
    }
}
