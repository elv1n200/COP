package cop.utils

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsReportTest {
    @Test
    fun `formats a stable privacy notice and sorted mod list`() {
        val report = DiagnosticsReport.format(snapshot())

        assertContains(report, "enabled module names are listed")
        assertContains(report, "no player name, server address, token, individual setting value, or filesystem path")
        assertContains(report, "- COP: 1.7.2")
        assertContains(report, "- Enabled COP modules (2): Chat, Lag Detector")
        assertTrue(report.indexOf("- alpha: 1.0") < report.indexOf("- zeta: 2.0"))
    }

    @Test
    fun `removes control line breaks from external metadata`() {
        val report = DiagnosticsReport.format(
            snapshot().copy(
                javaVendor = "Vendor\nInjected heading",
                loadedMods = listOf(DiagnosticMod("unsafe\r\nid", "1.0\tdev")),
            ),
        )

        assertFalse(report.contains("\nInjected heading"))
        assertContains(report, "Vendor Injected heading")
        assertContains(report, "- unsafe id: 1.0 dev")
    }

    @Test
    fun `uses none for empty enabled module list`() {
        val report = DiagnosticsReport.format(snapshot().copy(enabledModules = emptyList()))
        assertContains(report, "Enabled COP modules (0): none")
        assertEquals(report.trimEnd(), report)
    }

    private fun snapshot() = DiagnosticsSnapshot(
        generatedAt = "2026-08-24T12:00:00Z",
        copVersion = "1.7.2",
        minecraftVersion = "26.1.2",
        loaderVersion = "0.19.2",
        fabricApiVersion = "0.149.0+26.1.2",
        fabricKotlinVersion = "1.13.9+kotlin.2.3.10",
        javaVersion = "25.0.2",
        javaVendor = "Temurin",
        osName = "Windows 11",
        osVersion = "10.0",
        architecture = "amd64",
        maxMemoryMiB = 4096,
        allocatedMemoryMiB = 1024,
        window = "1920x1080 @ 2x",
        worldLoaded = true,
        currentScreen = "ChatScreen",
        inSkyblock = true,
        area = "Catacombs",
        subarea = "boss",
        enabledModules = listOf("Lag Detector", "Chat"),
        loadedMods = listOf(
            DiagnosticMod("zeta", "2.0"),
            DiagnosticMod("alpha", "1.0"),
        ),
    )
}
