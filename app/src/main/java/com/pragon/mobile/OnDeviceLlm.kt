@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.pragon.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import org.json.JSONObject
import java.io.File

/**
 * v10: the on-device models, run with Google's LiteRT-LM (CPU). Nothing here touches the network.
 *
 *  ROUTER  FunctionGemma 270M. Reads what you said and picks ONE tool from ToolCatalog (or none). Used only when the built-in
 *          phrase rules did not understand you, so ordinary commands never wait for a model.
 *  CHAT    Optional second model (chat.litertlm) for free conversation. Without it, offline Pragon answers "I can't chat offline".
 *
 * Each model loads lazily the first time it is needed (a few seconds), stays in memory while you use it, and is released after
 * [IDLE_MS] of silence so it doesn't hold RAM. Say "load the model" before you need it to skip the first-use wait.
 */
object OnDeviceLlm {

    private const val IDLE_MS = 5 * 60_000L

    @Volatile var routerState = "not loaded"      // "not loaded", "loading", "ready", "error: ..."
    @Volatile var chatState = "not loaded"

    class Call(val name: String, val args: Map<String, String>)

    private class Slot(val label: String) {
        var engine: Engine? = null
        var path: String = ""
        @Volatile var lastUse = 0L
    }

    private val router = Slot("router")
    private val chat = Slot("chat")
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var janitor = false

    private fun setState(slot: Slot, s: String) { if (slot === router) routerState = s else chatState = s }

    private fun ensure(ctx: Context, slot: Slot, file: File): Engine? = synchronized(lock) {
        val have = slot.engine
        if (have != null && slot.path == file.absolutePath) { slot.lastUse = Metrics.now(); return have }
        close(slot)
        setState(slot, "loading")
        val t0 = Metrics.now()
        try {
            val e = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path))
            e.initialize()
            slot.engine = e
            slot.path = file.absolutePath
            slot.lastUse = Metrics.now()
            setState(slot, "ready")
            Metrics.add("load_" + slot.label, Metrics.now() - t0)
            startJanitor()
            e
        } catch (t: Throwable) {
            setState(slot, "error: " + (t.message ?: t.javaClass.simpleName))
            null
        }
    }

    private fun close(slot: Slot) {
        try { slot.engine?.close() } catch (t: Throwable) { }
        slot.engine = null
        slot.path = ""
        setState(slot, "not loaded")
    }

    private fun startJanitor() {
        if (janitor) return
        janitor = true
        main.postDelayed(object : Runnable {
            override fun run() {
                synchronized(lock) {
                    val now = Metrics.now()
                    for (s in listOf(router, chat)) if (s.engine != null && now - s.lastUse > IDLE_MS) close(s)
                    janitor = router.engine != null || chat.engine != null
                }
                if (janitor) main.postDelayed(this, 60_000L)
            }
        }, 60_000L)
    }

    /** Frees everything now (called when the phone is low on memory). */
    fun release() = synchronized(lock) { close(router); close(chat) }

    /** Loads the router in the background so the first command isn't slow. Safe to call often. */
    fun warmup(ctx: Context) {
        val app = ctx.applicationContext
        val f = Offline.routerFile(app) ?: return
        if (router.engine != null) return
        Thread { ensure(app, router, f) }.start()
    }

    // ── routing ──────────────────────────────────────────────────────────

    private const val ROUTER_SYSTEM =
        "You control an Android phone. Call exactly one function that matches the user's request. " +
            "If no function fits, answer with no function call."

    /** One ToolCatalog tool as a LiteRT-LM OpenApiTool. The model only PROPOSES a call; we run it ourselves after checking it. */
    private class CatalogTool(private val t: ToolCatalog.Tool) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = ToolCatalog.schemaJson(t)
        override fun execute(paramsJsonString: String): String = "{}"   // never called: automaticToolCalling = false
    }

    /** Blocking (call from a worker thread). null = the model is missing, failed, or chose no tool. */
    fun route(ctx: Context, utterance: String): Call? {
        val file = Offline.routerFile(ctx) ?: return null
        val engine = ensure(ctx, router, file) ?: return null
        return try {
            val cfg = ConversationConfig(
                systemInstruction = Contents.of(ROUTER_SYSTEM),
                tools = ToolCatalog.tools.map { tool(CatalogTool(it)) },
                automaticToolCalling = false,
                samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.1),
            )
            engine.createConversation(cfg).use { conv ->
                val msg = conv.sendMessage(utterance)
                val tc = msg.toolCalls.firstOrNull() ?: return null
                Call(tc.name, toStringMap(tc.arguments))
            }
        } catch (t: Throwable) {
            setState(router, "error: " + (t.message ?: t.javaClass.simpleName))
            null
        } finally {
            router.lastUse = Metrics.now()
        }
    }

    /** Arguments arrive as a map (or JSON text, depending on the runtime version); both are flattened to strings. */
    private fun toStringMap(raw: Any?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun put(k: Any?, v: Any?) {
            if (k == null || v == null) return
            var s = v.toString()
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
            out[k.toString()] = s
        }
        when (raw) {
            null -> {}
            is Map<*, *> -> for ((k, v) in raw) put(k, v)
            else -> try {
                val j = JSONObject(raw.toString())
                for (k in j.keys()) put(k, j.get(k))
            } catch (e: Exception) { }
        }
        return out
    }

    // ── optional offline chat ────────────────────────────────────────────

    private var chatConv: com.google.ai.edge.litertlm.Conversation? = null
    private var chatTurns = 0

    /** Blocking. null = no chat model installed or it failed. Keeps the last few turns so follow-ups make sense. */
    fun chat(ctx: Context, text: String, maxTokens: Int = 256): String? {
        val file = Offline.chatFile(ctx) ?: return null
        val engine = ensure(ctx, chat, file) ?: return null
        return try {
            synchronized(lock) {
                if (chatConv == null || chatTurns >= 8) {
                    try { chatConv?.close() } catch (t: Throwable) { }
                    chatConv = engine.createConversation(ConversationConfig(
                        systemInstruction = Contents.of("You are Pragon, a calm, brief phone assistant. Answer in one to three short spoken sentences, no lists."),
                        maxOutputToken = maxTokens,
                    ))
                    chatTurns = 0
                }
                chatTurns++
                chatConv!!.sendMessage(text).toString().trim().ifEmpty { null }
            }
        } catch (t: Throwable) {
            setState(chat, "error: " + (t.message ?: t.javaClass.simpleName))
            chatConv = null
            null
        } finally {
            chat.lastUse = Metrics.now()
        }
    }
}
