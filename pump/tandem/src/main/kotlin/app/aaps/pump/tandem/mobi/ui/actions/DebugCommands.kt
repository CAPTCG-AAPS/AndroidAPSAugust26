@file:OptIn(ExperimentalMaterial3Api::class)

package app.aaps.pump.tandem.mobi.ui.actions

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.tandem.R
import app.aaps.pump.tandem.common.driver.LocalTandemDataStore
import app.aaps.pump.tandem.mobi.ui.util.HeaderLineWithBackButton
import com.jwoglom.pumpx2.pump.TandemError
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.response.ErrorResponse
import com.jwoglom.pumpx2.pump.messages.util.MessageHelpers
import kotlinx.coroutines.delay
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.time.Instant

@Composable
fun DebugCommands(
    innerPadding: PaddingValues = PaddingValues(),
    sendPumpCommands: (List<Message>) -> Boolean,
    navigateBack: () -> Unit,
    resourceHelper: ResourceHelper,
    aapsLogger: AAPSLogger,
    showHeader: Boolean = true,
) {
    val context = LocalContext.current
    val ds = LocalTandemDataStore.current
    val clipboard: ClipboardManager = LocalClipboardManager.current
    val lastReceived by ds.debugLastReceivedMessage.observeAsState()
    val lastTandemError by ds.debugLastTandemError.observeAsState()

    var pickerClass by remember { mutableStateOf<Class<out Message>?>(null) }
    var pickerChoices by remember { mutableStateOf<List<Constructor<out Message>>>(emptyList()) }

    var argDialogClass by remember { mutableStateOf<Class<out Message>?>(null) }
    var argDialogConstructor by remember { mutableStateOf<Constructor<out Message>?>(null) }

    var awaitingRequest by remember { mutableStateOf<Message?>(null) }
    var awaitingSince by remember { mutableStateOf(0L) }
    var capturedResponse by remember { mutableStateOf<Message?>(null) }
    var awaitingTimedOut by remember { mutableStateOf(false) }

    LaunchedEffect(awaitingRequest, lastReceived) {
        val req = awaitingRequest ?: return@LaunchedEffect
        val resp = lastReceived ?: return@LaunchedEffect
        if (resp.opCode() == req.responseOpCode) {
            capturedResponse = resp
        }
    }
    LaunchedEffect(awaitingRequest, lastTandemError) {
        if (awaitingRequest == null) return@LaunchedEffect
        val err = lastTandemError ?: return@LaunchedEffect
        val errResp = err.errorResponse ?: return@LaunchedEffect
        capturedResponse = errResp
    }
    LaunchedEffect(awaitingRequest) {
        if (awaitingRequest != null) {
            awaitingTimedOut = false
            delay(10_000)
            if (capturedResponse == null) awaitingTimedOut = true
        }
    }

    fun sendAndAwait(constructor: Constructor<out Message>, args: List<Any>) {
        try {
            val message = constructor.newInstance(*args.toTypedArray())
            ds.debugLastReceivedMessage.value = null
            ds.debugLastTandemError.value = null
            capturedResponse = null
            awaitingTimedOut = false
            awaitingRequest = message
            awaitingSince = System.currentTimeMillis()
            val ok = sendPumpCommands(listOf(message))
            if (!ok) {
                Toast.makeText(context, "Send failed (pump not connected?)", Toast.LENGTH_SHORT).show()
                awaitingRequest = null
            }
        } catch (e: InvocationTargetException) {
            aapsLogger.error(LTag.PUMP, "DebugCommands send failed", e)
            Toast.makeText(context, "Constructor failed: ${e.targetException?.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "DebugCommands send failed", e)
            Toast.makeText(context, "Send failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val groupedMessages = remember {
        MessageHelpers.getAllPumpRequestMessages()
            .filter { m ->
                !m.startsWith("authentication.") &&
                    !m.startsWith("historyLog.") &&
                    !m.contains("FactoryResetRequest") &&
                    !m.contains("FactoryResetBRequest") &&
                    !m.contains("Nonexistent", ignoreCase = true)
            }
            .sorted()
            .groupBy { it.substringBefore('.', missingDelimiterValue = "other") }
            .toSortedMap()
    }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    fun openMessage(messageName: String) {
        try {
            val className = MessageHelpers.REQUEST_PACKAGE + "." + messageName
            val clazz = Class.forName(className)
            if (!Message::class.java.isAssignableFrom(clazz)) {
                Toast.makeText(context, "Not a Message: $className", Toast.LENGTH_SHORT).show()
                return
            }
            @Suppress("UNCHECKED_CAST")
            val messageClass = clazz as Class<out Message>

            val constructors = messageClass.constructors
                .filterIsInstance<Constructor<out Message>>()
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }

            val noArg = constructors.firstOrNull { it.parameterCount == 0 }
            val argCtors = constructors
                .filter { it.parameterCount > 0 }
                .filter { ctor -> ctor.parameterTypes.none { it == ByteArray::class.java } }
                .filter { ctor -> ctor.parameterTypes.all { isSupportedDebugConstructorType(it) } }
                .sortedBy { it.parameterCount }

            if (argCtors.isEmpty()) {
                if (noArg == null) {
                    Toast.makeText(context, "No usable constructor for ${messageClass.simpleName}", Toast.LENGTH_SHORT).show()
                    return
                }
                sendAndAwait(noArg, emptyList())
                return
            }

            val choices = buildList<Constructor<out Message>> {
                if (noArg != null) add(noArg)
                addAll(argCtors)
            }

            if (choices.size == 1) {
                val only = choices.first()
                if (only.parameterCount == 0) {
                    sendAndAwait(only, emptyList())
                } else {
                    argDialogClass = messageClass
                    argDialogConstructor = only
                }
            } else {
                pickerClass = messageClass
                pickerChoices = choices
            }
        } catch (e: ClassNotFoundException) {
            aapsLogger.error(LTag.PUMP, "DebugCommands openMessage failed: $messageName", e)
            Toast.makeText(context, "Class not found: $messageName", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (showHeader) {
            item {
                HeaderLineWithBackButton(
                    text = resourceHelper.gs(R.string.debug_commands_title),
                    onBackClick = navigateBack,
                    resourceHelper = resourceHelper,
                )
                HorizontalDivider()
            }
        }

        groupedMessages.forEach { (group, messages) ->
            item(key = "header-$group") {
                val expanded = expandedGroups[group] == true
                ListItem(
                    headlineContent = { Text("$group  (${messages.size})") },
                    leadingContent = {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { expandedGroups[group] = !expanded },
                )
                HorizontalDivider()
            }
            if (expandedGroups[group] == true) {
                items(messages, key = { "$group/$it" }) { name ->
                    ListItem(
                        headlineContent = { Text(name.substringAfterLast('.')) },
                        leadingContent = { Icon(Icons.Filled.Build, contentDescription = null) },
                        modifier = Modifier.clickable { openMessage(name) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    val pickerCls = pickerClass
    if (pickerCls != null) {
        AlertDialog(
            onDismissRequest = {
                pickerClass = null
                pickerChoices = emptyList()
            },
            title = { Text("${pickerCls.simpleName}: pick constructor") },
            text = {
                Column {
                    pickerChoices.forEach { ctor ->
                        val label = if (ctor.parameterCount == 0) "No arguments"
                        else formatDebugConstructorSignature(pickerCls, ctor)
                        TextButton(
                            onClick = {
                                pickerClass = null
                                pickerChoices = emptyList()
                                if (ctor.parameterCount == 0) {
                                    sendAndAwait(ctor, emptyList())
                                } else {
                                    argDialogClass = pickerCls
                                    argDialogConstructor = ctor
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    pickerClass = null
                    pickerChoices = emptyList()
                }) { Text("Cancel") }
            },
        )
    }

    val argCls = argDialogClass
    val argCtor = argDialogConstructor
    if (argCls != null && argCtor != null) {
        ConstructorArgumentDialog(
            messageClass = argCls,
            constructor = argCtor,
            onDismiss = {
                argDialogClass = null
                argDialogConstructor = null
            },
            onSend = { args ->
                argDialogClass = null
                argDialogConstructor = null
                sendAndAwait(argCtor, args)
            },
        )
    }

    val req = awaitingRequest
    if (req != null) {
        ResponseDialog(
            request = req,
            response = capturedResponse,
            timedOut = awaitingTimedOut,
            elapsedMs = System.currentTimeMillis() - awaitingSince,
            onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                awaitingRequest = null
                capturedResponse = null
                awaitingTimedOut = false
            },
        )
    }
}

@Composable
private fun ResponseDialog(
    request: Message,
    response: Message?,
    timedOut: Boolean,
    elapsedMs: Long,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = response?.javaClass?.simpleName ?: request.javaClass.simpleName
    val body: String = when {
        response is ErrorResponse -> buildString {
            appendLine("From ${request.javaClass.simpleName}")
            appendLine()
            appendLine("Pump rejected the request.")
            appendLine()
            appendLine("errorCode=${response.errorCode}")
            appendLine("requestCodeId=${response.requestCodeId}")
            appendLine()
            append(response.toString())
        }
        response != null -> response.toString()
        timedOut         -> "No response within 10s. Pump may not have replied yet — leave open if it might still arrive."
        else             -> "Waiting for response… (${elapsedMs / 1000}s)"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(body)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (response != null) {
                TextButton(onClick = { onCopy(body) }) { Text("Copy") }
            }
        },
    )
}

@Composable
private fun ConstructorArgumentDialog(
    messageClass: Class<out Message>,
    constructor: Constructor<out Message>,
    onDismiss: () -> Unit,
    onSend: (List<Any>) -> Unit,
) {
    val context = LocalContext.current
    val paramTypes = constructor.parameterTypes
    val inputs = remember(constructor) {
        Array(paramTypes.size) { mutableStateOf("") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(formatDebugConstructorSignature(messageClass, constructor)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                paramTypes.forEachIndexed { index, type ->
                    val state = inputs[index]
                    OutlinedTextField(
                        value = state.value,
                        onValueChange = { state.value = it },
                        label = { Text("arg${index + 1}: ${debugTypeHint(type)}") },
                        keyboardOptions = KeyboardOptions(keyboardType = debugKeyboardType(type)),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = mutableListOf<Any>()
                for ((index, type) in paramTypes.withIndex()) {
                    val raw = inputs[index].value.trim()
                    if (raw.isBlank()) {
                        Toast.makeText(context, "Argument ${index + 1} is required", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    try {
                        parsed.add(parseDebugConstructorArg(raw, type))
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Invalid value for arg ${index + 1} (${debugTypeHint(type)})",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@TextButton
                    }
                }
                onSend(parsed)
            }) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatDebugConstructorSignature(
    messageClass: Class<out Message>,
    constructor: Constructor<out Message>,
): String =
    "${messageClass.simpleName}(" + constructor.parameterTypes.joinToString(", ") { it.simpleName } + ")"

private fun isSupportedDebugConstructorType(type: Class<*>): Boolean {
    if (type == ByteArray::class.java) return false
    if (type.isEnum) return true
    return type == String::class.java ||
        type == Instant::class.java ||
        type == Int::class.javaPrimitiveType || type == Int::class.java ||
        type == Long::class.javaPrimitiveType || type == Long::class.java ||
        type == Boolean::class.javaPrimitiveType || type == Boolean::class.java ||
        type == Float::class.javaPrimitiveType || type == Float::class.java ||
        type == Double::class.javaPrimitiveType || type == Double::class.java ||
        type == Short::class.javaPrimitiveType || type == Short::class.java ||
        type == Byte::class.javaPrimitiveType || type == Byte::class.java
}

private fun debugTypeHint(type: Class<*>): String {
    if (type == Instant::class.java) return "Instant (epoch seconds or ISO-8601)"
    if (type.isEnum) {
        val values = type.enumConstants?.joinToString("|") { (it as Enum<*>).name } ?: ""
        return "${type.simpleName} [$values]"
    }
    return type.simpleName
}

private fun debugKeyboardType(type: Class<*>): KeyboardType = when {
    type == Int::class.javaPrimitiveType || type == Int::class.java ||
        type == Long::class.javaPrimitiveType || type == Long::class.java ||
        type == Short::class.javaPrimitiveType || type == Short::class.java ||
        type == Byte::class.javaPrimitiveType || type == Byte::class.java -> KeyboardType.Number

    type == Float::class.javaPrimitiveType || type == Float::class.java ||
        type == Double::class.javaPrimitiveType || type == Double::class.java -> KeyboardType.Decimal

    else -> KeyboardType.Text
}

private fun parseDebugConstructorArg(raw: String, type: Class<*>): Any = when {
    type == String::class.java -> raw
    type == Int::class.javaPrimitiveType || type == Int::class.java -> raw.toInt()
    type == Long::class.javaPrimitiveType || type == Long::class.java -> raw.toLong()
    type == Boolean::class.javaPrimitiveType || type == Boolean::class.java -> parseBoolInput(raw)
    type == Float::class.javaPrimitiveType || type == Float::class.java -> raw.toFloat()
    type == Double::class.javaPrimitiveType || type == Double::class.java -> raw.toDouble()
    type == Short::class.javaPrimitiveType || type == Short::class.java -> raw.toShort()
    type == Byte::class.javaPrimitiveType || type == Byte::class.java -> raw.toByte()
    type == Instant::class.java ->
        if (raw.matches(Regex("^-?\\d+$"))) Instant.ofEpochSecond(raw.toLong()) else Instant.parse(raw)
    type.isEnum -> {
        @Suppress("UNCHECKED_CAST")
        val enumClass = type as Class<out Enum<*>>
        enumClass.enumConstants?.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("Invalid enum value '$raw' for ${type.name}")
    }
    else -> throw IllegalArgumentException("Unsupported parameter type: ${type.name}")
}

private fun parseBoolInput(text: String): Boolean = text == "1" || text.equals("true", ignoreCase = true)
