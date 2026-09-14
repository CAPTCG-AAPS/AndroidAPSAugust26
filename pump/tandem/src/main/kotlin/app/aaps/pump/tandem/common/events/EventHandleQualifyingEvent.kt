package app.aaps.pump.tandem.common.events

import app.aaps.core.interfaces.rx.events.Event
import com.jwoglom.pumpx2.pump.messages.response.qualifyingEvent.QualifyingEvent

class EventHandleQualifyingEvent (var events: Set<QualifyingEvent>,
                                  var dateTime: Long): Event()