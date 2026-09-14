package app.aaps.pump.common.events

import app.aaps.core.interfaces.rx.events.Event


// This is event sent from device selection which requires us to re-read firmware version of device
class EventPumpConfigurationChanged : Event()
