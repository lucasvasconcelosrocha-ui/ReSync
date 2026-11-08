🚀 **O QUE HÁ DE NOVO NESTA VERSÃO (v1.0.36):**

🔥 **Google Drive Turbo 2.0 (OkHttp3 HTTP/2 Puro sem SDKs Legados)**
• **Eliminação Total de SDKs Legados:** Remoção completa de `google-api-client`, `google-http-client` e `google-api-services-drive`. Todas as operações de Drive (listagem, metadados, streaming de upload resumável e download) operam diretamente sobre o protocolo HTTP/2 REST v3 com OkHttp3 e tokens OAuth2 via `GoogleAuthUtil`.
• **Streaming de Alta Velocidade & Zero-Copy:** Buffers de 256 KB com flush terminal único, chunks de upload resumável dinâmicos de até 32 MB e cálculo MD5 inline de passagem única.
• **Download Multi-Thread Otimizado:** Header `Accept-Encoding: identity`, conexões persistentes HTTP/2 e proteção de conexão de baixa latência (`WIFI_MODE_FULL_LOW_LATENCY`).

⚡ **Inicialização Instantânea de Serviços (Zero Timeout ANR)**
• **Blindagem do Foreground Service:** Invocação imediata de `startForeground()` e aquisição de locks (`PARTIAL_WAKE_LOCK` + `WifiLock`) na primeiríssima instrução síncrona de `onStartCommand()`, eliminando qualquer risco de `ForegroundServiceDidNotStartInTimeException` no Android 14 e 15.
• **Throttling de Notificações:** Notificações de progresso limitadas a no máximo uma emissão a cada 500ms, preservando a fluidez do sistema e threads de UI.

📦 **Otimização Extrema de Binário & Purga de Metadados (4.4 MB)**
• **Expurgo de Metadados do AGP e Kotlin:** Eliminação completa de `version-control-info.textproto`, `app-metadata.properties` e `reflect.kotlin_builtins` do pacote final.
• **Migração 100% WebP (q=85) & Desduplicação de Recursos:** Todos os ícones adaptativos e legados convertidos para WebP de alta qualidade, removendo duplicações de assets e economizando mais de 70% em tamanho de imagens.
• **Binário Enxuto:** Binário release arm64-v8a reduzido para apenas 4.4 MB com R8 full-mode e resource shrinking agressivo.
