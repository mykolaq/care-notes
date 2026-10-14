package com.carenotes.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val medicalTerms = mapOf(
    "МНН" to "Международное непатентованное наименование — название действующего вещества лекарства. Торговые названия могут различаться, а действующее вещество быть одним и тем же. Название можно найти в инструкции или на упаковке.",
    "ОХЛП" to "Общая характеристика лекарственного препарата — официальный документ для медицинских специалистов. Содержит подробные сведения о препарате, его применении и ограничениях. Для пациента обычно удобнее листок-вкладыш.",
    "ЛВ" to "Листок-вкладыш — инструкция для пациента. В ней описаны применение лекарства, возможные побочные действия и меры предосторожности.",
    "РУ" to "Регистрационное удостоверение — документ о регистрации лекарства. Его номер помогает найти нужную запись в реестре. При выборе инструкции сверяйте также название, форму, дозировку и производителя с упаковкой.",
    "ГРЛС" to "Государственный реестр лекарственных средств — официальный реестр Минздрава России. В нём можно найти сведения о регистрации препаратов и документы к ним."
)

@Composable internal fun MedicalTermsHelp(vararg terms: String) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Подсказка: ${terms.joinToString(", ")}")
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Что означают сокращения") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                terms.forEach { term ->
                    Text(term, style = MaterialTheme.typography.titleMedium)
                    Text(medicalTerms.getValue(term), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Понятно") } }
    )
}
