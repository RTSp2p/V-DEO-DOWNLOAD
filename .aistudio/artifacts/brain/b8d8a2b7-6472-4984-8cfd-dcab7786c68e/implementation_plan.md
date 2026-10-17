# 1DM Video Hunter & Download Manager

Gerenciador de downloads de alto desempenho e navegador web integrado para Android inspirado no 1DM, com captura inteligente de fluxos de vídeo completos (MP4, WebM, MKV), controle de duplicidade no Room, download em lote, biblioteca de concluídos com busca e 6 critérios de ordenação, e player de vídeo Media3 ExoPlayer integrado.

## User Review & Critical Decisions

> [!IMPORTANT]
> As preferências do usuário confirmadas na Fase 1 foram incorporadas à especificação:
> - **Estilo Visual:** Dark AMOLED com realces em Ciano Elétrico (`#00E5FF`) e Roxo Neon (`#9D4EDD`), criando uma atmosfera profissional, moderna e de alto contraste.
> - **Página Inicial do Navegador:** Start page limpa e rápida com barra de pesquisa omnibox e atalhos rápidos/favoritos (sites de teste de vídeo e portais comuns).
> - **Avisos de Conclusão de Download:** Notificação nativa do sistema Android com ação de reproduzir + Snackbar no app com botão "Assistir Agora".

---

## 1. Overview & Core Concept

- **O que faz:** Um navegador web customizado com sniffing inteligente de requisições de mídia em tempo real. Identifica arquivos de vídeo completos (MP4, WebM, MKV) descartando fragmentos de streaming (.m3u8, .ts, .mpd, .m4s), previne duplicatas no Room Database via `@Index(unique = true)` e `OnConflictStrategy.IGNORE`, permite baixar mídias individuais ou em lote com seleção múltipla via `DownloadManager`, organiza os downloads concluídos em uma biblioteca rica com busca instantânea e 6 modos de ordenação, e reproduz os vídeos salvos em um Player Media3 (ExoPlayer) integrado.
- **Público-Alvo:** Usuários que desejam capturar, arquivar e reproduzir conteúdos em vídeo diretamente em seus dispositivos Android com total controle de lote e organização.
- **Valor Principal:** Zero complicação com fragmentos de streaming inúteis, downloads resilientes em segundo plano via serviço do sistema operacional, e reprodutor interno fluido sem dependência de apps externos.

---

## 2. User Experience & Visual Design

### Fluxos Principais
1. **Navegação & Sniffing:**
   - O usuário abre o app na aba **Navegador**, vê a página inicial limpa com sugestões rápidas ou digita uma URL/termo de busca.
   - O WebView carrega as páginas. O `VideoSnifferWebViewClient` analisa as requisições em tempo real via `shouldInterceptRequest`.
   - Ao detectar vídeos válidos (MP4, WebM, MKV), o badge numérico no Floating Action Button (FAB) animado é incrementado com o total de vídeos detectados.
2. **Download em Lote (BottomSheet):**
   - Ao clicar no FAB, um `ModalBottomSheet` sobe suavemente.
   - Apresenta:
     - Barra de controle de lote com checkbox "Selecionar Todos", contador dinâmico ("X de Y selecionados") e botão "Baixar Selecionados".
     - Lista de cards com nome do arquivo, formato (.mp4, etc.), tamanho aproximado e botão de download individual direto.
   - Ao disparar o download, as tarefas são submetidas ao `DownloadManager` e o status transiciona para `DOWNLOADING`.
3. **Aba Biblioteca (Baixados):**
   - O usuário troca para a aba **Baixados** via Bottom Navigation Bar.
   - Barra superior com `SearchBar` reativo para busca em tempo real por nome e menu suspenso de ordenação com as 6 opções:
     1. Mais recentes primeiro (padrão)
     2. Mais antigos primeiro
     3. Maior tamanho
     4. Menor tamanho
     5. Nome (A-Z)
     6. Nome (Z-A)
   - Cada card exibe: miniatura temática, nome, formato, tamanho formatado (MB/GB) e data.
   - Ações: botão "Assistir" que abre o player e menu de opções com "Renomear" (AlertDialog) e "Excluir" (remove registro e arquivo físico do disco).
4. **Player de Vídeo Interno (ExoPlayer):**
   - Tela cheia imersiva com `PlayerView` (Media3 ExoPlayer), controle de play/pause, seekbar/progresso, tempo decorrido/total e botão de retorno.

### Identidade Visual & Tema (Dark AMOLED + Cyan & Purple)
- **Fundo Primário:** `#0A0D14` / `#05070A` (AMOLED Ultra Deep Black para economia de bateria e contraste extremo).
- **Superfícies de Cartões:** `#121824` com bordas sutis em `#1F293D`.
- **Acento Primário:** `#00E5FF` (Ciano elétrico vibrante para botões principais, seleções ativas e badges).
- **Acento Secundário:** `#9D4EDD` / `#7928CA` (Roxo neon para gradientes, indicadores de status e detalhes visuais).
- **Tipografia:** Material 3 Typography com pesos balanceados (Bold para contadores e títulos de destaque, Medium para nomes de arquivos).
- **Feedback & Motion:** Animação de entrada do bottom sheet, badge com pulsação suave ao capturar novos links e feedback tátil/ripple em todos os botões.

---

## 3. Key Product Decisions & Trade-Offs

- **Room Database com Índice Único (`videoUrl`):**
  - *Decisão:* Adicionar `@Index(value = ["videoUrl"], unique = true)` na tabela `captured_videos` e usar `insertVideo(video): Long` com `OnConflictStrategy.IGNORE`.
  - *Motivo:* Em páginas web dinâmicas, o mesmo recurso pode ser requisitado dezenas de vezes por players web e scripts. O índice único no Room garante que apenas a primeira requisição seja salva no banco (retorno do insert diferente de -1L), eliminando duplicatas na raiz da arquitetura.
- **Filtro Estrito de Extensão e MIME Type:**
  - *Decisão:* Capturar apenas URLs com terminação `.mp4`, `.webm`, `.mkv` ou Content-Type `video/mp4`, `video/webm`, `video/x-matroska`.
  - *Motivo:* Ignorar explicitamente manifestos HLS/DASH (`.m3u8`, `.ts`, `.mpd`, `.m4s`) porque estes exigem renderização de listas segmentadas e geram falsos downloads corrompidos de fragmentos de 2 segundos.
- **DownloadManager Nativo + BroadcastReceiver:**
  - *Decisão:* Utilizar `android.app.DownloadManager` apontando para `Environment.DIRECTORY_DOWNLOADS` com `BroadcastReceiver` ouvindo `ACTION_DOWNLOAD_COMPLETE`.
  - *Motivo:* O serviço nativo gerencia reconexão, estabilidade de rede em segundo plano e economia de bateria sem que o app precise manter serviços em primeiro plano contínuos. O receiver captura o `localUri` final e atualiza o status do Room para `COMPLETED`.
- **Media3 ExoPlayer:**
  - *Decisão:* Integrar `androidx.media3:media3-exoplayer` e `androidx.media3:media3-ui`.
  - *Motivo:* É a biblioteca oficial e recomendada pelo Android para reprodução de mídia de alta performance, garantindo reprodução impecável de MP4, WebM e MKV locais.

---

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                          PRESENTATION LAYER                            │
│  ┌─────────────────────────┐             ┌──────────────────────────┐  │
│  │     BrowserScreen       │             │     DownloadsScreen      │  │
│  │ ┌─────────────────────┐ │             │ ┌──────────────────────┐ │  │
│  │ │ VideoSnifferWebView │ │             │ │ SearchBar & SortMenu │ │  │
│  │ └─────────────────────┘ │             │ └──────────────────────┘ │  │
│  │ ┌─────────────────────┐ │             │ ┌──────────────────────┐ │  │
│  │ │ BatchDownloadSheet  │ │             │ │ CompletedVideoList   │ │  │
│  │ └─────────────────────┘ │             │ └──────────────────────┘ │  │
│  │ ┌─────────────────────┐ │             │ ┌──────────────────────┐ │  │
│  │ │ FloatingSnifferFAB  │ │             │ │ VideoPlayerDialog/Scr│ │  │
│  │ └─────────────────────┘ │             │ └──────────────────────┘ │  │
│  └───────────┬─────────────┘             └────────────┬─────────────┘  │
│              │                                        │                │
│              ▼                                        ▼                │
│  ┌─────────────────────────┐             ┌──────────────────────────┐  │
│  │    BrowserViewModel     │             │    DownloadsViewModel    │  │
│  │  - detectedVideos (Flow)│             │  - searchQuery (State)   │  │
│  │  - selectedVideoIds     │             │  - currentSort (State)   │  │
│  │  - startBatchDownload() │             │  - completedVideos(Flow) │  │
│  └───────────┬─────────────┘             └────────────┬─────────────┘  │
└──────────────┼────────────────────────────────────────┼────────────────┘
               ▼                                        ▼
┌────────────────────────────────────────────────────────────────────────┐
│                             DATA LAYER                                 │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                     VideoRepository                              │  │
│  │  - observeDetectedVideos(): Flow<List<CapturedVideoEntity>>      │  │
│  │  - observeCompletedVideos(): Flow<List<CapturedVideoEntity>>     │  │
│  │  - insertCapturedVideo(video): Boolean                           │  │
│  │  - updateStatusAndDownloadId(id, status, downloadId)             │  │
│  │  - updateDownloadCompleted(downloadId, localUri, size)           │  │
│  │  - renameVideo(id, newName, localUri)                            │  │
│  │  - deleteVideoAndFile(id, localUri)                              │  │
│  └───────────────────┬───────────────────────────────┬──────────────┘  │
│                      │                               │                 │
│                      ▼                               ▼                 │
│         ┌─────────────────────────┐      ┌──────────────────────────┐  │
│         │   Room VideoDao & DB    │      │  Android DownloadManager │  │
│         │ - CapturedVideoEntity   │      │  + DownloadCompletedRcv  │  │
│         └─────────────────────────┘      └──────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

### Entidade Room (`CapturedVideoEntity`)
```kotlin
@Entity(
    tableName = "captured_videos",
    indices = [Index(value = ["videoUrl"], unique = true)]
)
data class CapturedVideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoUrl: String,
    val fileName: String,
    val fileType: String,
    val fileSizeBytes: Long = 0L,
    val localUri: String? = null,
    val downloadStatus: String, // "DETECTED", "DOWNLOADING", "COMPLETED"
    val downloadId: Long = -1L,
    val capturedAt: Long = System.currentTimeMillis()
)
```

### Critérios de Ordenação na Biblioteca
```kotlin
enum class SortOption(val title: String) {
    RECENT_FIRST("Mais recentes"),
    OLDEST_FIRST("Mais antigos"),
    SIZE_DESC("Maior tamanho"),
    SIZE_ASC("Menor tamanho"),
    NAME_ASC("Nome (A-Z)"),
    NAME_DESC("Nome (Z-A)")
}
```

---

## 5. Implementation Roadmap (Phases)

1. **Configuração de Dependências & Manifest:**
   - Adicionar dependências do Media3 ExoPlayer (`media3-exoplayer`, `media3-ui`) no catálogo e no `build.gradle.kts`.
   - Declarar permissões (`INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`) e registrar o `DownloadCompletedReceiver` no `AndroidManifest.xml`.
   - Atualizar `metadata.json` e `strings.xml` com o nome do aplicativo **"1DM Video Hunter"**.
2. **Camada de Dados & Persistência (Room):**
   - Criar `CapturedVideoEntity`, `VideoDao`, `AppDatabase` e `VideoRepository`.
   - Implementar método com conflito IGNORE para anti-duplicidade e consultas com `Flow`.
3. **Serviço de Download & BroadcastReceiver:**
   - Implementar helper de `DownloadManager` para download individual e em lote.
   - Criar `DownloadCompletedReceiver` que atualiza o status, localUri e tamanho real após a finalização do arquivo.
4. **Navegador com Video Sniffer:**
   - Implementar `VideoSnifferWebViewClient` com regex e MIME type check rigoroso (apenas MP4, WebM, MKV, descartando m3u8/ts/mpd/m4s).
   - Desenvolver `BrowserScreen` com barra de busca/URL, WebView, start page rápida e Floating Action Button com badge numérico.
5. **ModalBottomSheet de Lote:**
   - Desenvolver BottomSheet com seleção individual e "Selecionar Todos", exibição de metadados dos vídeos detectados e ação de disparo do lote.
6. **Aba Biblioteca (DownloadsScreen):**
   - Implementar barra de busca em tempo real com `SearchBar`.
   - Menu de ordenação com as 6 opções solicitadas.
   - Lista fluida em `LazyColumn` com cartões informativos, botão "Assistir", diálogo de renomear e remoção física do arquivo com confirmação.
7. **Player Interno (Media3 ExoPlayer):**
   - Desenvolver tela/diálogo de reprodução com `PlayerView`, controles M3 integrados, rotação e tratamento de ciclo de vida do player.
8. **Compilação e Verificação:**
   - Executar `compile_applet` e garantir zero advertências ou erros de compilação.
