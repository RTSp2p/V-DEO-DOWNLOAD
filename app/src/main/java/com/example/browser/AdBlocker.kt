package com.example.browser

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Utilitário de alto desempenho para bloqueio de anúncios e rastreadores (AdBlocker) via lista de hosts.
 * Inspirado nos mecanismos de filtragem de navegadores móveis focados em download (1DM, Lightning Browser).
 */
object AdBlocker {

    private const val TAG = "AdBlocker"
    private const val HOSTS_FILE = "adblock_hosts.txt"

    private val blockedHosts: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val isInitialized = AtomicBoolean(false)

    /**
     * Inicializa o conjunto de hosts bloqueados a partir do arquivo em assets.
     */
    fun init(context: Context) {
        if (isInitialized.getAndSet(true)) return

        try {
            val assetManager = context.applicationContext.assets
            assetManager.open(HOSTS_FILE).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                    for (rawLine in lines) {
                        val line = rawLine.trim()
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue
                        }
                        // Limpa prefixos de arquivo de hosts como 127.0.0.1 ou 0.0.0.0 se presentes
                        val cleanedHost = line.substringAfter("127.0.0.1")
                            .substringAfter("0.0.0.0")
                            .trim()
                            .lowercase()
                        if (cleanedHost.isNotEmpty()) {
                            blockedHosts.add(cleanedHost)
                        }
                    }
                }
            }
            Log.d(TAG, "AdBlocker inicializado com ${blockedHosts.size} hosts bloqueados.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao carregar lista de hosts do AdBlocker: ${e.message}", e)
        }
    }

    /**
     * Verifica se a URL requisitada pertence a um host ou subdomínio presente na lista de bloqueio.
     */
    fun isAd(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        val host = try {
            val uri = Uri.parse(url)
            uri.host?.lowercase() ?: return false
        } catch (_: Exception) {
            return false
        }

        return isHostBlocked(host)
    }

    /**
     * Verifica se o host exato ou qualquer um de seus domínios pai está contido na lista de bloqueio O(1).
     * Exemplo: "ads.serving.doubleclick.net" testa sucessivamente:
     * - ads.serving.doubleclick.net
     * - serving.doubleclick.net
     * - doubleclick.net -> Encontra e bloqueia!
     */
    fun isHostBlocked(rawHost: String): Boolean {
        var cleanHost = rawHost.lowercase().trim()
        if (cleanHost.startsWith("www.")) {
            cleanHost = cleanHost.substring(4)
        }

        var current = cleanHost
        while (current.isNotEmpty()) {
            if (blockedHosts.contains(current)) {
                return true
            }
            val nextDot = current.indexOf('.')
            if (nextDot != -1 && nextDot < current.length - 1) {
                current = current.substring(nextDot + 1)
            } else {
                break
            }
        }
        return false
    }

    /**
     * Resposta vazia HTTP para cancelar o carregamento de scripts, iframes e requisições de anúncios
     * de forma limpa sem quebrar o layout da página.
     */
    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * Permite injetar regras adicionais dinamicamente ou em testes unitários.
     */
    fun addHost(host: String) {
        val clean = host.trim().lowercase().removePrefix("www.")
        if (clean.isNotEmpty()) {
            blockedHosts.add(clean)
        }
    }

    fun getBlockedHostsCount(): Int = blockedHosts.size

    fun reset() {
        blockedHosts.clear()
        isInitialized.set(false)
    }
}
