🚀 **O QUE HÁ DE NOVO NESTA VERSÃO:**

🎨 **Nova Interface Bento UI (Dark Minimalista & OLED)**
• **Redesign Visual Completo:** Nova arquitetura visual em todas as telas do aplicativo com fundo Preto OLED profundo (`#05070A`), cards modulares Bento (`#0E121A`), bordas finas (*hairline*) e micro-interações fluidas.
• **Sincronização Global de Cores e Temas:** Padronização completa do `MaterialTheme` em 100% das telas, botões, indicadores e barras de progresso para respeitar dinamicamente a cor de tema escolhida pelo usuário.
• **Foto de Perfil Integrada:** Carregamento automático do avatar real da conta Google logada na barra superior em todo o aplicativo.

📊 **Novo Dashboard Inicial Interativo & Telemetria Real**
• **Bento Grid de Estatísticas:** 4 cards no topo exibindo em tempo real: Último Backup Concluído (com data/hora relativa e tipo), Total de Arquivos Protegidos (com contagem e peso formatado), Armazenamento em Nuvem Google Drive (gráfico circular com espaço livre/usado real) e Nível de Segurança (AES-256-GCM / Biometria / Modo de Operação).
• **Navegação Direta pelos Cards:** Toque nos cards do topo para abrir diretamente a Nuvem, Lista de Backups ou Diagnóstico do Sistema.
• **Monitor do Sistema & Diagnóstico Root:** Seção dedicada com status em tempo real do Kernel Linux, SELinux, Arquitetura, Nível de API e teste instantâneo de permissão Superusuário/Root com modal detalhado de diagnóstico.
• **Atalhos Rápidos de Ação:** Limpeza de cache do sistema, verificação manual de integridade e acesso veloz a operações críticas.

🧩 **Central de Módulos de Dados Dedicada**
• **Telas Bento para Todos os Tipos de Dados:** Interfaces modernas e modulares para Bluetooth (dispositivos pareados), Mensagens SMS/MMS, Registro de Chamadas, Contatos Telefônicos, Papéis de Parede e Redes Wi-Fi.
• **Módulo Dedicado para DNS Privado:** Painel específico para backup, restauração e inspeção do DNS Seguro ativo do sistema com validação de status e IPs.
• **Seleção Múltipla Granular:** Capacidade de selecionar/deselecionar itens individuais ou em lote em cada módulo antes de realizar o backup.

⚡ **Motor Universal de Backup (Root & Sem Root)**
• **Detecção Inteligente de Privilégios:** Alternância transparente e automática entre `RootBackupEngine` e `NonRootBackupEngine` conforme os acessos concedidos pelo usuário (SAF, MediaStore, ContentResolvers e Root).
• **Google Drive de Alto Desempenho:** Upload e download otimizados em chunks, integridade garantida com verificação SHA-256 e criptografia ponta a ponta AES-256-GCM.
• **Sem Travamentos ou Textos Cortados:** Ajuste fino de paddings, weights e dimensões de botões e cards para exibição perfeita em qualquer resolução e proporção de tela.
