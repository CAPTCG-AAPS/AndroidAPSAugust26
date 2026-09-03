package app.aaps.pump.tandem.common.events

import app.aaps.core.interfaces.rx.events.Event
import app.aaps.pump.tandem.common.database.data.entity.TandemQualifyingEventEntity
import com.jwoglom.pumpx2.pump.messages.response.qualifyingEvent.QualifyingEvent

class EventDatabaseAddQEData(var eventEntities: List<TandemQualifyingEventEntity>): Event()
