package com.armia.app.data

import android.content.Context
import android.net.Uri
import com.armia.app.NativeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pacote de efeitos: um .zip (ou .json solto) com um arquivo `pack.json` que define o "look" de
 * cada estilo (nitidez, contraste, brilho, sombras…). O app exige o pacote para ligar.
 *
 * Segurança e estabilidade: só lê o pack.json (nada é extraído para o disco), limita tamanhos
 * e força cada número para uma faixa segura. Um pacote ruim nunca gera imagem quebrada.
 */
object PackManager {

    data class Pack(
        val name: String,
        val version: String,
        val targets: Map<FilterStyle, FloatArray>
    )

    data class PackState(val pack: Pack? = null, val error: String? = null)

    private class Field(val key: String, val min: Float, val max: Float)

    // A ORDEM destas duas listas precisa ser a mesma de presets.cpp::applyOverrides (24 campos).
    private val OVERLAY_FIELDS = listOf(
        Field("grain", 0f, 1f), Field("grainSize", 1f, 8f), Field("vignette", 0f, 1f),
        Field("vignetteStart", 0f, 1f), Field("letterbox", 0f, 0.2f),
        Field("tintR", 0f, 1f), Field("tintG", 0f, 1f), Field("tintB", 0f, 1f), Field("tintA", 0f, 1f)
    )
    private val GRADE_FIELDS = listOf(
        Field("denoise", 0f, 1f), Field("sharpen", 0f, 1f), Field("exposure", -1f, 1f),
        Field("contrast", -1f, 1f), Field("saturation", 0f, 2f), Field("vibrance", 0f, 1f),
        Field("temperature", -1f, 1f), Field("shadowLift", 0f, 1f), Field("highlightRoll", 0f, 1f),
        Field("grain", 0f, 0.3f), Field("vignette", 0f, 1f), Field("autoExposure", 0f, 1f),
        Field("clarity", 0f, 1.5f), Field("bloom", 0f, 1f), Field("microShadow", 0f, 1f)
    )

    private const val FILE_NAME = "armia_pack.json"
    private const val MAX_JSON_BYTES = 256 * 1024
    private const val MAX_INPUT_BYTES = 20L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 500

    private val _state = MutableStateFlow(PackState())
    val state: StateFlow<PackState> = _state.asStateFlow()

    @Volatile
    private var loaded = false

    fun isReady(): Boolean = _state.value.pack != null

    /** Carrega o pacote salvo (uma vez por processo). Seguro chamar várias vezes. */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return
        try {
            _state.value = PackState(pack = parse(file.readText()))
        } catch (e: Exception) {
            _state.value = PackState(error = "O pacote salvo está corrompido. Escolha o arquivo de novo.")
        }
    }

    /** Lê e valida o arquivo. BLOQUEIA: chame fora da thread principal. */
    fun importFrom(context: Context, uri: Uri): PackState {
        val app = context.applicationContext
        val previous = _state.value.pack
        return try {
            val text = app.contentResolver.openInputStream(uri)?.use { readPackJson(it) }
                ?: throw IllegalArgumentException("Não consegui abrir o arquivo.")
            val pack = parse(text)
            save(app, text)
            loaded = true
            PackState(pack = pack).also { _state.value = it }
        } catch (e: IllegalArgumentException) {
            fail(previous, e.message ?: "Pacote inválido.")
        } catch (e: IOException) {
            fail(previous, "Erro ao ler o arquivo: ${e.message}")
        } catch (e: SecurityException) {
            fail(previous, "Sem permissão para ler esse arquivo.")
        }
    }

    /** Envia os números do pacote para o motor nativo (estilos fora do pacote voltam ao padrão). */
    fun applyTo(engine: NativeEngine) {
        val pack = _state.value.pack
        for (style in FilterStyle.values()) {
            if (style == FilterStyle.OFF) continue
            engine.setStyleTargets(style.id, pack?.targets?.get(style))
        }
    }

    // ------------------------------------------------------------------ leitura

    private fun fail(previous: Pack?, message: String): PackState =
        PackState(pack = previous, error = message).also { _state.value = it }

    private fun save(context: Context, text: String) {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(text)
        tmp.copyTo(File(context.filesDir, FILE_NAME), overwrite = true)
        tmp.delete()
    }

    private fun readPackJson(raw: InputStream): String {
        val stream = BufferedInputStream(LimitedInputStream(raw, MAX_INPUT_BYTES))
        stream.mark(4)
        val b0 = stream.read()
        val b1 = stream.read()
        stream.reset()
        return if (b0 == 'P'.code && b1 == 'K'.code) readFromZip(stream) else readBounded(stream)
    }

    private fun readFromZip(stream: InputStream): String {
        ZipInputStream(stream).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                if (count > MAX_ZIP_ENTRIES) throw IllegalArgumentException("O zip tem arquivos demais.")
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name == "pack.json") return readBounded(zip)
            }
        }
        throw IllegalArgumentException("Não achei o arquivo pack.json dentro do zip.")
    }

    private fun readBounded(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_JSON_BYTES) {
                throw IllegalArgumentException("O pack.json é grande demais (máximo 256 KB).")
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun parse(text: String): Pack {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("O pack.json não é um JSON válido.")
        }
        if (root.optInt("format", 0) != 1) {
            throw IllegalArgumentException("Formato de pacote não suportado (esperado \"format\": 1).")
        }
        val styles = root.optJSONObject("styles")
            ?: throw IllegalArgumentException("O pacote não tem a seção \"styles\".")

        val targets = LinkedHashMap<FilterStyle, FloatArray>()
        for (style in FilterStyle.values()) {
            if (style == FilterStyle.OFF) continue
            val obj = styles.optJSONObject(style.name) ?: continue
            val values = FloatArray(OVERLAY_FIELDS.size + GRADE_FIELDS.size) { Float.NaN }
            readFields(obj.optJSONObject("overlay"), OVERLAY_FIELDS, values, 0)
            readFields(obj.optJSONObject("grade"), GRADE_FIELDS, values, OVERLAY_FIELDS.size)
            targets[style] = values
        }
        if (targets.isEmpty()) {
            throw IllegalArgumentException("O pacote não define nenhum estilo conhecido.")
        }
        return Pack(
            name = root.optString("name", "Pacote sem nome").take(60),
            version = root.optString("version", "").take(20),
            targets = targets
        )
    }

    private fun readFields(obj: JSONObject?, fields: List<Field>, out: FloatArray, offset: Int) {
        if (obj == null) return
        fields.forEachIndexed { i, field ->
            if (obj.has(field.key)) {
                val v = obj.optDouble(field.key, Double.NaN)
                if (v.isNaN() || v.isInfinite()) {
                    throw IllegalArgumentException("Valor inválido em \"${field.key}\".")
                }
                out[offset + i] = v.toFloat().coerceIn(field.min, field.max)  // faixa segura
            }
        }
    }

    /** Corta a leitura se o arquivo passar do limite (proteção contra zip gigante). */
    private class LimitedInputStream(input: InputStream, private val limit: Long) :
        FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) add(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) add(n.toLong())
            return n
        }

        private fun add(n: Long) {
            count += n
            if (count > limit) throw IOException("arquivo grande demais")
        }
    }
}
