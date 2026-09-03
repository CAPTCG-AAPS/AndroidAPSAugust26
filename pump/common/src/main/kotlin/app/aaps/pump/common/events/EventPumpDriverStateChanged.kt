package app.aaps.pump.common.events

import app.aaps.core.interfaces.rx.events.Event
import app.aaps.pump.common.defs.PumpDriverState

class EventPumpDriverStateChanged(var driverStatus: PumpDriverState) : Event()
