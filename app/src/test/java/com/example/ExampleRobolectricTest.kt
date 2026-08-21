package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertTrue(appName.isNotEmpty())
  }

  @Test
  fun `simulate 3 devices replying to broadcast with no device_id maintains isolated schedule state`() {
    val viewModel = MqttViewModel()

    // Setup 3 physical devices
    viewModel.setDevicesListForTesting(
      listOf(
        DeviceInfo(id = "dev_1", name = "Living Room AC"),
        DeviceInfo(id = "dev_2", name = "Bedroom AC"),
        DeviceInfo(id = "dev_3", name = "Office AC")
      )
    )
    viewModel.selectDevice("__ALL__")

    // Trigger status sync which resets response sequence counter
    viewModel.syncStatus()

    // 3 devices reply to ac/status/get broadcast without device_id in payload
    val payloadDev1 = """{"power": true, "temperature": 22, "mode": "cool", "schedule_on": "08:00", "schedule_off": "18:00", "schedule_enabled": true}"""
    val payloadDev2 = """{"power": false, "temperature": 25, "mode": "heat", "schedule_on": "10:00", "schedule_off": "22:00", "schedule_enabled": false}"""
    val payloadDev3 = """{"power": true, "temperature": 18, "mode": "auto", "schedule_on": "06:30", "schedule_off": "15:30", "schedule_enabled": true}"""

    viewModel.parseIncomingStatusPayload(payloadDev1)
    viewModel.parseIncomingStatusPayload(payloadDev2)
    viewModel.parseIncomingStatusPayload(payloadDev3)

    val dev1State = viewModel.getDeviceState("dev_1")
    val dev2State = viewModel.getDeviceState("dev_2")
    val dev3State = viewModel.getDeviceState("dev_3")

    assertNotNull("Device 1 state should be cached", dev1State)
    assertNotNull("Device 2 state should be cached", dev2State)
    assertNotNull("Device 3 state should be cached", dev3State)

    // Confirm device 1 schedule/timer state is isolated and NOT overwritten by device 2 or 3
    assertEquals("08:00", dev1State?.scheduleOnTime)
    assertEquals("18:00", dev1State?.scheduleOffTime)
    assertEquals(true, dev1State?.scheduleEnabled)
    assertEquals(22, dev1State?.temp)

    // Confirm device 2 schedule/timer state is isolated
    assertEquals("10:00", dev2State?.scheduleOnTime)
    assertEquals("22:00", dev2State?.scheduleOffTime)
    assertEquals(false, dev2State?.scheduleEnabled)
    assertEquals(25, dev2State?.temp)

    // Confirm device 3 schedule/timer state is isolated
    assertEquals("06:30", dev3State?.scheduleOnTime)
    assertEquals("15:30", dev3State?.scheduleOffTime)
    assertEquals(true, dev3State?.scheduleEnabled)
    assertEquals(18, dev3State?.temp)
  }

  @Test
  fun `devices replying with explicit device_id update isolated state map entries`() {
    val viewModel = MqttViewModel()

    viewModel.setDevicesListForTesting(
      listOf(
        DeviceInfo(id = "dev_1", name = "Living Room AC"),
        DeviceInfo(id = "dev_2", name = "Bedroom AC"),
        DeviceInfo(id = "dev_3", name = "Office AC")
      )
    )
    viewModel.selectDevice("__ALL__")

    val payloadDev2 = """{"device_id": "dev_2", "power": false, "temperature": 25, "mode": "heat", "schedule_on": "10:00", "schedule_off": "22:00", "schedule_enabled": false}"""
    val payloadDev1 = """{"device_id": "dev_1", "power": true, "temperature": 22, "mode": "cool", "schedule_on": "08:00", "schedule_off": "18:00", "schedule_enabled": true}"""
    val payloadDev3 = """{"device_id": "dev_3", "power": true, "temperature": 18, "mode": "auto", "schedule_on": "06:30", "schedule_off": "15:30", "schedule_enabled": true}"""

    viewModel.parseIncomingStatusPayload(payloadDev2)
    viewModel.parseIncomingStatusPayload(payloadDev1)
    viewModel.parseIncomingStatusPayload(payloadDev3)

    val dev1State = viewModel.getDeviceState("dev_1")
    val dev2State = viewModel.getDeviceState("dev_2")
    val dev3State = viewModel.getDeviceState("dev_3")

    assertEquals("08:00", dev1State?.scheduleOnTime)
    assertEquals("10:00", dev2State?.scheduleOnTime)
    assertEquals("06:30", dev3State?.scheduleOnTime)
  }

  @Test
  fun `requestDeviceStatus publishes explicit device_id payload for ALL and specific device`() {
    val viewModel = MqttViewModel()

    // Test __ALL__ mode
    viewModel.requestDeviceStatus("__ALL__")
    val logsAfterAll = viewModel.logs.value
    assertTrue(logsAfterAll.any { it.contains("""PUBLISHED: {"device_id":"__ALL__"} to ac/status/get""") })

    // Test specific device mode
    viewModel.requestDeviceStatus("dev_123")
    val logsAfterDev = viewModel.logs.value
    assertTrue(logsAfterDev.any { it.contains("""PUBLISHED: {"device_id":"dev_123"} to ac/status/get""") })

    // Test selectDevice triggers requestDeviceStatus immediately
    viewModel.selectDevice("dev_456")
    val logsAfterSelect = viewModel.logs.value
    assertTrue(logsAfterSelect.any { it.contains("""PUBLISHED: {"device_id":"dev_456"} to ac/status/get""") })

    // Test fetchStatus default (__ALL__)
    viewModel.selectDevice("__ALL__")
    viewModel.fetchStatus()
    val logsAfterFetch = viewModel.logs.value
    assertTrue(logsAfterFetch.any { it.contains("""PUBLISHED: {"device_id":"__ALL__"} to ac/status/get""") })
  }

  @Test
  fun `multi-key flexible extraction for swing, fan speed, and ac mode`() {
    val viewModel = MqttViewModel()
    viewModel.setDevicesListForTesting(
      listOf(DeviceInfo(id = "ac_unit_1", name = "Main AC"))
    )
    viewModel.selectDevice("ac_unit_1")

    // 1. Test fallback keys: swing_mode, fan_speed, ac_mode with string/number types
    val payload1 = """{"device_id": "ac_unit_1", "power": true, "temp": 24, "ac_mode": "dry", "fan_speed": "high", "swing_mode": "on"}"""
    viewModel.parseIncomingStatusPayload(payload1)

    assertEquals("dry", viewModel.mode.value)
    assertEquals("high", viewModel.fan.value)
    assertTrue(viewModel.swing.value)
    assertTrue(viewModel.swingHorizontal.value)
    assertTrue(viewModel.swingVertical.value)

    val state1 = viewModel.getDeviceState("ac_unit_1")
    assertEquals("dry", state1?.mode)
    assertEquals("high", state1?.fan)
    assertEquals(true, state1?.swing)

    // 2. Test numeric modes and fan levels, plus swing integer (1 / 0) and operation_mode / speed / swing_enabled
    val payload2 = """{"device_id": "ac_unit_1", "operation_mode": 1, "speed": 2, "swing_enabled": 0}"""
    viewModel.parseIncomingStatusPayload(payload2)

    assertEquals("heat", viewModel.mode.value)
    assertEquals("medium", viewModel.fan.value)
    assertFalse(viewModel.swing.value)
    assertFalse(viewModel.swingHorizontal.value)
    assertFalse(viewModel.swingVertical.value)

    // 3. Test work_mode, fan_level, is_swing
    val payload3 = """{"device_id": "ac_unit_1", "work_mode": "fan", "fan_level": 0, "is_swing": "auto"}"""
    viewModel.parseIncomingStatusPayload(payload3)

    assertEquals("fan", viewModel.mode.value)
    assertEquals("auto", viewModel.fan.value)
    assertTrue(viewModel.swing.value)
  }

  @Test
  fun `partial updates retain previous states for swing, fan, and mode`() {
    val viewModel = MqttViewModel()
    viewModel.setDevicesListForTesting(
      listOf(DeviceInfo(id = "ac_living", name = "Living Room AC"))
    )
    viewModel.selectDevice("ac_living")

    // Full state setup
    val fullPayload = """{"device_id": "ac_living", "power": true, "temp": 21, "mode": "cool", "fan": "medium", "swing": true}"""
    viewModel.parseIncomingStatusPayload(fullPayload)

    assertEquals("cool", viewModel.mode.value)
    assertEquals("medium", viewModel.fan.value)
    assertTrue(viewModel.swing.value)
    assertEquals(21, viewModel.temperature.value)

    // Partial update containing only temperature and power
    val partialPayload = """{"device_id": "ac_living", "power": true, "temp": 26}"""
    viewModel.parseIncomingStatusPayload(partialPayload)

    // Temperature is updated, while mode, fan, and swing are strictly retained
    assertEquals(26, viewModel.temperature.value)
    assertEquals("cool", viewModel.mode.value)
    assertEquals("medium", viewModel.fan.value)
    assertTrue(viewModel.swing.value)

    val cachedState = viewModel.getDeviceState("ac_living")
    assertEquals(26, cachedState?.temp)
    assertEquals("cool", cachedState?.mode)
    assertEquals("medium", cachedState?.fan)
    assertEquals(true, cachedState?.swing)
  }

  @Test
  fun `server payload omitting swing and fan keys does not wipe out existing swing and fan UI and cache states`() {
    val viewModel = MqttViewModel()
    viewModel.setDevicesListForTesting(
      listOf(DeviceInfo(id = "dev_27279", name = "Living Room"))
    )
    viewModel.selectDevice("dev_27279")

    // Setup initial state on device: swing is true and fan is high
    viewModel.onSwingToggled(true)
    viewModel.onFanSpeedChanged("high")
    viewModel.onModeChanged("cool")
    viewModel.onTemperatureChanged(22)

    assertTrue(viewModel.swing.value)
    assertEquals("high", viewModel.fan.value)
    assertEquals("cool", viewModel.mode.value)
    assertEquals(22, viewModel.temperature.value)

    // Server sends payload that omits swing and fan entirely:
    val serverPayload = """{"device_id":"dev_27279","power":true,"temp":25,"mode":"auto"}"""
    viewModel.parseIncomingStatusPayload(serverPayload)

    // Verify temp and mode are updated as given by server
    assertEquals(25, viewModel.temperature.value)
    assertEquals("auto", viewModel.mode.value)
    assertTrue(viewModel.powerState.value)

    // CRITICAL: swing and fan MUST be preserved from existing state
    assertTrue(viewModel.swing.value)
    assertEquals("high", viewModel.fan.value)

    val devState = viewModel.getDeviceState("dev_27279")
    assertEquals(25, devState?.temp)
    assertEquals("auto", devState?.mode)
    assertEquals(true, devState?.swing)
    assertEquals("high", devState?.fan)
  }

  @Test
  fun `inbound status for different device updates map cache only without altering active UI StateFlows`() {
    val viewModel = MqttViewModel()
    viewModel.setDevicesListForTesting(
      listOf(
        DeviceInfo(id = "dev_alpha", name = "Alpha AC"),
        DeviceInfo(id = "dev_beta", name = "Beta AC")
      )
    )

    // Select dev_alpha and set initial state
    viewModel.selectDevice("dev_alpha")
    val alphaInit = """{"device_id": "dev_alpha", "power": true, "temp": 20, "mode": "cool", "fan": "low", "swing": true}"""
    viewModel.parseIncomingStatusPayload(alphaInit)

    assertEquals(20, viewModel.temperature.value)
    assertEquals("cool", viewModel.mode.value)
    assertEquals("low", viewModel.fan.value)
    assertTrue(viewModel.swing.value)

    // Inbound payload arrives for dev_beta while user is viewing dev_alpha
    val betaPayload = """{"device_id": "dev_beta", "power": false, "temp": 28, "mode": "heat", "fan": "high", "swing": false}"""
    viewModel.parseIncomingStatusPayload(betaPayload)

    // Active UI state must remain unchanged (dev_alpha)
    assertEquals(20, viewModel.temperature.value)
    assertEquals("cool", viewModel.mode.value)
    assertEquals("low", viewModel.fan.value)
    assertTrue(viewModel.swing.value)

    // However, dev_beta state in map cache must be updated
    val betaState = viewModel.getDeviceState("dev_beta")
    assertNotNull(betaState)
    assertEquals(28, betaState?.temp)
    assertEquals("heat", betaState?.mode)
    assertEquals("high", betaState?.fan)
    assertEquals(false, betaState?.swing)
    assertEquals(false, betaState?.power)
  }

  @Test
  fun `command publishing for single device updates specific device cache and formats targeted payload`() {
    val viewModel = MqttViewModel()
    viewModel.setDevicesListForTesting(
      listOf(
        DeviceInfo(id = "dev_room1", name = "Room 1 AC"),
        DeviceInfo(id = "dev_room2", name = "Room 2 AC")
      )
    )

    viewModel.selectDevice("dev_room1")

    viewModel.onModeChanged("dry")
    viewModel.onFanSpeedChanged("turbo")
    viewModel.onSwingToggled(true)
    viewModel.onTemperatureChanged(23)

    assertEquals("dry", viewModel.mode.value)
    assertEquals("turbo", viewModel.fan.value)
    assertTrue(viewModel.swing.value)
    assertEquals(23, viewModel.temperature.value)

    val room1State = viewModel.getDeviceState("dev_room1")
    assertEquals("dry", room1State?.mode)
    assertEquals("turbo", room1State?.fan)
    assertEquals(true, room1State?.swing)
    assertEquals(23, room1State?.temp)

    val logs = viewModel.logs.value
    assertTrue(logs.any { it.contains(""""device_id": "dev_room1"""") && it.contains(""""mode": "dry"""") })
    assertTrue(logs.any { it.contains(""""device_id": "dev_room1"""") && it.contains(""""fan": "turbo"""") })
    assertTrue(logs.any { it.contains(""""device_id": "dev_room1"""") && it.contains(""""swing": true""") })
  }

  @Test
  fun `command publishing in ALL mode updates all devices in cache and formats global payload`() {
    val viewModel = MqttViewModel()
    viewModel.setDevicesListForTesting(
      listOf(
        DeviceInfo(id = "dev_a", name = "AC A"),
        DeviceInfo(id = "dev_b", name = "AC B")
      )
    )

    viewModel.selectDevice("__ALL__")

    viewModel.onModeChanged("auto")
    viewModel.onFanSpeedChanged("medium")
    viewModel.onSwingToggled(false)

    assertEquals("auto", viewModel.mode.value)
    assertEquals("medium", viewModel.fan.value)
    assertFalse(viewModel.swing.value)

    val devAState = viewModel.getDeviceState("dev_a")
    val devBState = viewModel.getDeviceState("dev_b")

    assertEquals("auto", devAState?.mode)
    assertEquals("medium", devAState?.fan)
    assertEquals(false, devAState?.swing)

    assertEquals("auto", devBState?.mode)
    assertEquals("medium", devBState?.fan)
    assertEquals(false, devBState?.swing)

    val logs = viewModel.logs.value
    assertTrue(logs.any { it.contains(""""device_id": "__ALL__"""") })
  }

  @Test
  fun `sendScheduleConfig formats strict 24H payload with native boolean and targets ac schedule config topic`() {
    val viewModel = MqttViewModel()
    viewModel.setDevicesListForTesting(
      listOf(DeviceInfo(id = "dev_39718", name = "Master Bedroom AC"))
    )

    viewModel.selectDevice("dev_39718")

    // Enabling schedule with 14:00 and 18:00
    viewModel.sendScheduleConfig(
      enabled = true,
      onTimeFormatted24H = "14:00",
      offTimeFormatted24H = "18:00"
    )

    assertTrue(viewModel.scheduleEnabled.value)
    assertEquals("14:00", viewModel.scheduleOnTime.value)
    assertEquals("18:00", viewModel.scheduleOffTime.value)

    val cachedState = viewModel.getDeviceState("dev_39718")
    assertEquals(true, cachedState?.scheduleEnabled)
    assertEquals("14:00", cachedState?.scheduleOnTime)
    assertEquals("18:00", cachedState?.scheduleOffTime)

    val logs = viewModel.logs.value
    val matchingLog = logs.firstOrNull { it.contains(""""device_id":"dev_39718"""") && it.contains(""""schedule_enabled":true""") }
    assertNotNull(matchingLog)
    assertTrue(matchingLog!!.contains(""""schedule_on":"14:00""""))
    assertTrue(matchingLog.contains(""""schedule_off":"18:00""""))

    // Disabling schedule
    viewModel.sendScheduleConfig(
      enabled = false,
      onTimeFormatted24H = "14:00",
      offTimeFormatted24H = "18:00"
    )

    assertFalse(viewModel.scheduleEnabled.value)
    val latestLogs = viewModel.logs.value
    val disabledLog = latestLogs.firstOrNull { it.contains(""""device_id":"dev_39718"""") && it.contains(""""schedule_enabled":false""") }
    assertNotNull(disabledLog)
  }

  @Test
  fun `sendScheduleConfig preserves leading zero 24-hour strings and produces on-off aliases`() {
    val viewModel = MqttViewModel()
    viewModel.setDevicesListForTesting(
      listOf(DeviceInfo(id = "dev_living", name = "Living Room AC"))
    )
    viewModel.selectDevice("dev_living")

    // Test with leading zeros and late night 24H format
    viewModel.sendScheduleConfig(
      enabled = true,
      onTimeFormatted24H = "08:05",
      offTimeFormatted24H = "23:59"
    )

    assertEquals("08:05", viewModel.scheduleOnTime.value)
    assertEquals("23:59", viewModel.scheduleOffTime.value)

    val logs = viewModel.logs.value
    val log = logs.firstOrNull { it.contains(""""on":"08:05"""") }
    assertNotNull(log)
    assertTrue(log!!.contains(""""off":"23:59""""))
    assertTrue(log.contains(""""schedule_on":"08:05""""))
    assertTrue(log.contains(""""schedule_off":"23:59""""))
    assertTrue(log.contains(""""enabled":true"""))
    assertTrue(log.contains(""""schedule_enabled":true"""))

    // Test incoming payload with "on" and "off" aliases updates 24H state
    viewModel.parseIncomingStatusPayload("""{"device_id": "dev_living", "enabled": true, "on": "13:01", "off": "18:30"}""")
    assertEquals("13:01", viewModel.scheduleOnTime.value)
    assertEquals("18:30", viewModel.scheduleOffTime.value)
    assertTrue(viewModel.scheduleEnabled.value)
  }

  @Test
  fun `publishTempDiff and publishAutoMode publish exact JSON to ac temp config topic and update state`() {
    val viewModel = MqttViewModel()
    viewModel.setDevicesListForTesting(
      listOf(DeviceInfo(id = "dev_55", name = "Test AC"))
    )
    viewModel.selectDevice("dev_55")

    // Test temp_diff publishing
    viewModel.publishTempDiff(3)
    assertEquals(3, viewModel.tempDiff.value)
    val devState = viewModel.getDeviceState("dev_55")
    assertEquals(3, devState?.tempDiff)

    val logs = viewModel.logs.value
    val tempDiffLog = logs.firstOrNull { it.contains("""PUBLISHED: {"device_id":"dev_55","temp_diff":3}""") }
    assertNotNull(tempDiffLog)

    // Test auto_mode publishing
    viewModel.publishAutoMode(true)
    assertTrue(viewModel.autoMode.value)
    assertEquals(true, devState?.autoMode)

    val autoModeLog = viewModel.logs.value.firstOrNull { it.contains("""PUBLISHED: {"device_id":"dev_55","auto_mode":true}""") }
    assertNotNull(autoModeLog)

    // Test incoming temp/config payload parsing
    val incoming = """{"device_id":"dev_55","temp_diff":2,"auto_mode":false}"""
    viewModel.parseIncomingStatusPayload(incoming)
    assertEquals(2, viewModel.tempDiff.value)
    assertFalse(viewModel.autoMode.value)
    assertEquals(2, devState?.tempDiff)
    assertEquals(false, devState?.autoMode)
  }

  @Test
  fun `publishTempDiff and publishAutoMode in ALL mode update all devices in cache`() {
    val viewModel = MqttViewModel()
    viewModel.setDevicesListForTesting(
      listOf(
        DeviceInfo(id = "dev_x", name = "AC X"),
        DeviceInfo(id = "dev_y", name = "AC Y")
      )
    )
    viewModel.selectDevice("__ALL__")

    viewModel.publishTempDiff(4)
    assertEquals(4, viewModel.tempDiff.value)
    assertEquals(4, viewModel.getDeviceState("dev_x")?.tempDiff)
    assertEquals(4, viewModel.getDeviceState("dev_y")?.tempDiff)

    viewModel.publishAutoMode(true)
    assertTrue(viewModel.autoMode.value)
    assertEquals(true, viewModel.getDeviceState("dev_x")?.autoMode)
    assertEquals(true, viewModel.getDeviceState("dev_y")?.autoMode)
  }
}
