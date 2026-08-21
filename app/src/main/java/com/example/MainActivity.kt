package com.example

import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavController
import com.example.ui.theme.MyApplicationTheme
import com.hivemq.client.mqtt.MqttClient
import org.json.JSONArray
import org.json.JSONObject
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedListener
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedListener
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class User(
    val username: String,
    val password: String
)

data class DeviceInfo(
    val id: String,
    val name: String
) {
    val displayText: String
        get() = if (id == "__ALL__" || id.equals("all", ignoreCase = true)) {
            "All Devices: __ALL__"
        } else if (name.isNotEmpty()) {
            "$name: $id"
        } else {
            id
        }
}

// View model to handle MQTT logic
data class DeviceState(
    var power: Boolean? = null,
    var temp: Int? = null,
    var mode: String? = null,
    var fan: String? = null,
    var swing: Boolean? = null,
    var swingHorizontal: Boolean? = null,
    var swingVertical: Boolean? = null,
    var scheduleOnTime: String? = null,
    var scheduleOffTime: String? = null,
    var scheduleEnabled: Boolean? = null,
    var tempDiff: Int? = null,
    var autoMode: Boolean? = null
)

class MqttViewModel : ViewModel() {

    private val deviceStatusMap = mutableMapOf<String, DeviceState>()
    private var broadcastResponseIndex = 0

    fun getDeviceState(deviceId: String): DeviceState? {
        return deviceStatusMap[deviceId]
    }

    fun setDevicesListForTesting(devicesList: List<DeviceInfo>) {
        val mutable = devicesList.toMutableList()
        if (mutable.none { it.id == "__ALL__" || it.id.equals("all", ignoreCase = true) }) {
            mutable.add(0, DeviceInfo(id = "__ALL__", name = "All Devices"))
        }
        _devices.value = mutable
    }

    private fun getNextBroadcastResponseIndex(): Int {
        val physicalDevices = _devices.value.filter { it.id != "__ALL__" && !it.id.equals("all", ignoreCase = true) }
        if (physicalDevices.isEmpty()) return 0
        val index = broadcastResponseIndex % physicalDevices.size
        broadcastResponseIndex = (broadcastResponseIndex + 1) % physicalDevices.size
        return index
    }

    private fun updateAllDevicesState(
        power: Boolean? = null,
        temp: Int? = null,
        mode: String? = null,
        fan: String? = null,
        swing: Boolean? = null,
        swingH: Boolean? = null,
        swingV: Boolean? = null,
        schedOn: String? = null,
        schedOff: String? = null,
        schedEnable: Boolean? = null,
        tempDiff: Int? = null,
        autoMode: Boolean? = null
    ) {
        val allIds = (_devices.value.map { it.id } + deviceStatusMap.keys).toSet()
        for (id in allIds) {
            if (id == "__ALL__" || id.equals("all", ignoreCase = true)) continue
            val devState = deviceStatusMap.getOrPut(id) { DeviceState() }
            if (power != null) devState.power = power
            if (temp != null) devState.temp = temp
            if (mode != null) devState.mode = mode
            if (fan != null) devState.fan = fan
            if (swing != null) devState.swing = swing
            if (swingH != null) devState.swingHorizontal = swingH
            if (swingV != null) devState.swingVertical = swingV
            if (schedOn != null) devState.scheduleOnTime = schedOn
            if (schedOff != null) devState.scheduleOffTime = schedOff
            if (schedEnable != null) devState.scheduleEnabled = schedEnable
            if (tempDiff != null) devState.tempDiff = tempDiff
            if (autoMode != null) devState.autoMode = autoMode
        }
    }

    private fun updateSingleDeviceState(
        deviceId: String,
        power: Boolean? = null,
        temp: Int? = null,
        mode: String? = null,
        fan: String? = null,
        swing: Boolean? = null,
        swingH: Boolean? = null,
        swingV: Boolean? = null,
        schedOn: String? = null,
        schedOff: String? = null,
        schedEnable: Boolean? = null,
        tempDiff: Int? = null,
        autoMode: Boolean? = null
    ) {
        if (deviceId == "__ALL__" || deviceId.equals("all", ignoreCase = true)) return
        val devState = deviceStatusMap.getOrPut(deviceId) { DeviceState() }
        if (power != null) devState.power = power
        if (temp != null) devState.temp = temp
        if (mode != null) devState.mode = mode
        if (fan != null) devState.fan = fan
        if (swing != null) devState.swing = swing
        if (swingH != null) devState.swingHorizontal = swingH
        if (swingV != null) devState.swingVertical = swingV
        if (schedOn != null) devState.scheduleOnTime = schedOn
        if (schedOff != null) devState.scheduleOffTime = schedOff
        if (schedEnable != null) devState.scheduleEnabled = schedEnable
        if (tempDiff != null) devState.tempDiff = tempDiff
        if (autoMode != null) devState.autoMode = autoMode
    }

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users

    private val _brokerUrl = MutableStateFlow("broker.hivemq.com")
    val brokerUrl: StateFlow<String> = _brokerUrl

    private val _topic = MutableStateFlow("ac/command")
    val topic: StateFlow<String> = _topic
    
    private val _subscribeTopic = MutableStateFlow("ac/status")
    val subscribeTopic: StateFlow<String> = _subscribeTopic

    private val _powerState = MutableStateFlow(false)
    val powerState: StateFlow<Boolean> = _powerState

    private val _temperature = MutableStateFlow<Int?>(null)
    val temperature: StateFlow<Int?> = _temperature

    private val _mode = MutableStateFlow("cool")
    val mode: StateFlow<String> = _mode

    private val _fan = MutableStateFlow("auto")
    val fan: StateFlow<String> = _fan

    // Status can be "Disconnected", "Connecting...", "Connected", "Error"
    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _devices = MutableStateFlow<List<DeviceInfo>>(listOf(DeviceInfo(id = "__ALL__", name = "All Devices")))
    val devices: StateFlow<List<DeviceInfo>> = _devices

    private val _selectedDevice = MutableStateFlow<String?>("__ALL__")
    val selectedDevice: StateFlow<String?> = _selectedDevice

    private val _swing = MutableStateFlow(false)
    val swing: StateFlow<Boolean> = _swing

    private val _swingHorizontal = MutableStateFlow(false)
    val swingHorizontal: StateFlow<Boolean> = _swingHorizontal

    private val _swingVertical = MutableStateFlow(false)
    val swingVertical: StateFlow<Boolean> = _swingVertical

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    private val _scheduleOnTime = MutableStateFlow("14:00")
    val scheduleOnTime: StateFlow<String> = _scheduleOnTime

    private val _scheduleOffTime = MutableStateFlow("18:00")
    val scheduleOffTime: StateFlow<String> = _scheduleOffTime

    private val _scheduleEnabled = MutableStateFlow(false)
    val scheduleEnabled: StateFlow<Boolean> = _scheduleEnabled

    private val _tempDiff = MutableStateFlow(1)
    val tempDiff: StateFlow<Int> = _tempDiff

    private val _autoMode = MutableStateFlow(false)
    val autoMode: StateFlow<Boolean> = _autoMode

    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
    }

    var isUserInteracting = false
    private var pollingJob: Job? = null

    private fun startPollingLoop() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000L)
                if (client?.state?.isConnected == true && !isUserInteracting) {
                    requestDeviceStatus(_selectedDevice.value)
                }
            }
        }
    }

    private var client: Mqtt3AsyncClient? = null

    fun updateBrokerUrl(url: String) {
        _brokerUrl.value = url
    }

    fun updateTopic(newTopic: String) {
        _topic.value = newTopic
    }

    fun updateSubscribeTopic(topic: String) {
        _subscribeTopic.value = topic
    }

    fun updateTemperature(temp: Int) {
        _temperature.value = temp
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message"
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                _logs.value = _logs.value + logEntry
            } else {
                viewModelScope.launch(Dispatchers.Main) {
                    _logs.value = _logs.value + logEntry
                }
            }
        } catch (_: Throwable) {
            _logs.value = _logs.value + logEntry
        }
    }

    fun connectToMqttBroker() {
        if (client?.state?.isConnected == true) {
            client?.disconnect()
        }

        val currentBroker = _brokerUrl.value
        _connectionStatus.value = "Connecting..."
        appendLog("Attempting to connect to $currentBroker...")
        
        // 1. Create a unique client ID
        val clientId = "ac_controller_android_${UUID.randomUUID()}"
        
        try {
            // 2. Build the MQTT 3 client asynchronously
            client = MqttClient.builder()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(currentBroker)
                .serverPort(1883)
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener(object : MqttClientConnectedListener {
                    override fun onConnected(context: MqttClientConnectedContext) {
                        viewModelScope.launch(Dispatchers.Main) {
                            _connectionStatus.value = "Connected"
                            appendLog("Connected successfully")
                            subscribeToTopic()
                            requestDeviceStatus(_selectedDevice.value)
                            startPollingLoop()
                        }
                    }
                })
                .addDisconnectedListener(object : MqttClientDisconnectedListener {
                    override fun onDisconnected(context: MqttClientDisconnectedContext) {
                        val cause = context.cause.message ?: "Unknown"
                        _connectionStatus.value = "Disconnected"
                        appendLog("Disconnected: $cause")
                        pollingJob?.cancel()
                    }
                })
                .buildAsync()

            // 3. Connect to the broker in the background. 
            client?.connect()?.whenComplete { _, throwable ->
                if (throwable != null) {
                    _connectionStatus.value = "Error"
                    appendLog("Error: ${throwable.message}")
                }
            }
        } catch (e: Exception) {
            _connectionStatus.value = "Error"
            appendLog("Exception during connection: ${e.message}")
        }
    }

    private fun subscribeToTopic() {
        val topic = _subscribeTopic.value
        client?.subscribeWith()
            ?.topicFilter(topic)
            ?.callback { publish ->
                val payloadStr = String(publish.payloadAsBytes)
                Log.d("MQTT_RAW_STATUS", "Raw payload received: $payloadStr")
                viewModelScope.launch(Dispatchers.Main) {
                    appendLog("RECEIVED: $payloadStr")
                    parseIncomingStatusPayload(payloadStr)
                }
            }
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable != null) {
                    appendLog("Subscribe Error: ${throwable.message}")
                } else {
                    appendLog("Subscribed to $topic")
                    requestDeviceStatus(_selectedDevice.value)
                }
            }

        client?.subscribeWith()
            ?.topicFilter("ac/logs")
            ?.callback { publish ->
                val payloadStr = String(publish.payloadAsBytes)
                appendLog("SERVER LOG: $payloadStr")
            }
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable == null) {
                    getServerLogs()
                }
            }

        client?.subscribeWith()
            ?.topicFilter("ac/devices/list")
            ?.callback { publish ->
                val payloadStr = String(publish.payloadAsBytes)
                viewModelScope.launch(Dispatchers.Main) {
                    appendLog("RECEIVED DEVICES: $payloadStr")
                    try {
                        val devicesList = mutableListOf<DeviceInfo>()

                        fun addDevice(id: String, name: String) {
                            val cleanId = id.trim()
                            val cleanName = name.trim()
                            if (cleanId.isNotEmpty() && devicesList.none { it.id == cleanId }) {
                                devicesList.add(DeviceInfo(id = cleanId, name = if (cleanName.isNotEmpty()) cleanName else "Device"))
                            }
                        }

                        try {
                            val normalizedJson = payloadStr.replace('\'', '"')
                            val trimmed = normalizedJson.trim()
                            if (trimmed.startsWith("[")) {
                                val jsonArray = JSONArray(trimmed)
                                for (i in 0 until jsonArray.length()) {
                                    val item = jsonArray.get(i)
                                    if (item is JSONObject) {
                                        val idVal = item.optString("id", item.optString("device_id", ""))
                                        val nameVal = item.optString("name", item.optString("device_name", ""))
                                        addDevice(idVal, nameVal)
                                    } else if (item is String) {
                                        addDevice(item, "Device")
                                    }
                                }
                            } else if (trimmed.startsWith("{")) {
                                val jsonObj = JSONObject(trimmed)
                                if (jsonObj.has("devices")) {
                                    val jsonArray = jsonObj.getJSONArray("devices")
                                    for (i in 0 until jsonArray.length()) {
                                        val item = jsonArray.get(i)
                                        if (item is JSONObject) {
                                            val idVal = item.optString("id", item.optString("device_id", ""))
                                            val nameVal = item.optString("name", item.optString("device_name", ""))
                                            addDevice(idVal, nameVal)
                                        }
                                    }
                                } else if (jsonObj.has("id") || jsonObj.has("device_id")) {
                                    val idVal = jsonObj.optString("id", jsonObj.optString("device_id", ""))
                                    val nameVal = jsonObj.optString("name", jsonObj.optString("device_name", ""))
                                    addDevice(idVal, nameVal)
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore JSON array exception, proceed to fallbacks
                        }

                        if (devicesList.isEmpty()) {
                            val objectRegex = """\{[^}]*\}""".toRegex()
                            val objMatches = objectRegex.findAll(payloadStr)
                            for (match in objMatches) {
                                val objStr = match.value
                                val idMatch = """['"]?(?:id|device_id)['"]?\s*:\s*['"]?([^'",}]+)['"]?""".toRegex(RegexOption.IGNORE_CASE).find(objStr)
                                val nameMatch = """['"]?(?:name|device_name)['"]?\s*:\s*['"]?([^'",}]+)['"]?""".toRegex(RegexOption.IGNORE_CASE).find(objStr)
                                val idVal = idMatch?.groupValues?.get(1)?.trim() ?: ""
                                val nameVal = nameMatch?.groupValues?.get(1)?.trim() ?: ""
                                if (idVal.isNotEmpty()) {
                                    addDevice(idVal, nameVal)
                                }
                            }
                        }

                        if (devicesList.isEmpty()) {
                            val idRegex = """['"]?(?:id|device_id)['"]?\s*:\s*['"]?([^'",}]+)['"]?""".toRegex(RegexOption.IGNORE_CASE)
                            val matches = idRegex.findAll(payloadStr)
                            for (match in matches) {
                                val idVal = match.groupValues[1].trim()
                                if (idVal.isNotEmpty()) {
                                    addDevice(idVal, "Device")
                                }
                            }
                        }

                        if (devicesList.isEmpty()) {
                            val parts = payloadStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            for (p in parts) {
                                val cleaned = p.replace("[", "").replace("]", "").replace("{", "").replace("}", "").replace("'", "").replace("\"", "").trim()
                                if (cleaned.isNotEmpty()) {
                                    addDevice(cleaned, "Device")
                                }
                            }
                        }

                        if (devicesList.none { it.id == "__ALL__" || it.id.equals("all", ignoreCase = true) }) {
                            devicesList.add(0, DeviceInfo(id = "__ALL__", name = "All Devices"))
                        }

                        if (devicesList.isNotEmpty()) {
                            _devices.value = devicesList
                            val deviceIds = devicesList.map { it.id }
                            if (_selectedDevice.value == null || _selectedDevice.value !in deviceIds) {
                                selectDevice(devicesList[0].id)
                            }
                        }
                    } catch(e: Exception) {
                        appendLog("Error parsing devices: ${e.message}")
                    }
                }
            }
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable == null) {
                    getDevices()
                }
            }

        client?.subscribeWith()
            ?.topicFilter("ac/users/list")
            ?.callback { publish ->
                val payloadStr = String(publish.payloadAsBytes)
                viewModelScope.launch(Dispatchers.Main) {
                    appendLog("RECEIVED USERS: $payloadStr")
                    try {
                        val usersList = mutableListOf<User>()
                        if (payloadStr.trim().startsWith("[")) {
                            val jsonArray = JSONArray(payloadStr.trim())
                            for (i in 0 until jsonArray.length()) {
                                val item = jsonArray.optJSONObject(i)
                                if (item != null) {
                                    val u = item.optString("username", "")
                                    val p = item.optString("password", "")
                                    if (u.isNotEmpty() && p.isNotEmpty()) {
                                        usersList.add(User(u, p))
                                    }
                                }
                            }
                        }
                        if (usersList.isNotEmpty()) {
                            _users.value = usersList
                        }
                    } catch(e: Exception) {
                        appendLog("Error parsing users: ${e.message}")
                    }
                }
            }
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable == null) {
                    getUsers()
                }
            }

        client?.subscribeWith()
            ?.topicFilter("ac/schedule/config")
            ?.callback { publish ->
                val payloadStr = String(publish.payloadAsBytes)
                Log.d("MQTT_SCHEDULE", "Received schedule config on ac/schedule/config: $payloadStr")
                viewModelScope.launch(Dispatchers.Main) {
                    appendLog("RECEIVED SCHEDULE: $payloadStr")
                    parseIncomingStatusPayload(payloadStr)
                }
            }
            ?.send()

        client?.subscribeWith()
            ?.topicFilter("ac/temp/config")
            ?.callback { publish ->
                val payloadStr = String(publish.payloadAsBytes)
                Log.d("MQTT_TEMP_CONFIG", "Received temp config on ac/temp/config: $payloadStr")
                viewModelScope.launch(Dispatchers.Main) {
                    appendLog("RECEIVED TEMP CONFIG: $payloadStr")
                    parseIncomingStatusPayload(payloadStr)
                }
            }
            ?.send()
    }

    private fun runOnMain(action: () -> Unit) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action()
            } else {
                viewModelScope.launch(Dispatchers.Main) {
                    action()
                }
            }
        } catch (_: Throwable) {
            action()
        }
    }

    internal fun parseIncomingStatusPayload(rawPayload: String) {
        val trimmed = rawPayload.trim()

        // 1. Handle Plain Text Payloads ("OFF", "ON", "TOGGLE", etc.)
        when (trimmed.uppercase()) {
            "OFF" -> {
                Log.d("MQTT_STATUS", "Parsed Plain-Text Payload: OFF")
                _powerState.value = false
                updateAllDevicesState(power = false)
                val currentSelected = _selectedDevice.value ?: ""
                if (currentSelected.isNotEmpty() && !currentSelected.equals("__ALL__", ignoreCase = true)) {
                    updateSingleDeviceState(currentSelected, power = false)
                }
                Log.d("MQTT_DIAGNOSTIC", "FORCE APPLIED PLAIN TEXT -> Power: false")
                return
            }
            "ON" -> {
                Log.d("MQTT_STATUS", "Parsed Plain-Text Payload: ON")
                _powerState.value = true
                updateAllDevicesState(power = true)
                val currentSelected = _selectedDevice.value ?: ""
                if (currentSelected.isNotEmpty() && !currentSelected.equals("__ALL__", ignoreCase = true)) {
                    updateSingleDeviceState(currentSelected, power = true)
                }
                Log.d("MQTT_DIAGNOSTIC", "FORCE APPLIED PLAIN TEXT -> Power: true")
                return
            }
        }

        // 2. Handle JSON Payloads safely
        try {
            if (trimmed.startsWith("{")) {
                val rootJson = JSONObject(trimmed)

                // Check for "devices" array
                if (rootJson.has("devices") && !rootJson.isNull("devices")) {
                    val devicesArray = rootJson.getJSONArray("devices")
                    for (i in 0 until devicesArray.length()) {
                        val deviceObj = devicesArray.optJSONObject(i) ?: continue
                        extractAndApplyDeviceState(deviceObj, i)
                    }
                } else {
                    val nextIdx = getNextBroadcastResponseIndex()
                    extractAndApplyDeviceState(rootJson, nextIdx)
                }
            } else if (trimmed.startsWith("[")) {
                val rootArray = JSONArray(trimmed)
                for (i in 0 until rootArray.length()) {
                    val deviceObj = rootArray.optJSONObject(i) ?: continue
                    extractAndApplyDeviceState(deviceObj, i)
                }
            } else {
                Log.w("MQTT_STATUS", "Unrecognized non-JSON payload format: $trimmed")
            }
        } catch (e: Exception) {
            Log.e("MQTT_STATUS", "Failed to parse payload: $trimmed", e)
        }
    }

    internal fun extractAndApplyDeviceState(json: JSONObject, index: Int = 0) {
        // Extract device_id (or id, mac, device) safely
        var rawId = ""
        try {
            val idKeys = listOf("device_id", "id", "mac", "device")
            for (k in idKeys) {
                if (json.has(k) && !json.isNull(k)) {
                    val s = json.optString(k, "").trim()
                    if (s.isNotEmpty()) {
                        rawId = s
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MQTT_PARSER", "Error extracting device_id: ${e.message}")
        }

        val invalidIdValues = listOf("", "null", "undefined", "none")
        val isValidId = rawId.isNotEmpty() && !invalidIdValues.contains(rawId.lowercase())
        val physicalDevices = _devices.value.filter { it.id != "__ALL__" && !it.id.equals("all", ignoreCase = true) }

        val payloadDeviceId = when {
            isValidId -> rawId
            physicalDevices.getOrNull(index)?.id?.isNotEmpty() == true -> physicalDevices[index].id
            else -> ""
        }

        // 1. Independent & Multi-Key Power Parsing (ONLY update if key exists)
        var parsedPower: Boolean? = null
        try {
            val powerKeys = listOf("power", "state", "power_state", "is_on", "status")
            for (key in powerKeys) {
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.opt(key)
                    if (raw is Boolean) {
                        parsedPower = raw
                        break
                    } else if (raw is Number) {
                        parsedPower = raw.toInt() != 0
                        break
                    } else {
                        val str = raw?.toString()?.lowercase()?.trim() ?: ""
                        if (str in listOf("true", "on", "1")) {
                            parsedPower = true
                            break
                        } else if (str in listOf("false", "off", "0")) {
                            parsedPower = false
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MQTT_PARSER", "Power parsing error: ${e.message}")
        }

        // 2. Independent & Multi-Key Temperature Parsing (ONLY update if key exists & > 0)
        var parsedTemp: Int? = null
        try {
            for (key in listOf("temperature", "temp", "target_temp", "set_temp")) {
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.opt(key)
                    val d = raw?.toString()?.toDoubleOrNull()
                    if (d != null && d > 0) {
                        parsedTemp = d.toInt()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MQTT_PARSER", "Temp parsing error: ${e.message}")
        }

        // 3. Independent & Multi-Key Mode Parsing (ONLY update if key exists)
        var parsedMode: String? = null
        try {
            val modeKeys = listOf("mode", "ac_mode", "operation_mode", "work_mode", "mede")
            for (key in modeKeys) {
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.opt(key)
                    if (raw is Number) {
                        parsedMode = when (raw.toInt()) {
                            0 -> "cool"
                            1 -> "heat"
                            2 -> "dry"
                            3 -> "fan"
                            4 -> "auto"
                            else -> "cool"
                        }
                        break
                    } else if (raw != null) {
                        val str = raw.toString().lowercase().trim()
                        val num = str.toIntOrNull()
                        if (num != null) {
                            parsedMode = when (num) {
                                0 -> "cool"
                                1 -> "heat"
                                2 -> "dry"
                                3 -> "fan"
                                4 -> "auto"
                                else -> "cool"
                            }
                            break
                        } else if (str.isNotEmpty()) {
                            parsedMode = str
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MQTT_PARSER", "Mode parsing error: ${e.message}")
        }

        // 4. Independent & Multi-Key Fan Speed Parsing (CRITICAL: ONLY update if key explicitly exists)
        var parsedFan: String? = null
        try {
            val fanKeys = listOf("fan", "fan_speed", "speed", "fan_level")
            for (key in fanKeys) {
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.opt(key)
                    if (raw is Number) {
                        parsedFan = when (raw.toInt()) {
                            0 -> "auto"
                            1 -> "low"
                            2 -> "medium"
                            3 -> "high"
                            4 -> "turbo"
                            else -> if (raw.toInt() <= 0) "auto" else if (raw.toInt() == 1) "low" else if (raw.toInt() == 2) "medium" else "high"
                        }
                        break
                    } else if (raw != null) {
                        val str = raw.toString().lowercase().trim()
                        val num = str.toIntOrNull()
                        if (num != null) {
                            parsedFan = when (num) {
                                0 -> "auto"
                                1 -> "low"
                                2 -> "medium"
                                3 -> "high"
                                4 -> "turbo"
                                else -> if (num <= 0) "auto" else if (num == 1) "low" else if (num == 2) "medium" else "high"
                            }
                            break
                        } else if (str.isNotEmpty()) {
                            parsedFan = when (str) {
                                "med", "mid", "middle" -> "medium"
                                "min" -> "low"
                                "max" -> "high"
                                else -> str
                            }
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MQTT_PARSER", "Fan parsing error: ${e.message}")
        }

        // 5. Independent & Multi-Key Swing Parsing (CRITICAL: ONLY update if key explicitly exists)
        var parsedSwing: Boolean? = null
        var parsedSwingH: Boolean? = null
        var parsedSwingV: Boolean? = null
        try {
            val swingKeys = listOf("swing", "swing_mode", "swing_enabled", "is_swing")
            for (key in swingKeys) {
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.opt(key)
                    if (raw is Boolean) {
                        parsedSwing = raw
                        break
                    } else if (raw is Number) {
                        parsedSwing = raw.toInt() != 0
                        break
                    } else if (raw != null) {
                        val str = raw.toString().lowercase().trim()
                        val num = str.toIntOrNull()
                        if (num != null) {
                            parsedSwing = num != 0
                            break
                        } else if (str in listOf("on", "auto", "vertical", "horizontal", "true", "1")) {
                            parsedSwing = true
                            if (str == "horizontal") parsedSwingH = true
                            if (str == "vertical") parsedSwingV = true
                            break
                        } else if (str in listOf("off", "false", "0")) {
                            parsedSwing = false
                            break
                        }
                    }
                }
            }

            // Check swing_horizontal explicitly if present
            if (json.has("swing_horizontal") && !json.isNull("swing_horizontal")) {
                val raw = json.opt("swing_horizontal")
                if (raw is Boolean) {
                    parsedSwingH = raw
                } else if (raw is Number) {
                    parsedSwingH = raw.toInt() != 0
                } else if (raw != null) {
                    val str = raw.toString().lowercase().trim()
                    if (str in listOf("true", "on", "1", "auto")) parsedSwingH = true
                    else if (str in listOf("false", "off", "0")) parsedSwingH = false
                }
            }

            // Check swing_vertical explicitly if present
            if (json.has("swing_vertical") && !json.isNull("swing_vertical")) {
                val raw = json.opt("swing_vertical")
                if (raw is Boolean) {
                    parsedSwingV = raw
                } else if (raw is Number) {
                    parsedSwingV = raw.toInt() != 0
                } else if (raw != null) {
                    val str = raw.toString().lowercase().trim()
                    if (str in listOf("true", "on", "1", "auto")) parsedSwingV = true
                    else if (str in listOf("false", "off", "0")) parsedSwingV = false
                }
            }

            // Propagate general swing to horizontal / vertical if not specifically overridden
            if (parsedSwing != null) {
                if (parsedSwingH == null) parsedSwingH = parsedSwing
                if (parsedSwingV == null) parsedSwingV = parsedSwing
            } else if (parsedSwingH != null || parsedSwingV != null) {
                parsedSwing = (parsedSwingH == true) || (parsedSwingV == true)
            }
        } catch (e: Exception) {
            Log.e("MQTT_PARSER", "Swing parsing error: ${e.message}")
        }

        // 7. Independent & Bulletproof Schedule On Parsing
        var parsedSchedOn: String? = null
        try {
            for (key in listOf("schedule_on", "on", "sched_on", "timer_on")) {
                if (json.has(key) && !json.isNull(key)) {
                    val str = json.optString(key, "").trim()
                    if (str.isNotEmpty()) {
                        parsedSchedOn = str
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MQTT_PARSER", "SchedOn parsing error: ${e.message}")
        }

        // 8. Independent & Bulletproof Schedule Off Parsing
        var parsedSchedOff: String? = null
        try {
            for (key in listOf("schedule_off", "off", "sched_off", "timer_off")) {
                if (json.has(key) && !json.isNull(key)) {
                    val str = json.optString(key, "").trim()
                    if (str.isNotEmpty()) {
                        parsedSchedOff = str
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MQTT_PARSER", "SchedOff parsing error: ${e.message}")
        }

        // 9. Independent & Bulletproof Schedule Enabled Parsing
        var parsedSchedEnable: Boolean? = null
        try {
            for (key in listOf("schedule_enabled", "enabled", "sched_enabled", "timer_enabled")) {
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.opt(key)
                    if (raw is Boolean) {
                        parsedSchedEnable = raw
                        break
                    } else {
                        val str = raw?.toString()?.lowercase()?.trim() ?: ""
                        if (str in listOf("true", "on", "1")) {
                            parsedSchedEnable = true
                            break
                        } else if (str in listOf("false", "off", "0")) {
                            parsedSchedEnable = false
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MQTT_PARSER", "SchedEnable parsing error: ${e.message}")
        }

        // 10. Independent & Bulletproof Temp Diff Parsing
        var parsedTempDiff: Int? = null
        try {
            for (key in listOf("temp_diff", "diff", "tempDiff", "hysteresis")) {
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.opt(key)
                    if (raw is Number) {
                        parsedTempDiff = raw.toInt().coerceIn(1, 5)
                        break
                    } else if (raw is String) {
                        val num = raw.trim().toIntOrNull()
                        if (num != null) {
                            parsedTempDiff = num.coerceIn(1, 5)
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MQTT_PARSER", "TempDiff parsing error: ${e.message}")
        }

        // 11. Independent & Bulletproof Auto Mode Parsing
        var parsedAutoMode: Boolean? = null
        try {
            for (key in listOf("auto_mode", "autoMode", "auto_control")) {
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.opt(key)
                    if (raw is Boolean) {
                        parsedAutoMode = raw
                        break
                    } else {
                        val str = raw?.toString()?.lowercase()?.trim() ?: ""
                        if (str in listOf("true", "on", "1")) {
                            parsedAutoMode = true
                            break
                        } else if (str in listOf("false", "off", "0")) {
                            parsedAutoMode = false
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MQTT_PARSER", "AutoMode parsing error: ${e.message}")
        }

        // Always update Map Cache for payloadDeviceId (Partial updates retain prior states)
        val isPayloadBroadcast = payloadDeviceId.isEmpty() || payloadDeviceId == "__ALL__" || payloadDeviceId.equals("all", ignoreCase = true)
        if (isPayloadBroadcast) {
            updateAllDevicesState(
                power = parsedPower,
                temp = parsedTemp,
                mode = parsedMode,
                fan = parsedFan,
                swing = parsedSwing,
                swingH = parsedSwingH,
                swingV = parsedSwingV,
                schedOn = parsedSchedOn,
                schedOff = parsedSchedOff,
                schedEnable = parsedSchedEnable,
                tempDiff = parsedTempDiff,
                autoMode = parsedAutoMode
            )
        } else {
            updateSingleDeviceState(
                payloadDeviceId,
                power = parsedPower,
                temp = parsedTemp,
                mode = parsedMode,
                fan = parsedFan,
                swing = parsedSwing,
                swingH = parsedSwingH,
                swingV = parsedSwingV,
                schedOn = parsedSchedOn,
                schedOff = parsedSchedOff,
                schedEnable = parsedSchedEnable,
                tempDiff = parsedTempDiff,
                autoMode = parsedAutoMode
            )
        }

        // Determine if UI StateFlows should be updated directly:
        // IF selectedDevice == payloadDeviceId: Immediately update StateFlow values from the updated cached state.
        // IF selectedDevice == "__ALL__": Keep the overall/last active state updated in UI.
        // IF status belongs to a different device: Update deviceStatusMap ONLY and DO NOT change the currently displayed UI values.
        val currentSelected = _selectedDevice.value ?: ""
        val isViewingAll = currentSelected.isEmpty() || currentSelected == "__ALL__" || currentSelected.equals("all", ignoreCase = true)
        val isThisSpecificDevice = payloadDeviceId.isNotEmpty() && payloadDeviceId.equals(currentSelected, ignoreCase = true)

        val isTargetDevice = isThisSpecificDevice || isViewingAll || (isPayloadBroadcast && currentSelected.isNotEmpty())

        if (isTargetDevice) {
            val cached = if (payloadDeviceId.isNotEmpty()) deviceStatusMap[payloadDeviceId] else null
            val applyUiUpdates = {
                if (parsedPower != null) _powerState.value = parsedPower
                else if (cached?.power != null && isThisSpecificDevice) _powerState.value = cached.power!!

                if (parsedTemp != null && parsedTemp > 0) _temperature.value = parsedTemp
                else if (cached?.temp != null && cached.temp!! > 0 && isThisSpecificDevice) _temperature.value = cached.temp

                if (parsedMode != null) _mode.value = parsedMode
                else if (cached?.mode != null && isThisSpecificDevice) _mode.value = cached.mode!!

                if (parsedFan != null) _fan.value = parsedFan
                else if (cached?.fan != null && isThisSpecificDevice) _fan.value = cached.fan!!

                if (parsedSwing != null) _swing.value = parsedSwing
                else if (cached?.swing != null && isThisSpecificDevice) _swing.value = cached.swing!!

                if (parsedSwingH != null) _swingHorizontal.value = parsedSwingH
                else if (cached?.swingHorizontal != null && isThisSpecificDevice) _swingHorizontal.value = cached.swingHorizontal!!

                if (parsedSwingV != null) _swingVertical.value = parsedSwingV
                else if (cached?.swingVertical != null && isThisSpecificDevice) _swingVertical.value = cached.swingVertical!!

                if (parsedSchedOn != null) _scheduleOnTime.value = parsedSchedOn
                else if (cached?.scheduleOnTime != null && isThisSpecificDevice) _scheduleOnTime.value = cached.scheduleOnTime!!

                if (parsedSchedOff != null) _scheduleOffTime.value = parsedSchedOff
                else if (cached?.scheduleOffTime != null && isThisSpecificDevice) _scheduleOffTime.value = cached.scheduleOffTime!!

                if (parsedSchedEnable != null) _scheduleEnabled.value = parsedSchedEnable
                else if (cached?.scheduleEnabled != null && isThisSpecificDevice) _scheduleEnabled.value = cached.scheduleEnabled!!

                if (parsedTempDiff != null) _tempDiff.value = parsedTempDiff
                else if (cached?.tempDiff != null && isThisSpecificDevice) _tempDiff.value = cached.tempDiff!!

                if (parsedAutoMode != null) _autoMode.value = parsedAutoMode
                else if (cached?.autoMode != null && isThisSpecificDevice) _autoMode.value = cached.autoMode!!
            }
            runOnMain(applyUiUpdates)
        }

        Log.d("MQTT_DIAGNOSTIC", "STATUS APPLIED -> device: '$payloadDeviceId', currentSelected: '$currentSelected', isViewingAll: $isViewingAll, Power: $parsedPower, Temp: $parsedTemp, Mode: $parsedMode, Fan: $parsedFan, Swing: $parsedSwing")
    }
    
    fun requestDeviceStatus(deviceId: String? = _selectedDevice.value) {
        broadcastResponseIndex = 0
        val targetId = if (deviceId.isNullOrEmpty()) "__ALL__" else deviceId
        val payloadStr = JSONObject().apply {
            put("device_id", targetId)
        }.toString()
        val topic = "ac/status/get"
        val payload = payloadStr.toByteArray()
        
        appendLog("PUBLISHED: $payloadStr to $topic")
        Log.d("MQTT_STATUS_GET", "Published status request: $payloadStr to $topic")
        
        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith().topic(topic).payload(payload).qos(MqttQos.AT_LEAST_ONCE).send()
                }
            }
        }
    }

    fun fetchStatus(deviceId: String? = _selectedDevice.value) {
        requestDeviceStatus(deviceId)
    }
    
    fun syncStatus(deviceId: String? = _selectedDevice.value) {
        requestDeviceStatus(deviceId)
    }

    fun getServerLogs() {
        val topic = "ac/logs/get"
        val payloadStr = ""
        val payload = payloadStr.toByteArray()
        
        appendLog("PUBLISHED: (Empty payload to $topic)")
        
        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith().topic(topic).payload(payload).qos(MqttQos.AT_LEAST_ONCE).send()
                }
            }
        }
    }
    
    fun getDevices() {
        val topic = "ac/devices/get"
        val payloadStr = ""
        val payload = payloadStr.toByteArray()
        
        appendLog("PUBLISHED: (Empty payload to $topic)")
        
        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith().topic(topic).payload(payload).qos(MqttQos.AT_LEAST_ONCE).send()
                }
            }
        }
    }
    
    fun getUsers() {
        val topic = "ac/users/get"
        val payloadStr = ""
        val payload = payloadStr.toByteArray()
        
        appendLog("PUBLISHED: (Empty payload to $topic)")
        
        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith().topic(topic).payload(payload).qos(MqttQos.AT_LEAST_ONCE).send()
                }
            }
        }
    }
    
    fun selectDevice(deviceId: String) {
        _selectedDevice.value = deviceId
        if (deviceId != "__ALL__" && !deviceId.equals("all", ignoreCase = true)) {
            val devState = deviceStatusMap[deviceId]
            if (devState != null) {
                devState.power?.let { _powerState.value = it }
                devState.temp?.let { _temperature.value = it }
                devState.mode?.let { _mode.value = it }
                devState.fan?.let { _fan.value = it }
                devState.swing?.let { _swing.value = it }
                devState.swingHorizontal?.let { _swingHorizontal.value = it }
                devState.swingVertical?.let { _swingVertical.value = it }
                devState.scheduleOnTime?.let { _scheduleOnTime.value = it }
                devState.scheduleOffTime?.let { _scheduleOffTime.value = it }
                devState.scheduleEnabled?.let { _scheduleEnabled.value = it }
                devState.tempDiff?.let { _tempDiff.value = it }
                devState.autoMode?.let { _autoMode.value = it }
            }
        }
        requestDeviceStatus(deviceId)
    }

    fun onSwingToggled(turnOn: Boolean = !_swing.value) {
        publishSwingCommand(turnOn)
    }

    fun onFanSpeedChanged(newSpeed: String) {
        publishFanCommand(newSpeed)
    }

    fun onModeChanged(newMode: String) {
        publishModeCommand(newMode)
    }

    fun onPowerToggled(turnOn: Boolean = !_powerState.value) {
        publishCommand(turnOn)
    }

    fun onTemperatureChanged(temp: Int) {
        publishTemperatureCommand(temp)
    }

    fun publishCommand(turnOn: Boolean) {
        val currentTopic = _topic.value
        val deviceId = _selectedDevice.value ?: return
        val isAll = deviceId == "__ALL__" || deviceId.equals("all", ignoreCase = true)
        
        runOnMain {
            _powerState.value = turnOn
            if (isAll) {
                updateAllDevicesState(power = turnOn)
            } else {
                updateSingleDeviceState(deviceId, power = turnOn)
            }
        }
        val payloadStr = if (turnOn) "{\"device_id\":\"$deviceId\", \"power\":true}" else "{\"device_id\":\"$deviceId\", \"power\":false}"
        val payload = payloadStr.toByteArray()

        appendLog("PUBLISHED: $payloadStr")

        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith()
                        .topic(currentTopic)
                        .payload(payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                        .whenComplete { _, throwable ->
                            if (throwable != null) {
                                appendLog("Publish Error: ${throwable.message}")
                            }
                        }
                } else {
                    appendLog("Warning: Cannot publish, not connected.")
                }
            } ?: appendLog("Warning: Client is null.")
        }
    }

    fun publishTemperatureCommand(temp: Int) {
        val currentTopic = _topic.value
        val deviceId = _selectedDevice.value ?: return
        val isAll = deviceId == "__ALL__" || deviceId.equals("all", ignoreCase = true)

        runOnMain {
            _temperature.value = temp
            _powerState.value = true
            if (isAll) {
                updateAllDevicesState(power = true, temp = temp)
            } else {
                updateSingleDeviceState(deviceId, power = true, temp = temp)
            }
        }
        val payloadStr = "{\"device_id\": \"$deviceId\", \"temp\": $temp, \"power\":true}"
        val payload = payloadStr.toByteArray()

        appendLog("PUBLISHED: $payloadStr")

        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith()
                        .topic(currentTopic)
                        .payload(payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                        .whenComplete { _, throwable ->
                            if (throwable != null) {
                                appendLog("Publish Error: ${throwable.message}")
                            }
                        }
                } else {
                    appendLog("Warning: Cannot publish, not connected.")
                }
            } ?: appendLog("Warning: Client is null.")
        }
    }

    fun publishModeCommand(mode: String) {
        val currentTopic = _topic.value
        val deviceId = _selectedDevice.value ?: return
        val cleanMode = mode.lowercase().trim()
        val isAll = deviceId == "__ALL__" || deviceId.equals("all", ignoreCase = true)

        runOnMain {
            _mode.value = cleanMode
            _powerState.value = true
            if (isAll) {
                updateAllDevicesState(power = true, mode = cleanMode)
            } else {
                updateSingleDeviceState(deviceId, power = true, mode = cleanMode)
            }
        }
        val payloadStr = "{\"device_id\": \"$deviceId\", \"mode\": \"$cleanMode\", \"power\":true}"
        val payload = payloadStr.toByteArray()

        appendLog("PUBLISHED: $payloadStr")

        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith()
                        .topic(currentTopic)
                        .payload(payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                        .whenComplete { _, throwable ->
                            if (throwable != null) {
                                appendLog("Publish Error: ${throwable.message}")
                            }
                        }
                } else {
                    appendLog("Warning: Cannot publish, not connected.")
                }
            } ?: appendLog("Warning: Client is null.")
        }
    }

    fun publishFanCommand(fan: String) {
        val currentTopic = _topic.value
        val deviceId = _selectedDevice.value ?: return
        val cleanFan = fan.lowercase().trim()
        val isAll = deviceId == "__ALL__" || deviceId.equals("all", ignoreCase = true)

        runOnMain {
            _fan.value = cleanFan
            _powerState.value = true
            if (isAll) {
                updateAllDevicesState(power = true, fan = cleanFan)
            } else {
                updateSingleDeviceState(deviceId, power = true, fan = cleanFan)
            }
        }
        val payloadStr = "{\"device_id\": \"$deviceId\", \"fan\": \"$cleanFan\", \"power\":true}"
        val payload = payloadStr.toByteArray()

        appendLog("PUBLISHED: $payloadStr")

        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith()
                        .topic(currentTopic)
                        .payload(payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                        .whenComplete { _, throwable ->
                            if (throwable != null) {
                                appendLog("Publish Error: ${throwable.message}")
                            }
                        }
                } else {
                    appendLog("Warning: Cannot publish, not connected.")
                }
            } ?: appendLog("Warning: Client is null.")
        }
    }

    fun publishSwingCommand(turnOn: Boolean) {
        val currentTopic = _topic.value
        val deviceId = _selectedDevice.value ?: return
        val isAll = deviceId == "__ALL__" || deviceId.equals("all", ignoreCase = true)

        runOnMain {
            _swing.value = turnOn
            _swingHorizontal.value = turnOn
            _swingVertical.value = turnOn
            _powerState.value = true
            if (isAll) {
                updateAllDevicesState(power = true, swing = turnOn, swingH = turnOn, swingV = turnOn)
            } else {
                updateSingleDeviceState(deviceId, power = true, swing = turnOn, swingH = turnOn, swingV = turnOn)
            }
        }
        val payloadStr = "{\"device_id\": \"$deviceId\", \"swing\": $turnOn, \"power\":true}"
        val payload = payloadStr.toByteArray()

        appendLog("PUBLISHED: $payloadStr")

        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith()
                        .topic(currentTopic)
                        .payload(payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                        .whenComplete { _, throwable ->
                            if (throwable != null) {
                                appendLog("Publish Error: ${throwable.message}")
                            }
                        }
                } else {
                    appendLog("Warning: Cannot publish, not connected.")
                }
            } ?: appendLog("Warning: Client is null.")
        }
    }

    fun publishSwingHorizontalCommand(turnOn: Boolean) {
        val currentTopic = _topic.value
        val deviceId = _selectedDevice.value ?: return
        val isAll = deviceId == "__ALL__" || deviceId.equals("all", ignoreCase = true)

        runOnMain {
            _swingHorizontal.value = turnOn
            _swing.value = turnOn || _swingVertical.value
            _powerState.value = true
            if (isAll) {
                updateAllDevicesState(power = true, swingH = turnOn, swing = turnOn || _swingVertical.value)
            } else {
                updateSingleDeviceState(deviceId, power = true, swingH = turnOn, swing = turnOn || _swingVertical.value)
            }
        }
        val payloadStr = "{\"device_id\": \"$deviceId\", \"power\":true, \"swing_horizontal\": $turnOn}"
        val payload = payloadStr.toByteArray()

        appendLog("PUBLISHED: $payloadStr")

        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith()
                        .topic(currentTopic)
                        .payload(payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                        .whenComplete { _, throwable ->
                            if (throwable != null) {
                                appendLog("Publish Error: ${throwable.message}")
                            }
                        }
                } else {
                    appendLog("Warning: Cannot publish, not connected.")
                }
            } ?: appendLog("Warning: Client is null.")
        }
    }

    fun publishSwingVerticalCommand(turnOn: Boolean) {
        val currentTopic = _topic.value
        val deviceId = _selectedDevice.value ?: return
        val isAll = deviceId == "__ALL__" || deviceId.equals("all", ignoreCase = true)

        runOnMain {
            _swingVertical.value = turnOn
            _swing.value = turnOn || _swingHorizontal.value
            _powerState.value = true
            if (isAll) {
                updateAllDevicesState(power = true, swingV = turnOn, swing = turnOn || _swingHorizontal.value)
            } else {
                updateSingleDeviceState(deviceId, power = true, swingV = turnOn, swing = turnOn || _swingHorizontal.value)
            }
        }
        val payloadStr = "{\"device_id\": \"$deviceId\", \"power\":true, \"swing_vertical\": $turnOn}"
        val payload = payloadStr.toByteArray()

        appendLog("PUBLISHED: $payloadStr")

        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith()
                        .topic(currentTopic)
                        .payload(payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                        .whenComplete { _, throwable ->
                            if (throwable != null) {
                                appendLog("Publish Error: ${throwable.message}")
                            }
                        }
                } else {
                    appendLog("Warning: Cannot publish, not connected.")
                }
            } ?: appendLog("Warning: Client is null.")
        }
    }

    fun sendScheduleConfig(
        enabled: Boolean,
        onTimeFormatted24H: String,
        offTimeFormatted24H: String
    ) {
        val selected = _selectedDevice.value
        val targetId = if (selected.isNullOrEmpty()) "__ALL__" else selected
        val isAll = targetId == "__ALL__" || targetId.equals("all", ignoreCase = true)

        runOnMain {
            _scheduleEnabled.value = enabled
            _scheduleOnTime.value = onTimeFormatted24H
            _scheduleOffTime.value = offTimeFormatted24H
            if (isAll) {
                updateAllDevicesState(schedOn = onTimeFormatted24H, schedOff = offTimeFormatted24H, schedEnable = enabled)
            } else {
                updateSingleDeviceState(targetId, schedOn = onTimeFormatted24H, schedOff = offTimeFormatted24H, schedEnable = enabled)
            }
        }

        val scheduleTopic = "ac/schedule/config"
        val payloadObj = JSONObject().apply {
            put("device_id", targetId)
            put("schedule_enabled", enabled)
            put("schedule_on", onTimeFormatted24H)
            put("schedule_off", offTimeFormatted24H)
            put("enabled", enabled)
            put("on", onTimeFormatted24H)
            put("off", offTimeFormatted24H)
        }
        val payloadStr = payloadObj.toString()
        val payload = payloadStr.toByteArray()

        appendLog("PUBLISHED: $payloadStr")
        Log.d("MQTT_SCHEDULE", "Published to $scheduleTopic: $payloadStr")

        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith()
                        .topic(scheduleTopic)
                        .payload(payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                        .whenComplete { _, throwable ->
                            if (throwable != null) {
                                appendLog("Publish Error: ${throwable.message}")
                            }
                        }
                } else {
                    appendLog("Warning: Cannot publish, not connected.")
                }
            } ?: appendLog("Warning: Client is null.")
        }
    }

    fun publishSaveScheduleTime(onTime24: String, offTime24: String) {
        sendScheduleConfig(
            enabled = _scheduleEnabled.value,
            onTimeFormatted24H = onTime24,
            offTimeFormatted24H = offTime24
        )
    }

    fun publishToggleSchedule(enabled: Boolean, onTime24: String, offTime24: String) {
        sendScheduleConfig(
            enabled = enabled,
            onTimeFormatted24H = onTime24,
            offTimeFormatted24H = offTime24
        )
    }

    fun publishTempDiff(tempDiff: Int) {
        val selected = _selectedDevice.value
        val targetId = if (selected.isNullOrEmpty()) "__ALL__" else selected
        val isAll = targetId == "__ALL__" || targetId.equals("all", ignoreCase = true)
        val clampedDiff = tempDiff.coerceIn(1, 5)

        runOnMain {
            _tempDiff.value = clampedDiff
            if (isAll) {
                updateAllDevicesState(tempDiff = clampedDiff)
            } else {
                updateSingleDeviceState(targetId, tempDiff = clampedDiff)
            }
        }

        val topic = "ac/temp/config"
        val payload = JSONObject().apply {
            put("device_id", targetId)
            put("temp_diff", clampedDiff)
        }.toString()
        val payloadBytes = payload.toByteArray()

        appendLog("PUBLISHED: $payload")
        Log.d("MQTT_TEMP_CONFIG", "Published to $topic: $payload")

        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith()
                        .topic(topic)
                        .payload(payloadBytes)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                        .whenComplete { _, throwable ->
                            if (throwable != null) {
                                appendLog("Publish Error: ${throwable.message}")
                            }
                        }
                } else {
                    appendLog("Warning: Cannot publish, not connected.")
                }
            } ?: appendLog("Warning: Client is null.")
        }
    }

    fun publishAutoMode(enabled: Boolean) {
        val selected = _selectedDevice.value
        val targetId = if (selected.isNullOrEmpty()) "__ALL__" else selected
        val isAll = targetId == "__ALL__" || targetId.equals("all", ignoreCase = true)

        runOnMain {
            _autoMode.value = enabled
            if (isAll) {
                updateAllDevicesState(autoMode = enabled)
            } else {
                updateSingleDeviceState(targetId, autoMode = enabled)
            }
        }

        val topic = "ac/temp/config"
        val payload = JSONObject().apply {
            put("device_id", targetId)
            put("auto_mode", enabled)
        }.toString()
        val payloadBytes = payload.toByteArray()

        appendLog("PUBLISHED: $payload")
        Log.d("MQTT_TEMP_CONFIG", "Published to $topic: $payload")

        viewModelScope.launch(Dispatchers.IO) {
            client?.let {
                if (it.state.isConnected) {
                    it.publishWith()
                        .topic(topic)
                        .payload(payloadBytes)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                        .whenComplete { _, throwable ->
                            if (throwable != null) {
                                appendLog("Publish Error: ${throwable.message}")
                            }
                        }
                } else {
                    appendLog("Warning: Cannot publish, not connected.")
                }
            } ?: appendLog("Warning: Client is null.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        client?.disconnect()
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MqttViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedPref = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val isLoggedIn = sharedPref.getBoolean("is_logged_in", false)
        val startDest = if (isLoggedIn) "main_screen" else "login_screen"

        enableEdgeToEdge()
        setContent {
            val isDarkMode by viewModel.isDarkMode.collectAsState()
            MyApplicationTheme(darkTheme = isDarkMode) {
                val navController = rememberNavController()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = startDest,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("login_screen") {
                            LoginScreen(
                                viewModel = viewModel,
                                navController = navController,
                                sharedPref = sharedPref
                            )
                        }
                        composable("main_screen") {
                            AcControllerScreen(
                                viewModel = viewModel,
                                navController = navController,
                                sharedPref = sharedPref
                            )
                        }
                        composable("connection_settings_screen") {
                            ConnectionSettingsScreen(
                                viewModel = viewModel,
                                navController = navController
                            )
                        }
                        composable("logs_screen") {
                            LogsScreen(
                                viewModel = viewModel,
                                navController = navController
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcControllerScreen(viewModel: MqttViewModel, navController: NavController, sharedPref: android.content.SharedPreferences, modifier: Modifier = Modifier) {
    val status by viewModel.connectionStatus.collectAsState()
    val brokerUrl by viewModel.brokerUrl.collectAsState()
    val topic by viewModel.topic.collectAsState()
    val subscribeTopic by viewModel.subscribeTopic.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val serverTemperature by viewModel.temperature.collectAsState()
    val serverMode by viewModel.mode.collectAsState()
    val serverFan by viewModel.fan.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val isSwingOn by viewModel.swing.collectAsState()
    val isSwingHorizontalOn by viewModel.swingHorizontal.collectAsState()
    val isSwingVerticalOn by viewModel.swingVertical.collectAsState()
    val isPowerOn by viewModel.powerState.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val loggedInUsername = sharedPref.getString("logged_in_username", "") ?: ""

    val listState = rememberLazyListState()
    val mainScrollState = rememberScrollState()

    // Auto-scroll to the bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.scrollToItem((logs.size - 1).coerceAtLeast(0))
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ModalDrawerSheet {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(24.dp)
                        ) {
                            // Header Section
                            Text(
                                text = "Logged in as:",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (loggedInUsername.isNotEmpty()) loggedInUsername else "User",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

                            // Menu Option 1 - Server Logs
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Dns, contentDescription = "Server Logs") },
                                label = { Text("Server Logs") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    navController.navigate("logs_screen")
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Menu Option 2 - Broker Settings
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = "Broker Settings") },
                                label = { Text("Broker Settings") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    navController.navigate("connection_settings_screen")
                                }
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            // Menu Option 3 - Logout
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Logout, contentDescription = "Logout") },
                                label = { Text("Logout") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    sharedPref.edit().clear().apply()
                                    navController.navigate("login_screen") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        text = "AC Controller",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) {
                                        val statusColor = when (status) {
                                            "Connected" -> Color(0xFF4CAF50)
                                            "Connecting..." -> Color.Gray
                                            else -> Color.Red
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(statusColor)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = status,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = statusColor
                                        )
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { viewModel.toggleDarkMode() }) {
                                    Icon(
                                        imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                        contentDescription = if (isDarkMode) "Switch to Light Mode" else "Switch to Dark Mode"
                                    )
                                }
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(
                                        imageVector = Icons.Default.Menu,
                                        contentDescription = "Open Navigation Menu"
                                    )
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(mainScrollState)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val selectedDeviceInfo = devices.find { it.id == selectedDevice }
                        val selectedDisplayText = selectedDeviceInfo?.displayText ?: (selectedDevice ?: "Select Device")
                        val deviceOptions = if (devices.isNotEmpty()) devices.map { it.displayText } else listOf(selectedDisplayText)

                        DropdownSelector(
                            label = "Device Selector",
                            options = deviceOptions,
                            selectedOption = selectedDisplayText,
                            onOptionSelected = { chosenDisplayLabel ->
                                val matchedDevice = devices.find { it.displayText == chosenDisplayLabel }
                                val chosenId = matchedDevice?.id ?: chosenDisplayLabel
                                viewModel.selectDevice(chosenId)
                            },
                            onExpandedChange = { expanded ->
                                viewModel.isUserInteracting = expanded
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        // Temperature Circle
                        var isDragging by remember { mutableStateOf(false) }
                        var currentTemperature by remember { mutableStateOf(serverTemperature) }
                        
                        LaunchedEffect(serverTemperature) {
                            if (!isDragging) {
                                currentTemperature = serverTemperature
                            }
                        }
                        
                        CircularTemperatureDial(
                            temperature = currentTemperature,
                            onTemperatureChange = { currentTemperature = it },
                            onTemperatureChangeFinished = {
                                currentTemperature?.let { viewModel.publishTemperatureCommand(it) }
                            },
                            onDragStart = { 
                                isDragging = true 
                                viewModel.isUserInteracting = true
                            },
                            onDragEnd = { 
                                isDragging = false 
                                viewModel.isUserInteracting = false
                            },
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        // Spinners
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            val modeOptions = listOf("heat", "cool", "dry", "auto", "fan")
                            var selectedMode by remember(serverMode) { mutableStateOf(serverMode) } 
                            
                            val fanOptions = listOf("auto", "low", "medium", "high")
                            var selectedFan by remember(serverFan) { mutableStateOf(serverFan) } 
                            
                            DropdownSelector(
                                label = "Mode",
                                options = modeOptions,
                                selectedOption = selectedMode,
                                onOptionSelected = { 
                                    selectedMode = it
                                    viewModel.onModeChanged(it)
                                },
                                onExpandedChange = { expanded ->
                                    viewModel.isUserInteracting = expanded
                                },
                                modifier = Modifier.weight(1f)
                            )
                            
                            DropdownSelector(
                                label = "Fan",
                                options = fanOptions,
                                selectedOption = selectedFan,
                                onOptionSelected = {
                                    selectedFan = it
                                    viewModel.onFanSpeedChanged(it)
                                },
                                onExpandedChange = { expanded ->
                                    viewModel.isUserInteracting = expanded
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Swing Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Button(
                                onClick = { viewModel.publishSwingHorizontalCommand(!isSwingHorizontalOn) },
                                enabled = status == "Connected",
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = if (isSwingHorizontalOn) "OFF" else "swing_horizontal",
                                    fontSize = 14.sp
                                )
                            }

                            Button(
                                onClick = { viewModel.publishSwingVerticalCommand(!isSwingVerticalOn) },
                                enabled = status == "Connected",
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = if (isSwingVerticalOn) "OFF" else "swing_vertical",
                                    fontSize = 14.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Time Scheduler Section
                        TimeSchedulerCard(
                            viewModel = viewModel,
                            isConnected = status == "Connected",
                            selectedDevice = selectedDevice
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Auto-Control Hysteresis Section
                        AutoControlHysteresisCard(
                            viewModel = viewModel,
                            isConnected = status == "Connected",
                            selectedDevice = selectedDevice
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Power Button
                        Button(
                            onClick = { viewModel.onPowerToggled(!isPowerOn) },
                            enabled = status == "Connected" && selectedDevice != null,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPowerOn) Color.White else MaterialTheme.colorScheme.primary,
                                contentColor = if (isPowerOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary
                            ),
                            border = if (isPowerOn) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
                        ) {
                            Icon(
                                imageVector = if (isPowerOn) Icons.Default.PowerOff else Icons.Default.PowerSettingsNew,
                                contentDescription = if (isPowerOn) "Turn OFF" else "Turn ON"
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (isPowerOn) "OFF" else "ON",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: MqttViewModel, navController: NavController) {
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.scrollToItem((logs.size - 1).coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Logs") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(paddingValues)
                .padding(8.dp)
        ) {
            items(logs) { log ->
                Text(
                    text = log,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun CircularTemperatureDial(
    temperature: Int?,
    onTemperatureChange: (Int) -> Unit,
    onTemperatureChangeFinished: () -> Unit,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val minTemp = 16
    val maxTemp = 30
    
    val startAngle = 140f
    val sweepAngle = 260f
    
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    val isValidTemp = temperature != null && temperature > 0

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(240.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { 
                        onDragEnd()
                        onTemperatureChangeFinished() 
                    },
                    onDragCancel = { onDragEnd() }
                ) { change, _ ->
                    change.consume()
                    val x = change.position.x - size.width / 2
                    val y = change.position.y - size.height / 2
                    var angle = (atan2(y.toDouble(), x.toDouble()) * 180 / PI).toFloat()
                    if (angle < 0) angle += 360f
                    
                    var adjustedAngle = angle - startAngle
                    if (adjustedAngle < -90) adjustedAngle += 360f
                    
                    if (adjustedAngle in 0f..sweepAngle) {
                        val fraction = adjustedAngle / sweepAngle
                        val newTemp = minTemp + (fraction * (maxTemp - minTemp)).roundToInt()
                        onTemperatureChange(newTemp.coerceIn(minTemp, maxTemp))
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val strokeWidth = 12.dp.toPx()
            val radius = size.minDimension / 2 - strokeWidth / 2
            
            drawArc(
                color = outlineColor,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                size = Size(radius * 2, radius * 2),
                topLeft = Offset(center.x - radius, center.y - radius)
            )
            
            val currentTempVal = if (isValidTemp) temperature!!.coerceIn(minTemp, maxTemp) else 24
            val currentFraction = (currentTempVal - minTemp).toFloat() / (maxTemp - minTemp)
            
            if (isValidTemp) {
                drawArc(
                    color = primaryColor,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle * currentFraction,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    size = Size(radius * 2, radius * 2),
                    topLeft = Offset(center.x - radius, center.y - radius)
                )
                
                val thumbAngle = (startAngle + sweepAngle * currentFraction) * (PI / 180f)
                val thumbX = center.x + radius * cos(thumbAngle).toFloat()
                val thumbY = center.y + radius * sin(thumbAngle).toFloat()
                
                drawCircle(
                    color = primaryColor,
                    radius = 16.dp.toPx(),
                    center = Offset(thumbX, thumbY)
                )
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.White,
                    radius = 6.dp.toPx(),
                    center = Offset(thumbX, thumbY)
                )
            }
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(Color.White)
        ) {
            val displayTemp = if (isValidTemp) "${temperature}°" else "--°"
            Text(
                text = displayTemp,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "CURRENT TEMP",
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 12.dp)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(percent = 50))
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                text = "COOL MODE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSelector(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onExpandedChange: ((Boolean) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { 
            expanded = it
            onExpandedChange?.invoke(it)
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            maxLines = 1,
            label = { 
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                ) 
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { 
                expanded = false 
                onExpandedChange?.invoke(false)
            }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { 
                        Text(
                            text = option,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                        onExpandedChange?.invoke(false)
                    }
                )
            }
        }
    }
}
@Composable
fun LoginScreen(viewModel: MqttViewModel, navController: NavController, sharedPref: android.content.SharedPreferences) {
    val users by viewModel.users.collectAsState()
    val status by viewModel.connectionStatus.collectAsState()
    
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (status != "Connected") {
            viewModel.connectToMqttBroker()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.AcUnit,
            contentDescription = "AC Icon",
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Welcome",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { 
                username = it
                errorMessage = ""
            },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { 
                password = it
                errorMessage = ""
            },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        
        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                val user = users.find { it.username == username && it.password == password }
                if (user != null) {
                    sharedPref.edit()
                        .putBoolean("is_logged_in", true)
                        .putString("logged_in_username", username)
                        .apply()
                    
                    navController.navigate("main_screen") {
                        popUpTo("login_screen") { inclusive = true }
                    }
                } else {
                    errorMessage = "Invalid username or password"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Login", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Status: $status",
            fontSize = 14.sp,
            color = if (status == "Connected") Color(0xFF4CAF50) else Color.Gray
        )
        if (status == "Connected" && users.isEmpty()) {
            Text(
                text = "Fetching users...",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(viewModel: MqttViewModel, navController: NavController) {
    val brokerUrl by viewModel.brokerUrl.collectAsState()
    val topic by viewModel.topic.collectAsState()
    val subscribeTopic by viewModel.subscribeTopic.collectAsState()
    val status by viewModel.connectionStatus.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = brokerUrl,
                onValueChange = { viewModel.updateBrokerUrl(it) },
                label = { Text("Broker URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { viewModel.updateTopic(it) },
                    label = { Text("Pub Topic") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = subscribeTopic,
                    onValueChange = { viewModel.updateSubscribeTopic(it) },
                    label = { Text("Sub Topic") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { viewModel.connectToMqttBroker() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Connect")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                val statusColor = when (status) {
                    "Connected" -> Color(0xFF4CAF50)
                    "Connecting..." -> Color.Gray
                    else -> Color.Red
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Status: $status",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog24H(
    title: String,
    initialTime24H: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val parts = initialTime24H.split(":")
    val initH = (if (parts.isNotEmpty()) parts[0].toIntOrNull() ?: 12 else 12).coerceIn(0, 23)
    val initM = (if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0).coerceIn(0, 59)

    val timePickerState = rememberTimePickerState(
        initialHour = initH,
        initialMinute = initM,
        is24Hour = true // Strictly enforce 24-hour UI
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(
                    state = timePickerState,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val formatted = String.format(Locale.US, "%02d:%02d", timePickerState.hour, timePickerState.minute)
                    onConfirm(formatted)
                }
            ) {
                Text("OK", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactDropdownSelector(
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onExpandedChange: ((Boolean) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { 
            expanded = it
            onExpandedChange?.invoke(it)
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            maxLines = 1,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            ),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { 
                expanded = false 
                onExpandedChange?.invoke(false)
            },
            modifier = Modifier.heightIn(max = 200.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontSize = 14.sp) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                        onExpandedChange?.invoke(false)
                    }
                )
            }
        }
    }
}

@Composable
fun TimeSelectorRow24H(
    label: String,
    time24H: String,
    onTimeChange: (String) -> Unit,
    onOpenDialog: () -> Unit,
    onExpandedChange: ((Boolean) -> Unit)? = null
) {
    val parts = time24H.split(":")
    val currentHour = if (parts.isNotEmpty()) "%02d".format((parts[0].toIntOrNull() ?: 0).coerceIn(0, 23)) else "00"
    val currentMinute = if (parts.size > 1) "%02d".format((parts[1].toIntOrNull() ?: 0).coerceIn(0, 59)) else "00"

    val hours24List = (0..23).map { "%02d".format(it) }
    val minutesList = (0..59).map { "%02d".format(it) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Clickable button to open Material 3 24H TimePicker Dialog
            TextButton(
                onClick = onOpenDialog,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = "Pick Time",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Pick Dial (24h)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactDropdownSelector(
                options = hours24List,
                selectedOption = currentHour,
                onOptionSelected = { newHour ->
                    val formatted = String.format(Locale.US, "%02d:%02d", newHour.toIntOrNull() ?: 0, currentMinute.toIntOrNull() ?: 0)
                    onTimeChange(formatted)
                },
                onExpandedChange = onExpandedChange,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = ":",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            CompactDropdownSelector(
                options = minutesList,
                selectedOption = currentMinute,
                onOptionSelected = { newMin ->
                    val formatted = String.format(Locale.US, "%02d:%02d", currentHour.toIntOrNull() ?: 0, newMin.toIntOrNull() ?: 0)
                    onTimeChange(formatted)
                },
                onExpandedChange = onExpandedChange,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSchedulerCard(
    viewModel: MqttViewModel,
    isConnected: Boolean,
    selectedDevice: String?,
    modifier: Modifier = Modifier
) {
    val serverScheduleOn by viewModel.scheduleOnTime.collectAsState()
    val serverScheduleOff by viewModel.scheduleOffTime.collectAsState()
    val isScheduleEnabled by viewModel.scheduleEnabled.collectAsState()

    var onTime24 by remember { mutableStateOf("14:00") }
    var offTime24 by remember { mutableStateOf("18:00") }
    var showOnPickerDialog by remember { mutableStateOf(false) }
    var showOffPickerDialog by remember { mutableStateOf(false) }

    LaunchedEffect(serverScheduleOn) {
        if (serverScheduleOn.isNotEmpty() && serverScheduleOn.contains(":")) {
            onTime24 = serverScheduleOn
        }
    }

    LaunchedEffect(serverScheduleOff) {
        if (serverScheduleOff.isNotEmpty() && serverScheduleOff.contains(":")) {
            offTime24 = serverScheduleOff
        }
    }

    if (showOnPickerDialog) {
        TimePickerDialog24H(
            title = "Set Turn ON Time (24H)",
            initialTime24H = onTime24,
            onConfirm = { selectedFormattedTime ->
                onTime24 = selectedFormattedTime
                showOnPickerDialog = false
            },
            onDismiss = { showOnPickerDialog = false }
        )
    }

    if (showOffPickerDialog) {
        TimePickerDialog24H(
            title = "Set Turn OFF Time (24H)",
            initialTime24H = offTime24,
            onConfirm = { selectedFormattedTime ->
                offTime24 = selectedFormattedTime
                showOffPickerDialog = false
            },
            onDismiss = { showOffPickerDialog = false }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Time Scheduler",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Time Scheduler (24h)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isScheduleEnabled) Color(0xFF4CAF50).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (isScheduleEnabled) "ENABLED" else "DISABLED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isScheduleEnabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Turn ON Selector (24H)
            TimeSelectorRow24H(
                label = "Turn ON at (24H):",
                time24H = onTime24,
                onTimeChange = { newTime -> onTime24 = newTime },
                onOpenDialog = { showOnPickerDialog = true },
                onExpandedChange = { expanded -> viewModel.isUserInteracting = expanded }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Turn OFF Selector (24H)
            TimeSelectorRow24H(
                label = "Turn OFF at (24H):",
                time24H = offTime24,
                onTimeChange = { newTime -> offTime24 = newTime },
                onOpenDialog = { showOffPickerDialog = true },
                onExpandedChange = { expanded -> viewModel.isUserInteracting = expanded }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Schedule Toggle Button (Direct 24H Value Passing)
            Button(
                onClick = {
                    viewModel.publishToggleSchedule(!isScheduleEnabled, onTime24, offTime24)
                },
                enabled = isConnected && selectedDevice != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScheduleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (isScheduleEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Text(
                    text = if (isScheduleEnabled) "Disable" else "Enable",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun AutoControlHysteresisCard(
    viewModel: MqttViewModel,
    isConnected: Boolean,
    selectedDevice: String?,
    modifier: Modifier = Modifier
) {
    val tempDiff by viewModel.tempDiff.collectAsState()
    val isAutoMode by viewModel.autoMode.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Thermostat,
                        contentDescription = "Auto-Control Hysteresis",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Auto-Control Hysteresis",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Switch(
                    checked = isAutoMode,
                    onCheckedChange = { enabled ->
                        viewModel.publishAutoMode(enabled)
                    },
                    enabled = isConnected && selectedDevice != null
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Temperature Differential:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Circular Minus (-) Button
                    IconButton(
                        onClick = {
                            if (tempDiff > 1) {
                                viewModel.publishTempDiff(tempDiff - 1)
                            }
                        },
                        enabled = isConnected && selectedDevice != null && tempDiff > 1,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (tempDiff > 1 && isConnected && selectedDevice != null) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Decrease Differential",
                            tint = if (tempDiff > 1 && isConnected && selectedDevice != null) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }

                    // Centered Dynamic Text (X°C)
                    Text(
                        text = "${tempDiff}°C",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.widthIn(min = 48.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    // Circular Plus (+) Button
                    IconButton(
                        onClick = {
                            if (tempDiff < 5) {
                                viewModel.publishTempDiff(tempDiff + 1)
                            }
                        },
                        enabled = isConnected && selectedDevice != null && tempDiff < 5,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (tempDiff < 5 && isConnected && selectedDevice != null) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Increase Differential",
                            tint = if (tempDiff < 5 && isConnected && selectedDevice != null) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }
    }
}
