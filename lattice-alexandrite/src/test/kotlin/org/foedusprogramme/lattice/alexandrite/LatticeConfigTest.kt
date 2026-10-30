package org.foedusprogramme.lattice.alexandrite

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.MemoryTask
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class LatticeConfigTest {
    private fun decode(json: String): LatticeConfig = Json.decodeFromString(LatticeConfig.serializer(), json)

    private fun refused(json: String): String =
        assertFailsWith<IllegalArgumentException>(json) { decode(json) }.message!!

    // Decoding.

    @Test
    fun `a full config decodes into the settings of each graph`() {
        val config = decode(
            """
            {
              "userName": "Alice",
              "assistantNames": ["Ali"],
              "zone": "Asia/Shanghai",
              "storage": {"kind": "pleochroic", "graphPrefix": "home-"},
              "embedder": {"kind": "pleochroic"},
              "model": {"kind": "host", "default": "anthropic/claude-haiku-4-5", "tasks": {"extract": "anthropic/x"}},
              "pleochroic": {"baseUrl": "https://lattice.example.com", "apiKey": "key"},
              "recall": {"limit": 8, "language": "zh", "timeoutMillis": 2000, "fallback": "bake"},
              "capture": {"turnsPerBatch": 2, "onCompaction": false, "episodes": false},
              "sleep": {"at": "04:15", "zone": "Europe/Berlin"},
              "graph": {"entityExtraction": true, "judgeBudget": 10, "readTimeoutSeconds": 5}
            }
            """,
        )

        val settings = config.settings(ZoneOffset.UTC)

        assertEquals("Alice", settings.userName)
        assertEquals(setOf("Ali"), settings.assistantNames)
        assertEquals(ZoneId.of("Asia/Shanghai"), settings.zone)
        assertEquals(Language.ZH, settings.language)
        assertEquals(8, settings.retrievalLimit)
        assertEquals(5.seconds, settings.graphReadTimeout)
        assertEquals(10, settings.judgeBudget)
        assertTrue(settings.entityExtraction)
        assertFalse(settings.episodicSessionNodes)
        assertEquals("home-main", config.storage.graphName("main"))
        assertEquals("anthropic/x", config.modelConfig.modelOf(MemoryTask.EXTRACT))
        assertEquals("anthropic/claude-haiku-4-5", config.modelConfig.modelOf(MemoryTask.JUDGE))
        assertEquals(LocalTime.of(4, 15), config.sleep.time)
        assertEquals(BAKE, config.recall.fallback)
    }

    @Test
    fun `the defaults keep a local graph, recall six memories and sleep at half past three`() {
        val config = decode(BASE_CONFIG)

        assertEquals(LOCAL, config.storage.kind)
        assertEquals("main", config.storage.graphName("main"))
        assertEquals(6, config.recall.limit)
        assertEquals(DROP, config.recall.fallback)
        assertTrue(config.recall.catalogue)
        assertEquals(4, config.capture.turnsPerBatch)
        assertEquals(LocalTime.of(3, 30), config.sleep.time)
        assertEquals(ZoneOffset.UTC, config.settings(ZoneOffset.UTC).zone)
        assertNull(config.pleochroic)
    }

    @Test
    fun `scenes decode into the scene settings and are off by default`() {
        val off = decode(BASE_CONFIG).settings(ZoneOffset.UTC).scenes
        assertFalse(off.enabled)
        assertFalse(off.sceneEpisodes)

        val config = decode(
            config(
                """
                {
                  "model": {"kind": "openai", "baseUrl": "http://127.0.0.1:8080/v1", "default": "qwen3-8b"},
                  "scenes": {
                    "enabled": true, "sceneEpisodes": true, "narrationCheck": "selfCheck",
                    "segmentBatchMessages": 30, "segmentLookaheadMessages": 10, "segmentWindowMessages": 120,
                    "trustedSenders": ["telegram:main@42"], "trustedTools": ["fs.read"], "rawTextTools": ["notes.dump"]
                  }
                }
                """,
            ),
        )
        val scenes = config.settings(ZoneOffset.UTC).scenes

        assertTrue(scenes.enabled)
        assertTrue(scenes.sceneEpisodes)
        assertEquals("selfCheck", scenes.narrationCheck)
        assertEquals(30, scenes.segmentBatchMessages)
        assertEquals(10, scenes.segmentLookaheadMessages)
        assertEquals(120, scenes.segmentWindowMessages)
        assertEquals(listOf("telegram:main@42"), config.scenes.trustedSenders)
    }

    @Test
    fun `scene values are checked, and scenes need a model`() {
        val model = """"model": {"kind": "openai", "baseUrl": "http://x", "default": "m"}"""
        assertContains(refused(config("""{"scenes": {"enabled": true}}""")), "scenes.enabled needs a model")
        assertContains(refused(config("""{$model, "scenes": {"narrationCheck": "strict"}}""")), "scenes.narrationCheck must be")
        assertContains(refused(config("""{"scenes": {"segmentBatchMessages": 0}}""")), "scenes.segmentBatchMessages must be")
        assertContains(refused(config("""{"scenes": {"segmentLookaheadMessages": -1}}""")), "scenes.segmentLookaheadMessages")
        assertContains(refused(config("""{"scenes": {"segmentWindowMessages": 5}}""")), "scenes.segmentWindowMessages")
        assertContains(
            refused(config("""{"scenes": {"trustedSenders": ["alice"]}}""")),
            "scenes.trustedSenders holds 'alice', which is no user address",
        )
        assertContains(refused(config("""{"scenes": {"trustedTools": ["Web Fetch"]}}""")), "scenes.trustedTools holds 'Web Fetch'")
        assertContains(refused(config("""{"scenes": {"rawTextTools": [""]}}""")), "scenes.rawTextTools holds ''")
    }

    @Test
    fun `an unknown key is refused, also in a nested object`() {
        assertFailsWith<SerializationException> { decode(config("""{"recal": {}}""")) }
        assertFailsWith<SerializationException> { decode(config("""{"recall": {"limits": 3}}""")) }
    }

    // Validation.

    @Test
    fun `a missing embedder or model is refused with an example`() {
        assertContains(refused("""{"model": {"kind": "none"}}"""), "embedder is not set: add one, such as")
        val account = """"pleochroic": {"baseUrl": "https://x.io", "apiKey": "k"}"""
        assertContains(refused("""{"embedder": {"kind": "pleochroic"}, $account}"""), "model is not set")
    }

    @Test
    fun `an embedder is checked for its kind`() {
        assertContains(refused(config("""{"embedder": {"kind": "ollama"}}""")), "embedder.kind must be")
        assertContains(
            refused(config("""{"embedder": {"kind": "openai", "model": "m", "dimension": 3}}""")),
            "embedder.baseUrl is not set",
        )
        assertContains(
            refused(config("""{"embedder": {"kind": "openai", "baseUrl": "ftp://x", "model": "m", "dimension": 3}}""")),
            "embedder.baseUrl must be an http or https URL",
        )
        assertContains(
            refused(config("""{"embedder": {"kind": "openai", "baseUrl": "http://x", "model": "m"}}""")),
            "embedder.dimension is not set",
        )
        assertContains(
            refused(config("""{"embedder": {"kind": "pleochroic", "model": "m"}}""")),
            "only read when embedder.kind is \"openai\"",
        )
    }

    @Test
    fun `a model is checked for its kind and tasks`() {
        assertContains(refused(config("""{"model": {"kind": "claude"}}""")), "model.kind must be")
        assertContains(refused(config("""{"model": {"kind": "host"}}""")), "model.default is not set")
        assertContains(
            refused(config("""{"model": {"kind": "host", "default": "no-slash"}}""")),
            "model.default must be written <endpoint>/<model>",
        )
        assertContains(
            refused(config("""{"model": {"kind": "host", "default": "a/b", "tasks": {"summarize": "a/c"}}}""")),
            "model.tasks names no task 'summarize': use extract, judge",
        )
        assertContains(
            refused(config("""{"model": {"kind": "host", "default": "a/b", "apiKey": "k"}}""")),
            "model.baseUrl and model.apiKey are only read when model.kind is \"openai\"",
        )
        assertContains(
            refused(config("""{"model": {"kind": "none", "default": "a/b"}}""")),
            "are not read when model.kind is \"none\"",
        )
        val impatient = """{"model": {"kind": "openai", "baseUrl": "http://x", "default": "m", "timeoutSeconds": 1}}"""
        assertContains(refused(config(impatient)), "model.timeoutSeconds must be between 5 and 3600")
    }

    @Test
    fun `pleochroic is required exactly when something uses it`() {
        assertContains(
            refused(config("""{"storage": {"kind": "pleochroic"}}""")),
            "storage use Pleochroic but pleochroic is not set",
        )
        assertContains(
            refused(config("""{"pleochroic": {"baseUrl": "https://x.example", "apiKey": "k"}}""")),
            "pleochroic is set but nothing uses it",
        )
        assertContains(
            refused(config("""{"storage": {"kind": "pleochroic"}, "pleochroic": {"baseUrl": "https://x.example"}}""")),
            "pleochroic.apiKey is not set",
        )
        assertContains(
            refused(config("""{"storage": {"kind": "local", "graphPrefix": "x-"}}""")),
            "storage.graphPrefix is only read when storage.kind is \"pleochroic\"",
        )
    }

    @Test
    fun `recall, capture, sleep and zone values are checked`() {
        assertContains(refused(config("""{"recall": {"limit": 0}}""")), "recall.limit must be between 1 and 50")
        assertContains(refused(config("""{"recall": {"language": "EN_us"}}""")), "recall.language must be a BCP 47 tag")
        assertContains(refused(config("""{"recall": {"timeoutMillis": 20000}}""")), "well under a hook's 10 seconds")
        assertContains(refused(config("""{"recall": {"fallback": "keep"}}""")), "recall.fallback must be")
        assertContains(refused(config("""{"capture": {"turnsPerBatch": 0}}""")), "capture.turnsPerBatch must be")
        assertContains(refused(config("""{"sleep": {"at": "3:30am"}}""")), "sleep.at must be a time written HH:mm")
        assertContains(refused(config("""{"sleep": {"zone": "Mars/Olympus"}}""")), "sleep.zone is no time zone")
        assertContains(refused(config("""{"zone": "nowhere"}""")), "zone is no time zone")
        assertContains(refused(config("""{"userName": " "}""")), "userName may not be blank")
    }

    // At start.

    @Test
    fun `a bad config stops the start with the path and the fix`() {
        val error = assertFailsWith<RuntimeStartException> {
            latticeHarness(config("""{"recall": {"limit": 99}}""")).execute { fail("the block ran") }
        }

        assertContains(error.message!!, "Invalid config at 'plugins.lattice': recall.limit must be between 1 and 50.")
    }

    @Test
    fun `a host model must name an endpoint the host has`() {
        val error = assertFailsWith<RuntimeStartException> {
            latticeHarness(config("""{"model": {"kind": "host", "default": "nowhere/model"}}"""), backends = null)
                .execute { fail("the block ran") }
        }

        assertContains(
            error.message!!,
            "names the model endpoint 'nowhere', which no model provider contributes: use one of the host's endpoints",
        )
    }

    @Test
    fun `a host model that names a host endpoint starts`() {
        val config = config("""{"model": {"kind": "host", "default": "scripted/test-model"}}""")
        var started = false

        latticeHarness(config, backends = null) { model(ScriptedModel()) }.execute { started = true }

        assertTrue(started)
    }
}
