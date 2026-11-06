package com.resync.app.cloud.gdrive

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * Singleton OkHttpClient configured for high-performance HTTP/2 streaming,
 * connection pooling, and resilient cloud transfers.
 */
object GDriveNetworkClient {

    val client: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 16
        }
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .dispatcher(dispatcher)
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

