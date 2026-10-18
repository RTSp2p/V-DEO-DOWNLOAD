# 1DM Video Hunter - Expansão de Telas, Navegação e Correções Críticas

Plano detalhado para a reestruturação da navegação principal em 3 destinos ("Home", "Navegador" e "Biblioteca"), adição da tela Home com abas de status de downloads ("TODOS", "BAIXANDO", "COMPLETOS", "ERROS"), persistência de histórico de navegação e sessão da WebView, menu do navegador com chave de captura e histórico em diálogo modal, novas ações no BottomSheet de vídeos detectados e correções críticas no disparo de notificações e no fechamento da janela de mídia.

## User Review & Critical Decisions

> [!IMPORTANT]
> Decisões confirmadas pelo usuário:
> - **Visualização do Histórico:** Diálogo modal rápido e elegante renderizado diretamente sobre a tela do Navegador, com os últimos 30 sites visitados, ação de toque para abrir e limpeza rápida.
> - **Listagem na Tela Home:** Cards detalhados com informações de status, taxa/progresso, tamanho, botões de cancelamento para ativos e botão de retry para downloads com erro.
> - **Correção de Notificações:** Notificações estritamente vinculadas ao `downloadId` individual no instante da mudança de estado, eliminando qualquer reemissão de notificações para itens concluídos no passado.
> - **Comportamento do Botão "X" no BottomSheet:** O botão apenas fecha/minimiza o BottomSheet (`sheetState.hide()`), mantendo intactos todos os vídeos detectados no banco de dados Room e no badge numérico do FAB.

---

## 1. Overview & Core Concept

- **O que muda:**
  1. **Navegação em 3 Destinos:**
     - **Home:** Painel central de controle de downloads com abas horizontais `PrimaryTabRow` filtrando por:
       - **TODOS:** Todos os vídeos gerenciados.
       - **BAIXANDO:** Downloads em andamento (`STATUS_DOWNLOADING`), com indicador de progresso e botão de cancelar.
       - **COMPLETOS:** Mídias salvas com sucesso (`STATUS_COMPLETED`), com botão rápido para reproduzir.
       - **ERROS:** Downloads com falha (`STATUS_FAILED`), com motivo e botão de tentar novamente (retry).
     - **Navegador:** Ambiente de navegação integrado com preservação de sessão (`saveState`/`restoreState` e última URL persistida), barra superior compacta (Voltar, Avançar, Recarregar e URL/Busca em linha), menu de 3 pontos com toggle de captura ("Começar Captura"), atalho "Voltar para Home" e "Histórico".
     - **Biblioteca:** Antiga tela de concluídos, mantendo pesquisa instantânea, 6 opções de ordenação e reprodução com player Media3 ExoPlayer.
  2. **Persistência de Histórico de Navegação (`BrowsingHistoryEntity`):**
     - Tabela Room dedicada gravando `url`, `title`, `visitedAt` e favicon/ícone, limitada aos últimos 30 registros mais recentes.
  3. **Ações no BottomSheet de Vídeos Detectados:**
     - Botão / menu no canto inferior esquerdo com:
       - **Limpar Todos:** Diálogo de confirmação para remover vídeos detectados pendentes do Room.
       - **Cancelar Download:** Interrompe tarefas ativas chamando `downloadManager.remove(id)` e marcando o status como cancelado/falho.
  4. **Correção do Botão "X" do BottomSheet:**
     - Apenas fecha o modal, sem apagar nenhum registro do banco nem zerar o badge.
  5. **Correção de Notificações Duplicadas:**
     - Eliminação de alertas para itens antigos; o `BroadcastReceiver` notifica exclusivamente o `downloadId` que disparou o evento.

---

## 2. User Experience & Visual Design

### Fluxo da Tela Home
- No topo da tela Home, um `PrimaryTabRow` estilizado com as 4 abas:
  - **TODOS** (badge com total geral)
  - **BAIXANDO** (badge ciano com contagem de downloads ativos)
  - **COMPLETOS** (badge roxo com vídeos concluídos)
  - **ERROS** (badge vermelho com falhas)
- Cada card de download na Home apresenta:
  - Ícone de mídia com cor temática dependendo do status.
  - Nome do arquivo e tamanho em bytes formatado.
  - Barra de progresso para downloads ativos.
  - Ações contextuais: Botão "Cancelar" para itens ativos, "Assistir" para completos e "Tentar Novamente" (Retry) para itens com falha.

### Fluxo da Tela Navegador
- **Barra Superior Integrada em Linha única:**
  - `[ < ]` (Voltar no histórico da WebView)
  - `[ > ]` (Avançar no histórico)
  - `[ ⟳ ]` (Recarregar página)
  - Campo de texto (Omnibox) estilizado com cantos arredondados, ocupando o espaço flexível.
  - `[ ⋮ ]` (Menu de opções com DropdownMenu).
- **Menu de 3 Pontos:**
  - `[✓] Começar Captura`: Checkbox que liga/desliga o sniffer em tempo real. Quando desligado, nenhuma requisição nem tag dispara novas inserções.
  - `🏠 Voltar para Home`: Alterna a aba ativa para Home sem recarregar nem destruir a WebView.
  - `🕒 Histórico`: Abre o diálogo modal do histórico.
- **Diálogo Modal de Histórico:**
  - Lista com os 30 registros mais recentes com título da página, URL e data/hora.
  - Toque no item carrega a URL diretamente na WebView e fecha o diálogo.
  - Botão "Limpar Histórico" no rodapé do diálogo.

### Ações na Janela de Vídeos Detectados (BottomSheet)
- No canto inferior do BottomSheet, uma barra de ações complementares ou menu de controle contendo:
  - **Botão "Limpar Todos"**: Dispara AlertDialog ("Deseja remover todos os vídeos detectados da lista?"). Se confirmado, limpa itens com status `DETECTED` do Room.
  - **Botão "Cancelar Download"**: Cancela todas as tarefas com status `DOWNLOADING` chamando `downloadManager.remove()` e atualizando o banco.
- **Botão "X" de fechar**: Executa unicamente o recolhimento visual do BottomSheet, sem remover nenhum registro.

---

## 3. Key Product Decisions & Trade-Offs

- **Status `FAILED` / Cancelado no Room:**
  - *Decisão:* Adicionar suporte explícito ao status `STATUS_FAILED = "FAILED"` na entidade `CapturedVideoEntity`.
  - *Motivo:* Permite à tela Home listar falhas na aba "ERROS", possibilitando que o usuário veja o motivo da interrupção e tente reiniciar o download com um clique.
- **Histórico Persistido no Room (`BrowsingHistoryEntity`):**
  - *Decisão:* Criar entidade com limite de 30 registros gerenciado via DAO (`DELETE FROM browsing_history WHERE id NOT IN (SELECT id FROM browsing_history ORDER BY visitedAt DESC LIMIT 30)`).
  - *Motivo:* Extremamente rápido, reativo via `Flow`, seguro e não depende de storage volátil.
- **Persistência de Sessão da WebView:**
  - *Decisão:* Armazenar a última URL válida via `DataStore` ou preferência local, e manter o `Bundle` de navegação com `saveState`/`restoreState` na instância ativa do navegador.
  - *Motivo:* Permite que o usuário feche e reabra o aplicativo continuando exatamente da página onde parou, preservando o histórico de navegação.
- **Notificações Pontuais:**
  - *Decisão:* Filtrar emissões de notificação e eventos na UI usando estritamente o `downloadId` recebido no Intent de `ACTION_DOWNLOAD_COMPLETE`.
  - *Motivo:* Evita reprocessar listas ou exibir múltiplos alertas sonoros e notificações de itens passados ao iniciar um novo download.

---

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                          PRESENTATION LAYER                            │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────┐  │
│  │    HomeScreen    │  │  BrowserScreen   │  │   DownloadsScreen    │  │
│  │ (Tabs: TODOS,    │  │ (WebView, Omnibox│  │ ("Biblioteca": busca,│  │
│  │  BAIXANDO,       │  │  Session, Menu,  │  │  ordenação, player)  │  │
│  │  COMPLETOS,      │  │  History Dialog, │  │                      │  │
│  │  ERROS)          │  │  Sniffer Toggle) │  │                      │  │
│  └────────┬─────────┘  └────────┬─────────┘  └──────────┬───────────┘  │
│           │                     │                       │              │
│           ▼                     ▼                       ▼              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────┐  │
│  │  HomeViewModel   │  │ BrowserViewModel │  │  DownloadsViewModel  │  │
│  │ (Tab state,      │  │ (Sniffer toggle, │  │                      │  │
│  │  cancel/retry)   │  │  History Flow)   │  │                      │  │
│  └────────┬─────────┘  └────────┬─────────┘  └──────────┬───────────┘  │
└───────────┼─────────────────────┼───────────────────────┼──────────────┘
            ▼                     ▼                       ▼
┌────────────────────────────────────────────────────────────────────────┐
│                             DATA LAYER                                 │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                     VideoRepository                              │  │
│  │  - observeAllVideos(): Flow<List<CapturedVideoEntity>>           │  │
│  │  - observeDownloadingVideos(): Flow<List<CapturedVideoEntity>>   │  │
│  │  - observeCompletedVideos(): Flow<List<CapturedVideoEntity>>     │  │
│  │  - observeFailedVideos(): Flow<List<CapturedVideoEntity>>        │  │
│  │  - observeHistory(): Flow<List<BrowsingHistoryEntity>>           │  │
│  │  - insertHistory(url, title)                                     │  │
│  │  - cancelActiveDownloads()                                       │  │
│  │  - retryDownload(id)                                             │  │
│  └───────────────────┬───────────────────────────────┬──────────────┘  │
│                      │                               │                 │
│                      ▼                               ▼                 │
│         ┌─────────────────────────┐      ┌──────────────────────────┐  │
│         │   Room Database & DAOs  │      │  Android DownloadManager │  │
│         │ - CapturedVideoEntity   │      │  + DownloadCompletedRcv  │  │
│         │ - BrowsingHistoryEntity │      │  (strictly by downloadId)│  │
│         └─────────────────────────┘      └──────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Implementation Roadmap (Phases)

1. **Camada de Dados & Persistência (Room):**
   - Atualizar `CapturedVideoEntity` com constante `STATUS_FAILED = "FAILED"`.
   - Criar `BrowsingHistoryEntity` (`id`, `url`, `title`, `visitedAt`).
   - Criar `BrowsingHistoryDao` com inserção, expurgo para manter 30 itens e `observeHistory()`.
   - Adicionar consultas no `VideoDao`: `observeAllVideos()`, `observeDownloadingVideos()`, `observeFailedVideos()`, `updateStatus(id, status)` e `cancelDownloads()`.
   - Atualizar `AppDatabase` com versão 2 e fallback to destructive migration.
2. **Repositório & DownloadHelper:**
   - Adicionar cancelamento de downloads ativos (`downloadHelper.cancelDownload(downloadId)` via `downloadManager.remove`).
   - Adicionar método `retryDownload(id)`.
   - Adicionar gravação e limpeza de histórico.
   - Corrigir `DownloadCompletedReceiver` para disparar notificação pontual apenas para o item do `downloadId` correspondente.
3. **Nova Tela Home (`HomeScreen.kt`) & `HomeViewModel`:**
   - Criar `HomeViewModel` observando os fluxos por status.
   - Criar `HomeScreen` com `PrimaryTabRow` (TODOS, BAIXANDO, COMPLETOS, ERROS).
   - Desenvolver cards ricos com barra de progresso, botão de cancelar em downloads ativos e botão de retry para falhas.
4. **Melhorias no Navegador (`BrowserScreen.kt` & `BrowserViewModel`):**
   - Adicionar persistência da última URL com recarregamento automático no arranque.
   - Reorganizar a barra superior em linha única: Voltar, Avançar, Recarregar, Omnibox e menu de opções.
   - Menu de 3 pontos: toggle "Começar Captura", "Voltar para Home" e "Histórico".
   - Criar diálogo modal `BrowsingHistoryDialog` listando os 30 sites recentes com clique para carregar URL e botão de limpar.
5. **Ações no BottomSheet e Correção do Botão "X":**
   - Corrigir o botão "X" do `BatchDownloadSheet`: fecha o modal sem excluir itens do Room.
   - Adicionar barra/menu de ações no canto inferior: "Limpar Todos" (com confirmação) e "Cancelar Download".
6. **Reestruturação da Barra Inferior (`MainScreen.kt`):**
   - 3 abas na `NavigationBar`: "Home", "Navegador" e "Biblioteca".
   - Atualizar strings e rótulos ("Biblioteca" para a tela de baixados).
7. **Verificação & Compilação:**
   - Executar `compile_applet` e testes unitários/Robolectric para garantir estabilidade e ausência de regressões.
