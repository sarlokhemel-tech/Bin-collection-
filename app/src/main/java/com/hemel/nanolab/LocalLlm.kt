package com.hemel.nanolab

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

object LocalLlm {
    private var engine: LlmInference? = null
    private var loadedId: String? = null
    private var loadedMax: Int = 0

    @Synchronized
    fun generate(ctx: Context, id: String, file: File, maxTokens: Int, prompt: String): String {
        if (engine == null || loadedId != id || loadedMax != maxTokens) {
            unloadLocked()
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(maxTokens)
                .build()
            engine = LlmInference.createFromOptions(ctx, opts)
            loadedId = id
            loadedMax = maxTokens
        }
        val out = engine!!.generateResponse(prompt) ?: ""
        return clean(out)
    }

    @Synchronized
    fun unload() {
        unloadLocked()
    }

    private fun unloadLocked() {
        try {
            engine?.close()
        } catch (e: Exception) {
        }
        engine = null
        loadedId = null
    }

    private fun clean(s: String): String {
        var t = s
        for (tag in listOf("<end_of_turn>", "<|im_end|>", "<|eot_id|>", "<eos>")) t = t.replace(tag, "")
        return t.trim()
    }

    private fun format(template: String, system: String, turns: List<Turn>): String {
        val sb = StringBuilder()
        when (template) {
            "chatml" -> {
                if (system.isNotBlank()) sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
                for (t in turns) {
                    sb.append("<|im_start|>").append(if (t.role == "user") "user" else "assistant").append('\n')
                        .append(t.text).append("<|im_end|>\n")
                }
                sb.append("<|im_start|>assistant\n")
            }
            "llama3" -> {
                sb.append("<|begin_of_text|>")
                if (system.isNotBlank()) {
                    sb.append("<|start_header_id|>system<|end_header_id|>\n\n").append(system).append("<|eot_id|>")
                }
                for (t in turns) {
                    sb.append("<|start_header_id|>").append(if (t.role == "user") "user" else "assistant")
                        .append("<|end_header_id|>\n\n").append(t.text).append("<|eot_id|>")
                }
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            "plain" -> {
                if (system.isNotBlank()) sb.append(system).append("\n\n")
                for (t in turns) {
                    sb.append(if (t.role == "user") "User: " else "Assistant: ").append(t.text).append('\n')
                }
                sb.append("Assistant:")
            }
            else -> {
                var first = true
                for (t in turns) {
                    if (t.role == "user") {
                        sb.append("<start_of_turn>user\n")
                        if (first && system.isNotBlank()) sb.append(system).append("\n\n")
                        sb.append(t.text).append("<end_of_turn>\n")
                        first = false
                    } else {
                        sb.append("<start_of_turn>model\n").append(t.text).append("<end_of_turn>\n")
                    }
                }
                sb.append("<start_of_turn>model\n")
            }
        }
        return sb.toString()
    }

    fun buildPrompt(template: String, system: String, turns: List<Turn>, maxTokens: Int): String {
        val budget = maxTokens * 2
        var use = turns
        while (true) {
            val p = format(template, system, use)
            if (p.length <= budget || use.size <= 1) return p
            use = use.drop(1)
            while (use.size > 1 && use.first().role != "user") use = use.drop(1)
        }
    }
}
