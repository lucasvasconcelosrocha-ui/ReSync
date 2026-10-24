package com.resync.app

import com.resync.app.engine.CpuGovernor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuGovernorTelemetryTest {

    @Test
    fun testFormatTelemetryLogDoesNotThrowException() {
        // Common and boundary test values: 0%, 18.5%, 100%, negative, large values
        val testCpuPcts = listOf(0, 18, 19, 50, 100, 150)
        val testCpuMsList = listOf(0L, 1250L, 131620L)
        val testSpeedsMBps = listOf(0.0, 1.5, 18.5, 102.75)
        val testTemperatures = listOf(25.0f, 38.5f, 42.0f, 45.2f)
        val testStages = listOf("Init", "Processamento de Aplicativos", "Compactação", "Finalizado", "Stage With % Special Chars %m %s")

        for (cpuPct in testCpuPcts) {
            for (cpuMs in testCpuMsList) {
                for (speed in testSpeedsMBps) {
                    for (temp in testTemperatures) {
                        for (stage in testStages) {
                            val formatted = CpuGovernor.formatTelemetryLog(
                                avgPct = cpuPct,
                                nowCpuMs = cpuMs,
                                speedMBps = speed,
                                currentTemp = temp,
                                stageName = stage
                            )
                            // Assert formatting structure and safety
                            assertTrue("Formatted log must contain telemetria header", formatted.startsWith("[Telemetria 10s]"))
                            assertTrue("Formatted log must contain CPU percentage", formatted.contains("CPU: $cpuPct%"))
                            assertTrue("Formatted log must contain dot decimal from Locale.US", formatted.contains("."))
                            assertFalse("Formatted log must not contain unparsed format errors", formatted.contains("UnknownFormat"))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun testFormatTelemetryLogSpecificValues() {
        val result0 = CpuGovernor.formatTelemetryLog(
            avgPct = 0,
            nowCpuMs = 0L,
            speedMBps = 0.0,
            currentTemp = 30.0f,
            stageName = "Init"
        )
        assertTrue(result0.contains("CPU: 0%"))
        assertTrue(result0.contains("Vel: 0.0 MB/s"))
        assertTrue(result0.contains("Temp: 30.0 °C"))

        val result185 = CpuGovernor.formatTelemetryLog(
            avgPct = 18,
            nowCpuMs = 1540L,
            speedMBps = 18.5,
            currentTemp = 42.1f,
            stageName = "Compressão"
        )
        assertTrue(result185.contains("CPU: 18%"))
        assertTrue(result185.contains("Vel: 18.5 MB/s"))
        assertTrue(result185.contains("Temp: 42.1 °C"))

        val result100 = CpuGovernor.formatTelemetryLog(
            avgPct = 100,
            nowCpuMs = 89000L,
            speedMBps = 45.2,
            currentTemp = 37.8f,
            stageName = "Upload"
        )
        assertTrue(result100.contains("CPU: 100%"))
        assertTrue(result100.contains("Vel: 45.2 MB/s"))
        assertTrue(result100.contains("Temp: 37.8 °C"))
    }
}

