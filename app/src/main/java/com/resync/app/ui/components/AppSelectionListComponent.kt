package com.resync.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.data.model.AppInfo
import com.resync.app.engine.StorageRepository
import com.resync.app.ui.theme.*

@Composable
fun AppSelectionListComponent(
    apps: List<AppInfo>,
    selectedPackages: Set<String>,
    onTogglePackage: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onDeselectAll: (List<String>) -> Unit,
    primaryAccent: Color,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 290.dp,
    showIndividualBackupButton: Boolean = false,
    showInternalSystemPackages: Boolean = false,
    onIndividualBackup: ((AppInfo) -> Unit)? = null,
    onAppClick: ((AppInfo) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("INSTALLED") } // "INSTALLED", "SYSTEM", "ALL"
    var isFilterDropdownExpanded by remember { mutableStateOf(false) }

    val visibleApps = remember(apps, showInternalSystemPackages) {
        if (showInternalSystemPackages) apps else apps.filter { !it.isInternalSystemPackage && !com.resync.app.engine.PackageIgnorePolicy.isInternalSystemPackage(it.packageName) }
    }

    val installedCount = remember(visibleApps) { visibleApps.count { !it.isSystemApp } }
    val systemCount = remember(visibleApps) { visibleApps.count { it.isSystemApp } }
    val allCount = remember(visibleApps) { visibleApps.size }

    val filteredApps = remember(visibleApps, searchQuery, selectedFilter) {
        visibleApps.filter { app ->
            val matchesFilter = when (selectedFilter) {
                "INSTALLED" -> !app.isSystemApp
                "SYSTEM" -> app.isSystemApp
                else -> true
            }
            val matchesSearch = if (searchQuery.isBlank()) true else {
                app.appName.contains(searchQuery.trim(), ignoreCase = true) ||
                        app.packageName.contains(searchQuery.trim(), ignoreCase = true)
            }
            matchesFilter && matchesSearch
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 1. Campo de Busca (48 dp, 14 sp centralizado verticalmente)
        BasicTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = TextPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(DarkSurfaceVariant, RoundedCornerShape(10.dp))
                .border(
                    1.dp,
                    if (searchQuery.isNotEmpty()) primaryAccent else DarkCardBorder,
                    RoundedCornerShape(10.dp)
                ),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = if (searchQuery.isNotEmpty()) primaryAccent else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Pesquisar aplicativo ou pacote...",
                                fontSize = 14.sp,
                                color = TextMuted,
                                maxLines = 1
                            )
                        }
                        innerTextField()
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Limpar",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 2. Filtro Dropdown (Instalados, Sistema, Todos)
        Box(modifier = Modifier.fillMaxWidth()) {
            val filterLabel = when (selectedFilter) {
                "SYSTEM" -> "Sistema ($systemCount)"
                "ALL" -> "Todos os apps ($allCount)"
                else -> "Instalados ($installedCount)"
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkSurfaceVariant,
                border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clickable { isFilterDropdownExpanded = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Filtro: $filterLabel",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryAccent,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = primaryAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = isFilterDropdownExpanded,
                onDismissRequest = { isFilterDropdownExpanded = false },
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .background(DarkSurfaceVariant)
            ) {
                DropdownMenuItem(
                    text = { Text("Instalados pelo usuário ($installedCount)", color = TextPrimary, fontSize = 12.sp) },
                    onClick = {
                        selectedFilter = "INSTALLED"
                        isFilterDropdownExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Aplicativos do Sistema ($systemCount)", color = TextPrimary, fontSize = 12.sp) },
                    onClick = {
                        selectedFilter = "SYSTEM"
                        isFilterDropdownExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Todos os Aplicativos ($allCount)", color = TextPrimary, fontSize = 12.sp) },
                    onClick = {
                        selectedFilter = "ALL"
                        isFilterDropdownExpanded = false
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 3. Ações Rápidas: Marcar todos e Desmarcar todos + Contador
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = primaryAccent.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.4f)),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clickable {
                        val eligiblePkgs = filteredApps
                            .filterNot { it.isInternalSystemPackage || com.resync.app.engine.PackageIgnorePolicy.isInternalSystemPackage(it.packageName) }
                            .map { it.packageName }
                        onSelectAll(eligiblePkgs)
                    }
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Marcar todos",
                        color = primaryAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkSurfaceVariant,
                border = BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clickable {
                        onDeselectAll(filteredApps.map { it.packageName })
                    }
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Desmarcar todos",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Contador de seleção atual
        val filteredSelectedCount = filteredApps.count { selectedPackages.contains(it.packageName) }
        Text(
            text = "$filteredSelectedCount de ${filteredApps.size} selecionado(s)",
            fontSize = 11.sp,
            color = TextMuted,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 4. Lista Rolável de Aplicativos
        val itemSpacing = 6.dp
        val appListScrollState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

        if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                if (allCount == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = primaryAccent, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Carregando aplicativos instalados...",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Text(
                        text = if (searchQuery.isNotEmpty()) "Nenhum aplicativo encontrado para a busca" else "Nenhum aplicativo neste filtro",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                state = appListScrollState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = maxHeight.coerceAtLeast(320.dp)),
                verticalArrangement = Arrangement.spacedBy(itemSpacing)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isChecked = selectedPackages.contains(app.packageName)
                    val sizeFormatted = StorageRepository.formatFileSize(app.sizeBytes)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 72.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                1.dp,
                                if (isChecked) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                if (onAppClick != null && !showIndividualBackupButton) {
                                    onAppClick(app)
                                } else {
                                    onTogglePackage(app.packageName)
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isChecked) primaryAccent.copy(alpha = 0.08f) else DarkSurfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { onTogglePackage(app.packageName) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = primaryAccent,
                                        uncheckedColor = TextMuted
                                    )
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                RealAppIcon(
                                    packageName = app.packageName,
                                    size = 36.dp,
                                    shapeRadius = 8.dp
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = app.appName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = app.packageName,
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "$sizeFormatted • v${app.versionName}",
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            if (showIndividualBackupButton && onIndividualBackup != null) {
                                Button(
                                    onClick = { onIndividualBackup(app) },
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Backup", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
