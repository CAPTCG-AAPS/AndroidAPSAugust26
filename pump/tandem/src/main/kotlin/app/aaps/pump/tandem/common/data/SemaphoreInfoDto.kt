package app.aaps.pump.tandem.common.data

data class SemaphoreInfoDto(var semaphoreNotifications: Boolean = false,
                            var semaphoreEvents: Boolean = false,
                            var semaphoreHistory: Boolean = false,
                            var semaphoreNeedsRefresh: Boolean = false)