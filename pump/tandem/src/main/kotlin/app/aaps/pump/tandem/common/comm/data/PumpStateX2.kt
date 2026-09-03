package app.aaps.pump.tandem.common.comm.data

class PumpStateX2(val pairingCode: String,
                  val jpakeDerivedSecret: String,
                  val jpakeServerNonce: String,
                  val savedBluetoothMAC: String,
                  val pumpSerialNum: String?
                  )