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
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            // 16 idle connections: better HTTP/2 stream multiplexing for concurrent Drive ops
            // (session init + chunk upload + folder queries can share the same TCP connection)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .dispatcher(dispatcher)
            .connectTimeout(60, TimeUnit.SECONDS)
            // callTimeout(0) = no per-call deadline — essential for large backup uploads
            // that can legitimately take many minutes; writeTimeout already guards per-write stalls.
            .callTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

