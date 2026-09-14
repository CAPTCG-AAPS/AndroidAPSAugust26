package app.aaps.pump.tandem.common.data

import com.jwoglom.pumpx2.pump.messages.response.currentStatus.IDPSegmentResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.IDPSettingsResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ProfileStatusResponse
class PumpProfileDto {

    var profileStatusResponse : ProfileStatusResponse? = null
    var activeIdpId: Int? = null
    var idpSettingsResponse : IDPSettingsResponse? = null
    var mapSegments = mutableMapOf<Int, IDPSegmentResponse>()

    var isNewScenario: Boolean = false

    var success: Boolean = true
    var errorDescription: String? = null

}