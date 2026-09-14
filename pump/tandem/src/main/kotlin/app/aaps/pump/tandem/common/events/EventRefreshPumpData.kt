package app.aaps.pump.tandem.common.events

import app.aaps.core.interfaces.rx.events.Event
import app.aaps.pump.tandem.common.data.defs.RefreshData

class EventRefreshPumpData(var refreshEvents: List<RefreshData>): Event()