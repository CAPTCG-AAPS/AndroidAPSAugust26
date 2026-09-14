package app.aaps.pump.tandem.common.comm.data

import com.jwoglom.pumpx2.pump.TandemError
import com.welie.blessed.HciStatus

class DisconnectDataDto(val onDisconnect: Boolean,
                        val hciStatus: HciStatus?,
                        val tandemError: TandemError?)