package com.resync.app.cloud.gdrive

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Custom SocketFactory configured with 512 KB send/receive socket buffers and TCP_NODELAY
 * to saturate connection bandwidth and minimize round-trip packet latency for Google Drive HTTP/2 transfers.
 */
class HighPerformanceSocketFactory(
    private val delegate: SocketFactory = SocketFactory.getDefault(),
    private val bufferSize: Int = 512 * 1024 // 512 KB
) : SocketFactory() {
    override fun createSocket(): Socket = configure(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        configure(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        configure(delegate.createSocket(address, port, localAddress, localPort))

    private fun configure(socket: Socket): Socket {
        try {
            socket.sendBufferSize = bufferSize
            socket.receiveBufferSize = bufferSize
            socket.tcpNoDelay = true
        } catch (_: Exception) {}
        return socket
    }
}

/**
 * Singleton OkHttpClient configured for high-performance HTTP/2 streaming,
 * connection pooling, enlarged socket buffers, and resilient cloud transfers.
 */
object GDriveNetworkClient {

    val client: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .dispatcher(dispatcher)
            .socketFactory(HighPerformanceSocketFactory())
            .connectTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

