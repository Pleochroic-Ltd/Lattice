@file:OptIn(InternalAlexandriteApi::class)

package org.foedusprogramme.lattice.alexandrite

import kotlinx.serialization.json.Json
import org.foedusprogramme.lattice.SceneTrustPolicy
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What a graph's dependencies carry for scenes. */
class BackendsTest {
    private val dir = Files.createTempDirectory("lattice-backends")
    private val store = MemoryStore()

    private val files = object : PluginFiles {
        override val dataDir: Path = dir
        override val cacheDir: Path = dir.resolve("cache")
    }

    private fun dependencies(extra: String, bindStores: Boolean = true): org.foedusprogramme.lattice.LatticeDependencies {
        val config = Json.decodeFromString(LatticeConfig.serializer(), config(extra))
        val scenes = SceneHost(
            config,
            files,
            store.transcripts.takeIf { bindStores },
            store.conversations.takeIf { bindStores },
        )
        return Backends(config, dir, emptyList(), Clock.systemUTC(), scenes).dependencies("main")
            .also { it.store.close() }
    }

    @Test
    fun `with scenes on a graph reads the host's conversations under Alexandrite's trust`() {
        val dependencies = dependencies(SCENES_ON)

        assertIs<AlexandriteConversationSource>(dependencies.conversations)
        assertIs<AlexandriteSceneTrustPolicy>(dependencies.sceneTrust)
    }

    @Test
    fun `a host that binds no store is told once at start, with what Lattice goes without`() {
        fun warning(extra: String, transcripts: Boolean, conversations: Boolean): String? = SceneHost(
            Json.decodeFromString(LatticeConfig.serializer(), config(extra)),
            files,
            store.transcripts.takeIf { transcripts },
            store.conversations.takeIf { conversations },
        ).storesWarning()

        assertNull(warning("{}", transcripts = true, conversations = true))
        assertEquals(
            "The host binds no conversation store and transcript store to plugins, so every captured turn and summary " +
                "counts as tainted and conversations are captured in batches of turns instead of scenes",
            warning(SCENES_ON, transcripts = false, conversations = false),
        )
        assertEquals(
            "The host binds no transcript store to plugins, so scenes cannot be turned on",
            warning("{}", transcripts = false, conversations = true),
        )
    }

    @Test
    fun `with scenes off or no store bound a graph has no conversations`() {
        val off = dependencies("""{"model": {"kind": "none"}}""")
        assertNull(off.conversations)
        assertSame(SceneTrustPolicy.Conservative, off.sceneTrust)

        assertNull(dependencies(SCENES_ON, bindStores = false).conversations)
    }
}
