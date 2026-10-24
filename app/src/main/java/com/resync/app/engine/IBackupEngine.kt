package com.resync.app.engine

import java.io.File

/**
 * Interface comum para os motores de backup e restauração do ReSync.
 * Segue o padrão de abstração e inversão de controle (IoC), permitindo que a aplicação
 * execute operações com privilégios de Superusuário (Root) ou em modo padrão (Sem Root),
 * sem acoplamento direto nas camadas de apresentação ou regras de negócio.
 */
interface IBackupEngine {

    /**
     * Indica se este motor está operando com privilégios elevados de Root (uid=0).
     */
    val isRootEnabled: Boolean

    /**
     * Executa o backup de arquivos de mídia, documentos e armazenamento externo.
     *
     * @param sourcePaths Lista opcional de caminhos a incluir. Se vazia, utiliza diretórios padrão.
     * @param destinationDir Diretório de saída onde o container ou arquivos serão gravados.
     * @return true se a operação for concluída com sucesso, false caso contrário.
     */
    suspend fun backupMediaAndFiles(
        sourcePaths: List<String> = emptyList(),
        destinationDir: File? = null
    ): Boolean

    /**
     * Extrai os APKs instalados (base e splits) para o destino configurado.
     *
     * @param packageNames Lista de pacotes alvo. Se vazia, considera todos os aplicativos de usuário.
     * @param destinationDir Diretório de saída para os pacotes de instalação.
     * @return true se a extração for bem-sucedida, false em caso de falha.
     */
    suspend fun extractInstalledApks(
        packageNames: List<String> = emptyList(),
        destinationDir: File? = null
    ): Boolean

    /**
     * Efetua o backup dos dados internos, preferências e bancos de dados dos aplicativos (/data/data).
     *
     * @param packageNames Lista de pacotes a processar.
     * @param destinationDir Diretório onde os arquivos compactados de dados serão salvos.
     * @return true se o backup dos dados for concluído com sucesso, false ou exceção controlada no modo Normal.
     */
    suspend fun backupAppData(
        packageNames: List<String> = emptyList(),
        destinationDir: File? = null
    ): Boolean

    /**
     * Restaura os dados internos, bancos de dados e preferências para /data/data/[pacote].
     *
     * @param packageNames Lista de pacotes a restaurar.
     * @param sourceDir Diretório de origem onde os dados arquivados se encontram.
     * @return true se a restauração for bem-sucedida, false ou exceção controlada no modo Normal.
     */
    suspend fun restoreAppData(
        packageNames: List<String> = emptyList(),
        sourceDir: File? = null
    ): Boolean
}
