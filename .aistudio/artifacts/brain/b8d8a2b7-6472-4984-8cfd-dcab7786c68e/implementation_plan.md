# 1DM Video Hunter — Arquitetura Avançada de Streams Adaptativas (HLS/DASH), Motor Híbrido e Multiplexação

Evolução completa da arquitetura de captura, download e reprodução do **1DM Video Hunter** para suportar streams adaptativas modernas (HLS `.m3u8` e DASH `.mpd`), contornar bloqueios de sessão (headers anti-403), detectar proteção DRM preventivamente, selecionar resoluções ou extrair apenas áudio, baixar segmentos criptografados (AES-128) ou com pausa/retomada (Range Requests) em segundo plano via Foreground Service, multiplexar trilhas em `.mp4` e pré-visualizar streams online no player interno.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> As decisões abaixo refletem suas escolhas confirmadas e as diretrizes técnicas para máxima estabilidade e compatibilidade no Android:

- **Decisão Confirmada 1 — Seleção de Qualidade em Lote vs. Individual**:
  - **Download Individual**: Ao tocar no botão de download de uma stream adaptativa (`HLS` ou `DASH`), o app faz o parse do manifesto e abre o modal **Seletor de Qualidade & Extração de Áudio**, permitindo escolher a resolução desejada (ex.: `1080p`, `720p`, `480p`, `360p`) ou a opção **"Apenas Áudio (.m4a)"**.
  - **Download em Lote ("Baixar Selecionados")**: Para não interromper o fluxo com múltiplos diálogos, o motor seleciona automaticamente a variante de **maior resolução disponível** para cada stream adaptativa do lote.
- **Decisão Confirmada 2 — Pré-visualização Online no Reprodutor Interno**:
  - Os cards de **Vídeos Detectados** (no navegador) e os cards da **Biblioteca** contarão com um botão **Play (Pré-visualizar)** que abre o reprodutor nativo (`InternalVideoPlayer`) configurado com `HlsMediaSource`, `DashMediaSource` e `ProgressiveMediaSource`, injetando automaticamente os headers capturados (`Cookie`, `User-Agent`, `Referer`) para reproduzir a stream online mesmo antes de baixá-la.
- **Escolha Técnica — Multiplexação e Remuxing Nativo com AndroidX Media3 (`media3-muxer` / `media3-transformer` / `MediaMuxer`)**:
  - Como o artefato Maven `com.arthenica:ffmpeg-kit-full` foi descontinuado/removido do Maven Central oficial, utilizaremos a suíte oficial **AndroidX Media3 (`media3-exoplayer-hls`, `media3-exoplayer-dash`, `media3-transformer`, `media3-muxer`, `media3-datasource-okhttp`)** combinada com pipeline nativo de demuxing/muxing (`MediaExtractor` + `MediaMuxer` / `Mp4Muxer`) e `Coil` para renderização de thumbnails.

---

## 1. Overview & Core Concept

- **O que o sistema faz**: Captura tanto mídias progressivas diretas (`.mp4`, `.webm`, `.mkv`) quanto manifestos de streaming adaptativo (`HLS .m3u8` e `DASH .mpd`) diretamente da navegação WebView, preservando credenciais de sessão (`Cookies`, `User-Agent`, `Referer`), metadados visuais (`título` e `thumbnail/poster`) e verificando criptografia DRM antes do download.
- **Público e Caso de Uso**: Usuários que desejam capturar vídeos de plataformas modernas que utilizam streaming segmentado, autenticação por sessão temporária, trilhas de áudio/vídeo separadas e criptografia de segmentos AES-128.
- **Valor Principal**: Elimina falhas `HTTP 403 Forbidden`, evita poluição da lista com centenas de fragmentos `.ts`/`.m4s` isolados, informa claramente quando um vídeo possui DRM incompatível (Widevine/PlayReady/SAMPLE-AES-CTR) e entrega um arquivo `.mp4` (ou `.m4a` para áudio) unificado e indexado diretamente na Galeria do Android (`MediaStore`).

---

## 2. User Experience & Visual Design

- **Fluxo Passo a Passo do Usuário**:
  1. **Detecção Inteligente no Navegador**: Enquanto o usuário navega, o sniffer intercepta vídeos diretos e manifestos mestres HLS/DASH (ignorando fragmentos `.ts`, `.m4s`, `.mp4-init`). Se um manifesto contiver DRM restritivo, um alerta imediato é exibido via Snackbar/Banner e o item recebe a tag visual `DRM PROTEGIDO`.
  2. **Lista de Vídeos Detectados Enriquecida**: Cada card no BottomSheet de Vídeos Detectados exibe a **miniatura (poster/thumbnail)**, o **título real da página ou vídeo**, badges indicando o tipo de fluxo (`DIRETO`, `HLS`, `DASH`, `DRM`), botão **Play (Pré-visualizar Online)** e botão **Baixar**.
  3. **Seletor de Qualidade e Extração de Áudio**: Ao clicar em baixar uma stream `HLS` ou `DASH` individualmente, abre-se um BottomSheet listando todas as variantes parseadas do manifesto (resolução, bitrate estimado, codec) mais a opção dedicada **"Apenas Áudio (.m4a)"**.
  4. **Acompanhamento em Segundo Plano e na Home**: O download inicia em um **Foreground Service** com notificação persistente exibindo progresso, velocidade em tempo real (`MB/s` ou `KB/s`), porcentagem e ação **Cancelar**. Na tela **Home**, os cards permitem **Pausar**, **Retomar** (via HTTP Range Requests ou retomada de segmento) e **Cancelar**.
  5. **Conclusão, Multiplexação e Galeria**: Ao finalizar o download dos segmentos/trilhas, o status exibe a etapa de multiplexação (`Remuxing MP4`), salva o arquivo final no `MediaStore.Video.Media` (ou pasta SAF configurada) e limpa o cache temporário.

- **Identidade Visual e Tema**:
  - **Estética**: Mantém a identidade **Cyber-Utilitarian Neon / Material Design 3** com suporte adaptativo completo aos modos Claro, Escuro (AMOLED) e Sistema.
  - **Badges Semânticos**:
    - `HLS` / `DASH`: Destaque em ciano/roxo neon (`primary` / `secondary`).
    - `DRM`: Destaque de alerta em vermelho/laranja (`errorContainer` / `onErrorContainer`) com ícone de cadeado e bloqueio preventivo do botão de download.
  - **Feedback Interativo**: Indicadores de velocidade de rede em tempo real, barra de progresso linear determinada para fragmentos HLS/DASH e indeterminada durante a fase rápida de multiplexação final.

---

## 3. Key Product Decisions & Trade-Offs

- **Decisão 1: Filtragem de Manifestos vs. Fragmentos no Sniffer**
  - *Abordagem Escolhida*: Interceptar `.m3u8`, `.mpd` e MIME types `application/x-mpegURL`, `application/vnd.apple.mpegurl` e `application/dash+xml`, bloqueando explicitamente o registro individual de `.ts`, `.m4s` e `init.mp4`.
  - *Por quê*: Players modernos disparam dezenas de requisições de segmentos por minuto. Filtrar fragmentos na origem garante que apenas a playlist principal apareça para o usuário escolher a qualidade.

- **Decisão 2: Motor Híbrido em Foreground Service Próprio com OkHttp + Coroutines**
  - *Abordagem Escolhida*: Substituir o uso exclusivo do `DownloadManager` do sistema por um motor híbrido gerenciado por `DownloadService` (Foreground Service do tipo `dataSync`), usando `OkHttpClient` com repasse de headers (`Cookie`, `User-Agent`, `Referer`).
  - *Por quê*: O `DownloadManager` nativo do Android não baixa nem concatena playlists `.m3u8`/`.mpd`, não descriptografa chaves `AES-128` e não faz multiplexação de vídeo+áudio separados. Com `OkHttp` + `Coroutines`, suportamos tanto **Range Requests** (`Range: bytes=N-`) com Pausa/Retomada para links diretos quanto download concorrente/sequencial de segmentos HLS/DASH.

- **Decisão 3: Detecção Preventiva de DRM e Descriptografia AES-128 Padrão**
  - *Abordagem Escolhida*: Durante a inspeção do manifesto, diferenciar criptografia de transporte suportada (`#EXT-X-KEY:METHOD=AES-128` com URI de chave acessível) de esquemas de DRM de hardware/licença (`SAMPLE-AES`, `SAMPLE-AES-CTR`, `Widevine`, `PlayReady`, `cenc`).
  - *Por quê*: Streams com `AES-128` padrão podem ser legitimamente descriptografadas via `javax.crypto.Cipher` (`AES/CBC/PKCS5Padding`) usando a chave de 16 bytes e o IV do manifesto, enquanto conteúdos com DRM Widevine/PlayReady devem ser sinalizados imediatamente ao usuário com aviso claro.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                        CAMADA DE APRESENTAÇÃO (UI)                         │
│                                                                            │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐  │
│  │    BrowserScreen     │  │      HomeScreen      │  │ DownloadsScreen  │  │
│  │  • WebView + Sniffer │  │  • Abas de Status    │  │   (Biblioteca)   │  │
│  │  • Vídeos Detectados │  │  • Progresso & Taxa  │  │  • Busca/Filtro  │  │
│  │  • Seletor Qualidade │  │  • Pausar / Retomar  │  │  • Player Nativo │  │
│  └──────────┬───────────┘  └──────────┬───────────┘  └────────┬─────────┘  │
│             │                         │                       │            │
│             └─────────────────────────┼───────────────────────┘            │
│                                       ▼                                    │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │              InternalVideoPlayer (Media3 ExoPlayer)                  │  │
│  │   • OkHttpDataSource.Factory (Cookies + User-Agent + Referer)        │  │
│  │   • HlsMediaSource / DashMediaSource / ProgressiveMediaSource        │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────┬────────────────────────────────────┘
                                        │ StateFlow / Eventos MVVM
                                        ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                     CAMADA DE DOMÍNIO & DADOS (MVVM)                       │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                         VideoRepository                              │  │
│  │   • Coordena Sniffer, Manifesto Parser, Room DB e DownloadService    │  │
│  └──────────────┬───────────────────────────────────────┬───────────────┘  │
│                 │                                       │                  │
│                 ▼                                       ▼                  │
│  ┌──────────────────────────────┐       ┌───────────────────────────────┐  │
│  │   StreamManifestParser       │       │     Room Database (v3)        │  │
│  │ • Parse HLS (.m3u8) Variants │       │ • CapturedVideoEntity         │  │
│  │ • Parse DASH (.mpd) Adapts   │       │   (isStream, streamType,      │  │
│  │ • Detecta DRM (Widevine/AES) │       │    headers, thumbnailUrl,     │  │
│  │ • Extrai Trilhas Áudio/Vídeo │       │    title, isDrmProtected,     │  │
│  └──────────────────────────────┘       │    downloadSpeed, progress)   │  │
│                                         └───────────────────────────────┘  │
└───────────────────────────────────────┬────────────────────────────────────┘
                                        │ Intent / Binders / Coroutines
                                        ▼
┌────────────────────────────────────────────────────────────────────────────┐
│               MOTOR DE DOWNLOAD HÍBRIDO (SEGUNDO PLANO)                    │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                  DownloadService (Foreground Service)                │  │
│  │   • Notificação Persistente (Progresso, Velocidade B/s, Cancelar)    │  │
│  ├──────────────────────────────────┬───────────────────────────────────┤  │
│  │   Fluxo Direto (MP4/WebM/MKV)    │   Fluxo Adaptativo (HLS / DASH)   │  │
│  │ • OkHttp Range Requests          │ • Download Sequencial/Paralelo    │  │
│  │   (Header: Range: bytes=start-)  │ • Descriptografia AES-128 (CBC)   │  │
│  │ • Suporte a Pause / Resume       │ • Download Vídeo + Áudio Separado │  │
│  └──────────────────┬───────────────┴───────────────────┬───────────────┘  │
│                     └─────────────────┬─────────────────┘                  │
│                                       ▼                                    │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                MediaMuxerEngine & MediaStoreExporter                 │  │
│  │   • Concatenação e Remuxing de Chunks / Muxing Vídeo + Áudio (.mp4)  │  │
│  │   • Exportação Pública via MediaStore.Video.Media (Movies/Download)  │  │
│  │   • Limpeza Segura de Chunks Temporários no Cache                    │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State (`CapturedVideoEntity` & `StreamType`)

- **`StreamType` (Enum)**: `DIRECT`, `HLS`, `DASH`.
- **Campos adicionados em `CapturedVideoEntity` (Room Database v3 com `TypeConverters`)**:
  - `title: String`: Título extraído do elemento `<video>` (`title`/`aria-label`) ou `document.title`.
  - `thumbnailUrl: String?`: URL do atributo `poster` da tag `<video>` ou meta `og:image`.
  - `isStream: Boolean`: `true` para streams `HLS`/`DASH`, `false` para arquivos diretos.
  - `streamType: StreamType`: Tipo da mídia (`DIRECT`, `HLS`, `DASH`).
  - `headers: Map<String, String>`: Mapa persistido via JSON contendo `Cookie`, `User-Agent`, `Referer` e `Origin`.
  - `isDrmProtected: Boolean`: Indica se o manifesto contém DRM incompatível (Widevine, PlayReady, SAMPLE-AES-CTR).
  - `selectedQualityLabel: String?`: Resolução ou modo selecionado (ex.: `"1080p"`, `"720p"`, `"Apenas Áudio"`).
  - `downloadProgress: Int`: Porcentagem de `0` a `100` persistida reativamente.
  - `downloadedBytes: Long` e `downloadSpeedBytesPerSec: Long`: Métricas em tempo real para exibição nos cards da Home e na notificação.
  - Novos status suportados: `DETECTED`, `DOWNLOADING`, `PAUSED`, `MUXING`, `COMPLETED`, `FAILED`, `DRM_BLOCKED`.

### Interactive Component & State Mapping

1. **Sniffer & JS Bridge (`VideoSnifferWebViewClient` + `VideoSnifferJsBridge`)**:
   - Captura links `.m3u8` e `.mpd` via DOM, `fetch`/`XHR` hooks e `shouldInterceptRequest`, além de inspecionar o `Content-Type` (`application/x-mpegurl`, `application/vnd.apple.mpegurl`, `application/dash+xml`).
   - Filtra e ignora segmentos individuais (`.ts`, `.m4s`, `.mp4-init`, `frag_`).
   - Extrai `title`, `poster` (thumbnail) e cabeçalhos (`Cookie`, `User-Agent`, `Referer`).
   - Faz verificação rápida do manifesto para detectar tags DRM (`SAMPLE-AES`, `Widevine`, `PlayReady`, `urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed`) e emite alerta imediato na UI caso detectado.
2. **Parser de Manifesto e Seletor de Qualidade (`StreamManifestParser` + `StreamQualitySheet`)**:
   - Analisa Master Playlists HLS (`#EXT-X-STREAM-INF`, `#EXT-X-MEDIA:TYPE=AUDIO`) e manifestos MPD DASH (`<AdaptationSet>`, `<Representation>`).
   - Exibe ao usuário a lista de resoluções disponíveis e a opção **"Apenas Áudio (.m4a)"**.
3. **Foreground Service Híbrido (`DownloadService`)**:
   - Mantém notificação ativa com progresso, velocidade em tempo real e botão **Cancelar** via `PendingIntent`.
   - Para links `DIRECT`: Suporta **Pausar e Retomar** usando cabeçalho `Range: bytes=<downloaded>-` e gravação incremental (`RandomAccessFile` / `FileOutputStream(append = true)`).
   - Para `HLS` / `DASH`: Resolve a Media Playlist da variante escolhida, baixa chave AES-128 se `#EXT-X-KEY:METHOD=AES-128` estiver presente, descriptografa cada segmento em cache, baixa a trilha de áudio em paralelo se separada, realiza o remuxing/concatenação para `.mp4` (ou `.m4a`), publica no `MediaStore.Video.Media` (e pasta SAF se configurada) e remove todos os arquivos temporários.
4. **Reprodutor Interno Online e Offline (`InternalVideoPlayer`)**:
   - Usa `ExoPlayer` com `DefaultHttpDataSource.Factory` configurado com os `headers` capturados (`Cookie`, `User-Agent`, `Referer`).
   - Seleciona dinamicamente `HlsMediaSource`, `DashMediaSource` ou `ProgressiveMediaSource` para permitir tanto assistir streams online antes do download quanto reproduzir vídeos já salvos na Biblioteca.
