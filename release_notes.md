🚀 **O QUE HÁ DE NOVO NESTA VERSÃO (v1.0.35):**

🔥 **Google Drive Turbo 2.0 (Velocidade Máxima de Transferência)**
• **OkHttp3 HTTP/2 Puro & Socket Buffers de 512 KB:** Substituição do cliente legado por conexão persistente HTTP/2 com pool de conexões (16 idle, 5 min keep-alive) e buffers de socket de 512 KB com TCP_NODELAY.
• **Download Paralelo Multi-Range:** Downloads divididos em até 4 conexões simultâneas via `Range: bytes=start-end` gravando diretamente nas posições exatas via `FileChannel` em arquivo pré-alocado.
• **Upload Resumable com Single-Pass MD5:** Streaming direto sem flush por bloco e cálculo de integridade MD5 em passagem única inline, eliminando a releitura redundante de disco e utilizando chunks dinâmicos de até 32 MB.
• **Zero Overhead GZIP:** Header `Accept-Encoding: identity` para transferir containers `.resync` sem dupla compressão.

⚡ **Pipeline Assíncrono de Compactação & Criptografia (Zero Bottleneck)**
• **BufferPool Thread-Safe:** Reutilização contínua de buffers de 128 KB e 256 KB (`ConcurrentLinkedQueue`), eliminando pausas de Garbage Collection (GC) e alocações repetitivas no heap.
• **Produtor/Consumidor com Coroutine Channels:** I/O de armazenamento desacoplado do empacotamento Zip/Deflater e da cifragem AES-256-GCM para arquivos pesados (> 2 MB), saturando I/O e núcleos ARM64 em paralelo.

🛡️ **Blindagem dos Foreground Services (Android 14/15)**
• **Zero Timeout no `startForeground()`:** Invocação de `startForeground()` e aquisição de locks como a primeira linha síncrona absoluta de `onStartCommand()`, eliminando `ForegroundServiceDidNotStartInTimeException`.
• **WakeLock & WifiLock de Alta Performance:** Locks imediatos com `WIFI_MODE_FULL_LOW_LATENCY` e `PARTIAL_WAKE_LOCK` para impedir que o rádio Wi-Fi e os clocks da CPU entrem em economia de energia durante transferências com tela apagada.

📦 **Desbaste Agressivo de Bloat e Redução Estrutural do APK**
• **Material Icons Subset:** Remoção do JAR de 84 MB do `material-icons-extended`, substituído por um subset sob medida de 2.4 MB contendo exclusivamente os 170 ícones utilizados no app e os metadados `.kotlin_module`.
• **Remoção de Dependências Mortas:** Removido `androidx.appcompat:appcompat` e excluído `org.apache.httpcomponents`.
• **Arquitetura 64-bit Exclusiva:** Filtrado NDK para `arm64-v8a` e descarte de arquivos de metadados inúteis.
• **APK de Apenas 4.8 MB:** Redução drástica no peso do binário com R8 minification e shrinkResources ativos.
