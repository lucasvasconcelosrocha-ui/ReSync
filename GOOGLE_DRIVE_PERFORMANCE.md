# Desempenho e Integração do Google Drive de Alta Velocidade — ReSync 1.0.24

## 1. Arquitetura de Transferência em Nuvem

O ReSync 1.0.24 preserva integralmente e estende o motor de alta velocidade do Google Drive desenvolvido nas etapas anteriores. Todos os dados coletados tanto pelo mecanismo **Sem Root** quanto pelo mecanismo **Com Root** utilizam a mesma infraestrutura de sincronização e armazenamento na nuvem.

---

## 2. Pilares de Otimização e Desempenho

### 2.1 Protocolo de Upload Retomável em Blocos (Resumable Chunked Transfer)
- **Tamanho dos Chunks:** Blocos múltiplos de 256 KB ajustados dinamicamente (1 MB a 4 MB) com base na latência e qualidade da conexão do usuário.
- **Header `Content-Range`:** Permite que conexões instáveis ou interrupções de sinal do dispositivo móvel retomem o upload exatamente do último byte confirmado pela API do Google Drive, sem reiniciar do zero.
- **URI de Sessão Persistente:** A URL de upload retomável gerada pelo Google Drive é armazenada na base Room (`DriveUploadSession`), permitindo retomadas automáticas mesmo após o fechamento do app ou reinicialização do aparelho.

### 2.2 Streaming Direto de Provedores de Conteúdo (Zero-Copy ContentResolver Streaming)
- **Sem Arquivos Temporários Duplicados:** Arquivos volumosos (como vídeos da câmera ou árvores de documentos de vários gigabytes) são lidos por streaming contínuo (`InputStream`) via `ContentResolver.openInputStream()` ou canais de arquivos locais e canalizados diretamente para o corpo da requisição HTTP.
- **Controle de Heap:** O consumo de memória RAM do processo mantém-se linear e contido em patamares inferiores a 50 MB, prevenindo erros de `OutOfMemoryError` mesmo em transferências de pacotes de 20 GB+.

### 2.3 Medição de Velocidade Real e Telemetria em Tempo Real
- **Cálculo da Taxa em MB/s:** Média móvel ponderada com janela de 1,5 segundos calculando a taxa efetiva de bytes transmitidos e confirmados pelo socket de rede (`bytesTransferred / timeDeltaMs`).
- **Estimativa Realista de Tempo Restante (ETA):** Baseada na velocidade sustentada em tempo real e na quantidade exata de bytes pendentes do container ativo.
- **Bento UI Cards:** Exibição fluida na tela inicial com atualizações a cada 250 ms sem engasgos de interface Jetpack Compose.

### 2.4 Validação Criptográfica SHA-256 de Ponta a Ponta
- **Pré-Upload:** O `BackupIntegrityValidator` calcula o hash SHA-256 do pacote durante ou logo após a compactação local.
- **Pós-Upload:** O hash é anexado aos metadados do arquivo no Google Drive (propriedades customizadas do Drive File) e comparado com o hash calculado pelo servidor quando aplicável.
- **Download e Restauração:** Antes de permitir que qualquer arquivo baixado seja descompactado ou restaurado, o ReSync recalcula o SHA-256 do arquivo local e rejeita imediatamente blocos que apresentem divergência, garantindo integridade absoluta dos dados.

### 2.5 Resiliência e Recuperação de Rede
- **Exponential Backoff:** Em caso de erros de HTTP (500, 502, 503, 504 ou erros de socket transitórios), o cliente efetua retries automáticos com atraso exponencial (1s, 2s, 4s, 8s, 16s) e jitter pseudo-aleatório.
- **Políticas de Wi-Fi e Bateria:** Suporte a execução restrita a redes não medidas (Wi-Fi) e carregador conectado quando configurado pelo usuário nas preferências do aplicativo.
