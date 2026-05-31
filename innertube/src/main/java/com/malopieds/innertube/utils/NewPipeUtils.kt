package com.malopieds.innertube.utils

import com.malopieds.innertube.YouTube
import com.malopieds.innertube.models.YouTubeClient
import com.malopieds.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.IOException
import java.net.Proxy

object NewPipeUtils {
    init {
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy))
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> = runCatching {
        YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String) = runCatching {
        format.url?.let { directUrl ->
            return@runCatching deobfuscateOrStrip(videoId, directUrl)
        }
        format.signatureCipher?.let { signatureCipher ->
            val params = parseQueryString(signatureCipher)
            val obfuscatedSignature = params["s"] ?: throw ParsingException("Could not parse cipher signature")
            val signatureParam = params["sp"] ?: throw ParsingException("Could not parse cipher signature parameter")
            val url = params["url"]?.let { URLBuilder(it) } ?: throw ParsingException("Could not parse cipher url")
            val deobfuscated = try {
                YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, obfuscatedSignature)
            } catch (_: Exception) {
                // NewPipe couldn't parse the deobfuscation function for this player version.
                // Fall back to our own implementation which parses the player JS directly.
                fallbackDeobfuscateSignature(obfuscatedSignature)
                    ?: run {
                        // Still failed — strip n and return the unsigned URL as a last resort.
                        url.parameters.remove("n")
                        return@runCatching url.toString()
                    }
            }
            url.parameters[signatureParam] = deobfuscated
            return@runCatching deobfuscateOrStrip(videoId, url.toString())
        }
        throw ParsingException("Could not find format url")
    }

    /**
     * Fallback sig deobfuscation for YouTube players that use the Bm-dispatch pattern,
     * which NewPipe's regex patterns don't recognise.
     *
     * Fetches the current player JS, extracts the operation sequence from the
     * Bm function and the J6 helper-object, then applies those operations.
     *
     * Returns null if extraction fails so the caller can fall back further.
     */
    private fun fallbackDeobfuscateSignature(obfuscatedSig: String): String? {
        return try {
            val js = fetchPlayerJs() ?: return null
            val ops = extractSigOps(js) ?: return null
            applySigOps(obfuscatedSig, ops)
        } catch (_: Exception) {
            null
        }
    }

    /** Fetches the current YouTube player base.js. */
    private fun fetchPlayerJs(): String? {
        return try {
            // Step 1: get the current player URL from YouTube's main page
            val ytPage = httpClient.newCall(
                Request.Builder()
                    .url("https://www.youtube.com/")
                    .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)
                    .build()
            ).execute().use { it.body?.string() } ?: return null

            val playerUrl = Regex(""""PLAYER_JS_URL":"(/s/player/[^"]+)"""")
                .find(ytPage)?.groupValues?.get(1) ?: return null

            // Step 2: fetch the player JS itself
            httpClient.newCall(
                Request.Builder()
                    .url("https://www.youtube.com$playerUrl")
                    .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)
                    .build()
            ).execute().use { it.body?.string() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts the signature operation sequence from the player JS.
     *
     * Handles the "Bm-dispatch" pattern used in players where the sig transform
     * is encoded as a series of indexed lookups against a string array (F[]).
     *
     * Returns a list of (type, arg) pairs where type is "reverse", "swap", or "splice".
     */
    private fun extractSigOps(js: String): List<Pair<String, Int>>? {
        // Parse the F[] string array that all symbol lookups go through.
        val fArray = Regex("""var\s+F\s*=\s*"([^"]+)"\.split\(";"\)""")
            .find(js)?.groupValues?.get(1)?.split(";") ?: return null

        // Find the Bm(e, r, sig) call inside the dq URL-builder function.
        val bmCallMatch = Regex("""\bBm\s*\(\s*(\d+)\s*,\s*(\d+)\s*,""").find(js) ?: return null
        val eVal = bmCallMatch.groupValues[1].toInt()
        val rVal = bmCallMatch.groupValues[2].toInt()
        val B = rVal xor eVal

        // Find the J6 helper object and determine what each named operation does.
        val j6Match = Regex("""\bJ6\s*=\s*\{([^{}]+)\}""").find(js) ?: return null
        val j6Body = j6Match.groupValues[1]
        val spliceIdx = fArray.indexOf("splice")
        val reverseIdx = fArray.indexOf("reverse")

        val opTypes = mutableMapOf<String, String>()
        Regex("""(\w+)\s*:\s*function\s*\([^)]*\)\s*\{([^}]+)\}""").findAll(j6Body).forEach { m ->
            val name = m.groupValues[1]
            val body = m.groupValues[2]
            opTypes[name] = when {
                spliceIdx >= 0 && "F[$spliceIdx]" in body -> "splice"
                reverseIdx >= 0 && "F[$reverseIdx]" in body -> "reverse"
                "e[0]" in body -> "swap"  // swap pattern: e[0]=e[r%len]; e[r%len]=e[0]
                else -> return@forEach
            }
        }

        // Extract the ordered operation calls from the Bm function body.
        val bmFuncMatch = Regex("""\bBm\s*=\s*function\s*\([^)]+\)\s*\{[^}]+\}""").find(js) ?: return null
        val bmBody = bmFuncMatch.value
        val ops = mutableListOf<Pair<String, Int>>()

        Regex("""J6\[F\[B\^(\d+)\]\]\(X,\s*(?:B\^(\d+)|(\d+))\)""").findAll(bmBody).forEach { m ->
            val funcXor = m.groupValues[1].toInt()
            val funcIdx = B xor funcXor
            val funcName = fArray.getOrNull(funcIdx) ?: return@forEach
            val opType = opTypes[funcName] ?: return@forEach
            val arg = if (m.groupValues[2].isNotEmpty()) B xor m.groupValues[2].toInt()
                      else m.groupValues[3].toInt()
            ops += opType to arg
        }

        return ops.takeIf { it.isNotEmpty() }
    }

    /** Applies a list of (type, arg) sig operations to [sig]. */
    private fun applySigOps(sig: String, ops: List<Pair<String, Int>>): String {
        val chars = sig.toMutableList()
        for ((type, arg) in ops) {
            when (type) {
                "reverse" -> chars.reverse()
                "swap" -> {
                    val i = arg % chars.size
                    val tmp = chars[0]; chars[0] = chars[i]; chars[i] = tmp
                }
                "splice" -> repeat(arg) { if (chars.isNotEmpty()) chars.removeAt(0) }
            }
        }
        return chars.joinToString("")
    }

    /**
     * Attempts to deobfuscate the throttling (n) parameter in [url].
     * If NewPipe cannot handle the current player version, strips the n parameter instead.
     */
    private fun deobfuscateOrStrip(videoId: String, url: String): String {
        return try {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        } catch (_: Exception) {
            val builder = URLBuilder(url)
            builder.parameters.remove("n")
            builder.toString()
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()
}

private class NewPipeDownloaderImpl(proxy: Proxy?) : Downloader() {
    private val client = OkHttpClient.Builder()
        .proxy(proxy)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()
        val requestBuilder = Request.Builder()
            .method(httpMethod, dataToSend?.toRequestBody())
            .url(url)
            .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body?.string()
        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl)
    }
}
